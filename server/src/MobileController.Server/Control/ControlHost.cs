using System.Buffers;
using System.Buffers.Binary;
using System.Collections.Concurrent;
using System.Diagnostics;
using System.IO.Pipes;
using System.Text;
using System.Text.Json;
using MobileController.Control;

namespace MobileController.Server.Control;

/// <summary>
/// Serves the desktop app over the control pipes (<see cref="ControlProtocol"/>). Two normal-priority threads: one
/// writes events, one reads commands. The receive thread never waits for either: snapshots come from
/// <see cref="StatusPublisher"/> and commands go through <see cref="GamepadServer.Commands"/>. A stuck or slow desktop
/// app can block only the events thread.
/// </summary>
internal sealed class ControlHost : IDisposable
{
    /// <summary>How often the events thread looks for new snapshots, live state, log lines and replies.</summary>
    private const int EventsPollMs = 15;
    private const int MaxQueuedLogs = 2000;

    private readonly string _name;
    private readonly GamepadServer _server;
    private readonly Options _options;
    private readonly Logger _log;
    private readonly DateTime _startedAt = DateTime.Now;
    private readonly long _start = Stopwatch.GetTimestamp();
    private readonly CancellationTokenSource _stop = new();
    private readonly ConcurrentQueue<LogEntry> _logs = new();
    private readonly ConcurrentQueue<ReplyMessage> _directReplies = new();
    private volatile bool _clientConnected;
    private Thread? _events, _commands;

    public ControlHost(string name, GamepadServer server, Options options, Logger log)
    {
        _name = name;
        _server = server;
        _options = options;
        _log = log;
        log.Listener = OnLog;
    }

    /// <summary>A desktop app is connected to the events pipe.</summary>
    public bool ClientConnected => _clientConnected;

    public void Start()
    {
        _events = new Thread(() => Guard(RunEvents)) { IsBackground = true, Name = "control events" };
        _commands = new Thread(() => Guard(RunCommands)) { IsBackground = true, Name = "control commands" };
        _events.Start();
        _commands.Start();
    }

    /// <summary>The control pipe is a convenience: whatever goes wrong with it is logged and must never stop the server.</summary>
    private void Guard(Action run)
    {
        try
        {
            run();
        }
        catch (Exception e)
        {
            _server.PublishEnabled = false;
            _log.Error($"Control pipe {_name} stopped: {e.Message}");
        }
    }

    public void Dispose()
    {
        _stop.Cancel();
        _events?.Join(TimeSpan.FromSeconds(2));
        _commands?.Join(TimeSpan.FromSeconds(2));
        _log.Listener = null;
        _server.PublishEnabled = false;
    }

    private void OnLog(LogEntry entry)
    {
        if (_clientConnected && _logs.Count < MaxQueuedLogs) _logs.Enqueue(entry);
    }

    // --- Events ------------------------------------------------------------------------------------------------

    private void RunEvents()
    {
        var buffer = new ArrayBufferWriter<byte>(16 * 1024);
        using var writer = new Utf8JsonWriter(buffer);
        var pads = new PadSnapshot[_server.Publisher.MaxPads];
        var present = new bool[pads.Length];
        var states = new PadState[pads.Length];
        var motors = new byte[pads.Length * 2];

        while (!_stop.IsCancellationRequested)
        {
            NamedPipeServerStream pipe;
            try
            {
                pipe = new NamedPipeServerStream(ControlProtocol.EventsPipe(_name), PipeDirection.Out, 1,
                    PipeTransmissionMode.Byte, PipeOptions.CurrentUserOnly | PipeOptions.Asynchronous);
            }
            catch (IOException e)
            {
                _log.Error($"Control pipe {_name} is not available (another server owns it?): {e.Message}");
                return;
            }
            using (pipe)
            {
                try
                {
                    pipe.WaitForConnectionAsync(_stop.Token).GetAwaiter().GetResult();
                }
                catch (OperationCanceledException)
                {
                    return;
                }

                // History first, then everything new: entries logged meanwhile may appear twice, never not at all.
                _logs.Clear();
                _clientConnected = true;
                _server.PublishEnabled = true;
                _log.Info("Desktop app connected (control pipe)");
                try
                {
                    WriteHello(pipe, buffer, writer);
                    foreach (var entry in _log.Recent()) WriteLog(pipe, buffer, writer, entry);
                    // Only what is published from now on: the buffers may still hold a previous client's data.
                    int lastSnapshot = _server.Publisher.Version, lastLive = _server.Publisher.LiveVersion;
                    while (!_stop.IsCancellationRequested)
                    {
                        if (_server.Publisher.Version != lastSnapshot)
                        {
                            lastSnapshot = _server.Publisher.Read(pads, out var server);
                            WriteSnapshot(pipe, buffer, writer, pads, server);
                        }
                        if (_server.Publisher.LiveVersion != lastLive)
                        {
                            lastLive = _server.Publisher.ReadLive(present, states, motors);
                            WriteLive(pipe, buffer, writer, present, states, motors);
                        }
                        while (_logs.TryDequeue(out var entry)) WriteLog(pipe, buffer, writer, entry);
                        while (_directReplies.TryDequeue(out var reply)) WriteReply(pipe, buffer, writer, reply.Id, reply.Error);
                        while (_server.Replies.TryDequeue(out var reply)) WriteReply(pipe, buffer, writer, reply.Id, Describe(reply.Error));
                        pipe.Flush();
                        _stop.Token.WaitHandle.WaitOne(EventsPollMs);
                    }
                }
                catch (IOException)
                {
                    // The desktop app went away (closed, crashed or restarted).
                }
                catch (ObjectDisposedException)
                {
                }
                finally
                {
                    _clientConnected = false;
                    _server.PublishEnabled = false;
                }
                if (_stop.IsCancellationRequested) return;
                _log.Info("Desktop app disconnected (control pipe)");
                if (_options.ExitWithPipe)
                {
                    _log.Info("Stopping: --exit-with-pipe");
                    _server.RequestStop();
                    return;
                }
            }
        }
    }

    private void WriteHello(Stream pipe, ArrayBufferWriter<byte> buffer, Utf8JsonWriter w)
    {
        Begin(buffer, w, "hello"u8);
        w.WriteNumber("controlVersion"u8, ControlProtocol.Version);
        w.WriteString("serverVersion"u8, Program.Version);
        w.WriteNumber("protocolVersion"u8, Protocol.Version);
        w.WriteNumber("pid"u8, Environment.ProcessId);
        w.WriteString("serverName"u8, _options.ServerName);
        w.WriteNumber("port"u8, _server.LocalEndPoint.Port);
        w.WriteString("bind"u8, _options.BindAddress.ToString());
        w.WriteNumber("maxPads"u8, _options.MaxPads);
        w.WriteBoolean("dryRun"u8, _options.DryRun);
        w.WriteString("defaultOutput"u8, KindName(_options.DefaultOutput));
        if (_options.LogFile is null) w.WriteNull("logFile"u8);
        else w.WriteString("logFile"u8, _options.LogFile);
        w.WriteString("startedAt"u8, _startedAt);
        End(pipe, buffer, w);
    }

    private void WriteSnapshot(Stream pipe, ArrayBufferWriter<byte> buffer, Utf8JsonWriter w, PadSnapshot[] pads, in ServerSnapshot s)
    {
        Begin(buffer, w, "snapshot"u8);
        w.WriteNumber("uptime"u8, Stopwatch.GetElapsedTime(_start).TotalSeconds);
        w.WriteNumber("datagrams"u8, s.Datagrams);
        w.WriteNumber("invalidFromUnknown"u8, s.InvalidFromUnknown);
        w.WriteNumber("blocked"u8, s.Blocked);
        w.WriteNumber("padsInUse"u8, s.PadsInUse);
        w.WriteStartObject("loop"u8);
        w.WriteNumber("passesPerSecond"u8, Math.Round(s.PassesPerSecond, 1));
        w.WriteNumber("maxPassMs"u8, Math.Round(s.MaxPassMs, 3));
        w.WriteEndObject();
        w.WriteStartObject("gc"u8);
        w.WriteNumber("gen0"u8, GC.CollectionCount(0));
        w.WriteNumber("gen1"u8, GC.CollectionCount(1));
        w.WriteNumber("gen2"u8, GC.CollectionCount(2));
        w.WriteNumber("pauseMs"u8, Math.Round(GC.GetTotalPauseDuration().TotalMilliseconds, 3));
        w.WriteEndObject();
        w.WriteStartArray("pads"u8);
        for (int i = 0; i < pads.Length; i++)
        {
            if (pads[i].Present) WritePad(w, ref pads[i], s.Taken);
        }
        w.WriteEndArray();
        End(pipe, buffer, w);
    }

    private void WritePad(Utf8JsonWriter w, ref PadSnapshot p, long taken)
    {
        Span<char> text = stackalloc char[Protocol.MaxStatusFieldBytes];
        w.WriteStartObject();
        w.WriteNumber("index"u8, p.Index);
        w.WriteString("endPoint"u8, text[..FormatEndpoint(p.EndpointKey, text)]);
        w.WriteNumber("connectedSeconds"u8, Math.Round((double)(taken - p.Connected) / Stopwatch.Frequency, 1));
        if (p.SupportsStatus)
        {
            p.DeviceId.TryFormat(text, out int written, "X16");
            w.WriteString("deviceId"u8, text[..written]);
            // Names are untrusted bytes: decoding replaces invalid UTF-8 (U+FFFD) instead of failing like the writer would.
            w.WriteString("deviceName"u8, text[..Encoding.UTF8.GetChars(((ReadOnlySpan<byte>)p.DeviceName)[..p.DeviceNameLength], text)]);
            w.WriteString("profile"u8, text[..Encoding.UTF8.GetChars(((ReadOnlySpan<byte>)p.ProfileName)[..p.ProfileNameLength], text)]);
            if (_options.Devices.TryGetValue(p.DeviceId, out var rule) && rule.Alias is not null) w.WriteString("alias"u8, rule.Alias);
            w.WriteNumber("appVersion"u8, p.AppVersion);
            if (p.Battery <= 100) w.WriteNumber("battery"u8, p.Battery);
            if (p.RttTenthsMs != Protocol.UnknownRtt) w.WriteNumber("phoneRttMs"u8, p.RttTenthsMs / 10.0);
        }
        w.WriteBoolean("charging"u8, (p.StatusFlags & StatusFlags.Charging) != 0);
        w.WriteBoolean("menuOpen"u8, (p.StatusFlags & StatusFlags.MenuOpen) != 0);
        w.WriteBoolean("motionActive"u8, (p.StatusFlags & StatusFlags.MotionActive) != 0);
        w.WriteNumber("rateHz"u8, p.RateHz);
        w.WriteString("preference"u8, p.Preference switch
        {
            OutputPreference.Xbox360 => ControlProtocol.KindXbox360,
            OutputPreference.DualShock4 => ControlProtocol.KindDualShock4,
            _ => ControlProtocol.PreferenceNone,
        });
        w.WriteString("kind"u8, KindName(p.Kind));
        if (p.Player <= 3) w.WriteNumber("player"u8, p.Player + 1);
        w.WriteNumber("largeMotor"u8, p.LargeMotor);
        w.WriteNumber("smallMotor"u8, p.SmallMotor);
        w.WriteBoolean("timedOut"u8, p.TimedOut);
        w.WriteNumber("inputsPerSecond"u8, Math.Round(p.InputsPerSecond, 1));
        w.WriteNumber("lossPercent"u8, Math.Round(p.LossPercent, 2));
        w.WriteNumber("jitterMs"u8, Math.Round(p.JitterMs, 2));
        w.WriteNumber("gapP50Ms"u8, Math.Round(p.GapP50Ms, 2));
        w.WriteNumber("gapP95Ms"u8, Math.Round(p.GapP95Ms, 2));
        w.WriteNumber("gapP99Ms"u8, Math.Round(p.GapP99Ms, 2));
        w.WriteNumber("gapMaxMs"u8, Math.Round(p.GapMaxMs, 2));
        w.WriteNumber("accepted"u8, p.Accepted);
        w.WriteNumber("duplicates"u8, p.Duplicates);
        w.WriteNumber("stale"u8, p.Stale);
        w.WriteNumber("invalid"u8, p.Invalid);
        w.WriteNumber("submits"u8, p.Submits);
        w.WriteEndObject();
    }

    private static void WriteLive(Stream pipe, ArrayBufferWriter<byte> buffer, Utf8JsonWriter w, bool[] present, PadState[] states, byte[] motors)
    {
        Begin(buffer, w, "live"u8);
        w.WriteStartArray("pads"u8);
        for (int i = 0; i < present.Length; i++)
        {
            if (!present[i]) continue;
            ref var s = ref states[i];
            w.WriteStartObject();
            w.WriteNumber("index"u8, i);
            w.WriteNumber("buttons"u8, s.Buttons);
            w.WriteNumber("leftTrigger"u8, s.LeftTrigger);
            w.WriteNumber("rightTrigger"u8, s.RightTrigger);
            w.WriteNumber("lx"u8, s.ThumbLX);
            w.WriteNumber("ly"u8, s.ThumbLY);
            w.WriteNumber("rx"u8, s.ThumbRX);
            w.WriteNumber("ry"u8, s.ThumbRY);
            w.WriteNumber("largeMotor"u8, motors[2 * i]);
            w.WriteNumber("smallMotor"u8, motors[2 * i + 1]);
            w.WriteEndObject();
        }
        w.WriteEndArray();
        End(pipe, buffer, w);
    }

    private static void WriteLog(Stream pipe, ArrayBufferWriter<byte> buffer, Utf8JsonWriter w, in LogEntry entry)
    {
        Begin(buffer, w, "log"u8);
        w.WriteString("time"u8, entry.Time);
        w.WriteString("level"u8, entry.Level switch
        {
            LogLevel.Warning => "warning",
            LogLevel.Error => "error",
            _ => "info",
        });
        w.WriteString("message"u8, entry.Message);
        End(pipe, buffer, w);
    }

    private static void WriteReply(Stream pipe, ArrayBufferWriter<byte> buffer, Utf8JsonWriter w, int id, string? error)
    {
        Begin(buffer, w, "reply"u8);
        w.WriteNumber("id"u8, id);
        w.WriteBoolean("ok"u8, error is null);
        if (error is not null) w.WriteString("error"u8, error);
        End(pipe, buffer, w);
    }

    private static void Begin(ArrayBufferWriter<byte> buffer, Utf8JsonWriter w, ReadOnlySpan<byte> type)
    {
        buffer.ResetWrittenCount();
        w.Reset(buffer);
        w.WriteStartObject();
        // First, so readers can dispatch on it without buffering the object.
        w.WriteString("type"u8, type);
    }

    private static void End(Stream pipe, ArrayBufferWriter<byte> buffer, Utf8JsonWriter w)
    {
        w.WriteEndObject();
        w.Flush();
        buffer.Write("\n"u8);
        pipe.Write(buffer.WrittenSpan);
    }

    /// <summary>"192.168.1.20:51234" from a <see cref="PadSlot.Key"/> (sockaddr_in: family, port big-endian, address).</summary>
    internal static int FormatEndpoint(ulong key, Span<char> destination)
    {
        Span<byte> raw = stackalloc byte[8];
        BinaryPrimitives.WriteUInt64LittleEndian(raw, key);
        int port = raw[2] << 8 | raw[3];
        destination.TryWrite($"{raw[4]}.{raw[5]}.{raw[6]}.{raw[7]}:{port}", out int written);
        return written;
    }

    private static string KindName(PadKind kind) => kind == PadKind.DualShock4 ? ControlProtocol.KindDualShock4 : ControlProtocol.KindXbox360;

    private static string? Describe(CommandError error) => error switch
    {
        CommandError.None => null,
        CommandError.NoSuchSlot => "no controller is connected in that slot",
        CommandError.NeedsStatus => "the phone's app is too old for this (update it)",
        _ => error.ToString(),
    };

    // --- Commands ----------------------------------------------------------------------------------------------

    private void RunCommands()
    {
        while (!_stop.IsCancellationRequested)
        {
            NamedPipeServerStream pipe;
            try
            {
                pipe = new NamedPipeServerStream(ControlProtocol.CommandsPipe(_name), PipeDirection.In, 1,
                    PipeTransmissionMode.Byte, PipeOptions.CurrentUserOnly | PipeOptions.Asynchronous);
            }
            catch (IOException)
            {
                return; // RunEvents reports the clash.
            }
            using (pipe)
            {
                try
                {
                    pipe.WaitForConnectionAsync(_stop.Token).GetAwaiter().GetResult();
                    using var reader = new StreamReader(pipe, Encoding.UTF8);
                    while (!_stop.IsCancellationRequested)
                    {
                        string? line = reader.ReadLineAsync(_stop.Token).AsTask().GetAwaiter().GetResult();
                        if (line is null) break;
                        Handle(line);
                    }
                }
                catch (OperationCanceledException)
                {
                    return;
                }
                catch (IOException)
                {
                    // The desktop app went away; wait for the next one.
                }
            }
        }
    }

    internal void Handle(string line)
    {
        CommandMessage? message;
        try
        {
            message = ControlProtocol.ParseCommand(line);
        }
        catch (JsonException e)
        {
            _log.Warn($"Ignored a malformed control command: {e.Message}");
            return;
        }
        if (message is null) return;
        if (!TryTranslate(message, out var command, out string? error))
        {
            _directReplies.Enqueue(new ReplyMessage(message.Id, false, error));
            return;
        }
        if (!_server.Commands.TryEnqueue(command)) _directReplies.Enqueue(new ReplyMessage(message.Id, false, "the server is busy, try again"));
    }

    private static bool TryTranslate(CommandMessage m, out ControlCommand command, out string? error)
    {
        command = default;
        error = null;
        CommandOp op;
        int value = 0;
        switch (m.Op)
        {
            case ControlProtocol.OpLive:
                op = CommandOp.Live;
                value = m.Value == "on" ? 1 : 0;
                break;
            case ControlProtocol.OpIdentify:
                op = CommandOp.Identify;
                break;
            case ControlProtocol.OpDisconnect:
                op = CommandOp.Disconnect;
                break;
            case ControlProtocol.OpRumbleTest:
                op = CommandOp.RumbleTest;
                break;
            case ControlProtocol.OpOutput:
                op = CommandOp.Output;
                if (string.IsNullOrEmpty(m.Value)) value = (int)OutputPreference.None;
                else if (Options.TryParseKind(m.Value, out var kind))
                    value = (int)(kind == PadKind.DualShock4 ? OutputPreference.DualShock4 : OutputPreference.Xbox360);
                else
                {
                    error = $"unknown output '{m.Value}' (xbox360, ds4 or empty)";
                    return false;
                }
                break;
            default:
                error = $"unknown command '{m.Op}'";
                return false;
        }
        if (op != CommandOp.Live && m.Index is null)
        {
            error = $"'{m.Op}' needs the controller index";
            return false;
        }
        command = new ControlCommand(m.Id, op, m.Index ?? -1, value);
        return true;
    }
}
