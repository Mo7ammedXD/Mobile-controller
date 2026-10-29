using System.Diagnostics;
using System.IO.Pipes;
using System.Net;
using System.Net.Sockets;
using System.Text;
using MobileController.Control;
using MobileController.Server.Control;

namespace MobileController.Server.Tests;

/// <summary>The desktop app's control pipes against a real server (pumped on the test thread) and a real <see cref="ControlHost"/>.</summary>
public sealed class ControlPlaneTests
{
    private static readonly PadState Sample = new(0x1001, 10, 255, 32767, -32767, -1, 256);

    /// <summary>A desktop app: reads the events pipe on a thread of its own and writes commands.</summary>
    private sealed class Desktop : IDisposable
    {
        private readonly NamedPipeClientStream _events;
        private readonly NamedPipeClientStream _commands;
        private readonly StreamWriter _writer;
        private readonly Thread _reader;
        private int _nextId;

        public Desktop(string name)
        {
            _events = new NamedPipeClientStream(".", ControlProtocol.EventsPipe(name), PipeDirection.In);
            _events.Connect(5000);
            _commands = new NamedPipeClientStream(".", ControlProtocol.CommandsPipe(name), PipeDirection.Out);
            _commands.Connect(5000);
            _writer = new StreamWriter(_commands, new UTF8Encoding(false)) { AutoFlush = true };
            _reader = new Thread(() =>
            {
                try
                {
                    using var reader = new StreamReader(_events, Encoding.UTF8);
                    while (reader.ReadLine() is { } line)
                    {
                        if (ControlProtocol.ParseEvent(line) is not { } message) continue;
                        lock (Messages) Messages.Add(message);
                    }
                }
                catch (IOException)
                {
                }
                catch (ObjectDisposedException)
                {
                }
            }) { IsBackground = true };
            _reader.Start();
        }

        /// <summary>Everything received so far (lock it to read).</summary>
        public List<ControlMessage> Messages { get; } = [];

        public int Send(string op, int? index = null, string? value = null)
        {
            int id = ++_nextId;
            _writer.WriteLine(ControlProtocol.Serialize(new CommandMessage(id, op, index, value)));
            return id;
        }

        /// <summary>Pumps the server until a message of type <typeparamref name="T"/> matching <paramref name="match"/> has arrived.</summary>
        public T Wait<T>(ServerHarness h, Func<T, bool>? match = null, int timeoutMs = 3000) where T : ControlMessage
        {
            var watch = Stopwatch.StartNew();
            while (watch.ElapsedMilliseconds < timeoutMs)
            {
                lock (Messages)
                {
                    foreach (var message in Messages)
                    {
                        if (message is T t && (match is null || match(t))) return t;
                    }
                }
                h.Server.PumpOnce(1000);
            }
            throw new TimeoutException($"no {typeof(T).Name} within {timeoutMs} ms");
        }

        public void Dispose()
        {
            _writer.Dispose();
            _commands.Dispose();
            _events.Dispose();
            _reader.Join(2000);
        }
    }

    private static (ServerHarness Harness, ControlHost Host, string Name) Start(Options? options = null)
    {
        // Short: on Linux and macOS a pipe is a Unix socket, whose path is limited to about 100 characters.
        string name = $"mc{Guid.NewGuid():N}"[..12];
        var h = new ServerHarness((options ?? ServerHarness.Quiet) with { PublishIntervalMs = 20, LiveIntervalMs = 10, ControlPipe = name });
        var host = new ControlHost(name, h.Server, h.Options, h.Log);
        host.Start();
        return (h, host, name);
    }

    [Fact]
    public void Hello_then_snapshots_with_every_phone()
    {
        var (h, host, name) = Start();
        using (h)
        using (host)
        using (var desktop = new Desktop(name))
        using (var phone = h.Connect())
        {
            var hello = desktop.Wait<HelloMessage>(h);
            Assert.Equal(ControlProtocol.Version, hello.ControlVersion);
            Assert.Equal("TestPC", hello.ServerName);
            Assert.Equal(h.Server.LocalEndPoint.Port, hello.Port);
            Assert.Equal(Environment.ProcessId, hello.Pid);

            phone.SendStatus(0xC0FFEE, "Pixel 8", battery: 77, output: OutputPreference.DualShock4, profile: "Racing");
            phone.SendInput(5, Sample);
            h.PumpDatagrams(2);

            var snapshot = desktop.Wait<SnapshotMessage>(h, s => s.Pads.Count == 1);
            var pad = snapshot.Pads[0];
            Assert.Equal(0, pad.Index);
            Assert.Equal(((IPEndPoint)phone.Socket.LocalEndPoint!).ToString(), pad.EndPoint);
            Assert.Equal("0000000000C0FFEE", pad.DeviceId);
            Assert.Equal("Pixel 8", pad.DeviceName);
            Assert.Equal("Racing", pad.Profile);
            Assert.Equal(77, pad.Battery);
            Assert.Equal(4.2, pad.PhoneRttMs);
            Assert.Equal(120, pad.RateHz);
            Assert.Equal(ControlProtocol.KindDualShock4, pad.Preference);
            Assert.Equal(ControlProtocol.KindDualShock4, pad.Kind);
            Assert.Equal(1, pad.Accepted);
            Assert.Equal(1, snapshot.PadsInUse);
            Assert.True(snapshot.Loop.PassesPerSecond > 0, $"passes/s {snapshot.Loop.PassesPerSecond}");
            Assert.True(snapshot.Datagrams >= 2, $"datagrams {snapshot.Datagrams}");

            // The log goes to the desktop app too.
            Assert.NotNull(desktop.Wait<LogMessage>(h, l => l.Message.Contains("is \"Pixel 8\"")));
        }
    }

    [Fact]
    public void Identify_reaches_the_phone_and_unknown_slots_are_refused()
    {
        var (h, host, name) = Start();
        using (h)
        using (host)
        using (var desktop = new Desktop(name))
        using (var phone = h.Connect())
        {
            desktop.Wait<HelloMessage>(h);
            phone.SendStatus(1);
            phone.SendInput(0, Sample);
            h.PumpDatagrams(2);
            phone.Drain();

            int id = desktop.Send(ControlProtocol.OpIdentify, 0);
            Assert.True(desktop.Wait<ReplyMessage>(h, r => r.Id == id).Ok);
            h.PumpFor(150);
            var commands = phone.ReceiveQueued().Where(d => Protocol.TryParseCommand(d, out byte op, out _) && op == Protocol.CommandIdentify).ToList();
            Assert.Equal(3, commands.Count); // repeated for loss, same token
            Assert.All(commands, c => Assert.Equal(commands[0], c));

            int bad = desktop.Send(ControlProtocol.OpIdentify, 3);
            var refused = desktop.Wait<ReplyMessage>(h, r => r.Id == bad);
            Assert.False(refused.Ok);
            Assert.Contains("no controller", refused.Error);

            int unknown = desktop.Send("selfdestruct", 0);
            Assert.Contains("unknown command", desktop.Wait<ReplyMessage>(h, r => r.Id == unknown).Error);
        }
    }

    [Fact]
    public void Disconnect_unplugs_and_holds_the_phone_off()
    {
        var (h, host, name) = Start();
        using (h)
        using (host)
        using (var desktop = new Desktop(name))
        using (var phone = h.Connect())
        {
            desktop.Wait<HelloMessage>(h);
            phone.SendInput(0, Sample);
            h.PumpDatagrams();
            var pad = Assert.Single(h.Factory.Pads);

            int id = desktop.Send(ControlProtocol.OpDisconnect, 0);
            Assert.True(desktop.Wait<ReplyMessage>(h, r => r.Id == id).Ok);
            Assert.True(pad.Disposed);

            // The phone keeps sending, but does not get a new pad right away.
            phone.SendInput(1, Sample);
            h.PumpDatagrams();
            Assert.Single(h.Factory.Pads);
            Assert.Contains("disconnected from the desktop app", h.LogText.ToString());
        }
    }

    [Fact]
    public void Choosing_the_output_replugs_at_the_next_input()
    {
        var (h, host, name) = Start();
        using (h)
        using (host)
        using (var desktop = new Desktop(name))
        using (var phone = h.Connect())
        {
            desktop.Wait<HelloMessage>(h);
            phone.SendStatus(0xAB);
            phone.SendInput(0, Sample);
            h.PumpDatagrams(2);
            Assert.Equal(PadKind.Xbox360, Assert.Single(h.Factory.Pads).Kind);

            int id = desktop.Send(ControlProtocol.OpOutput, 0, ControlProtocol.KindDualShock4);
            Assert.True(desktop.Wait<ReplyMessage>(h, r => r.Id == id).Ok);
            phone.SendInput(1, Sample);
            h.PumpDatagrams();
            Assert.Equal(2, h.Factory.Pads.Count);
            Assert.Equal(PadKind.DualShock4, h.Factory.Pads[1].Kind);

            // The replugged slot still knows the phone.
            var snapshot = desktop.Wait<SnapshotMessage>(h, s => s.Pads.Count == 1 && s.Pads[0].Kind == ControlProtocol.KindDualShock4);
            Assert.Equal("00000000000000AB", snapshot.Pads[0].DeviceId);

            // Back to the phone's own choice (none: the server default, Xbox 360).
            id = desktop.Send(ControlProtocol.OpOutput, 0, "");
            Assert.True(desktop.Wait<ReplyMessage>(h, r => r.Id == id).Ok);
            phone.SendInput(2, Sample);
            h.PumpDatagrams();
            Assert.Equal(PadKind.Xbox360, h.Factory.Pads[2].Kind);
        }
    }

    [Fact]
    public void Live_state_flows_only_while_asked_for()
    {
        var (h, host, name) = Start();
        using (h)
        using (host)
        using (var desktop = new Desktop(name))
        using (var phone = h.Connect())
        {
            desktop.Wait<HelloMessage>(h);
            phone.SendInput(0, Sample);
            h.PumpDatagrams();
            h.PumpFor(100);
            lock (desktop.Messages) Assert.DoesNotContain(desktop.Messages, m => m is LiveMessage);

            desktop.Send(ControlProtocol.OpLive, value: "on");
            var live = desktop.Wait<LiveMessage>(h, l => l.Pads.Count == 1);
            Assert.Equal(new LivePad(0, 0x1001, 10, 255, 32767, -32767, -1, 256, 0, 0), live.Pads[0]);
        }
    }

    [Fact]
    public void Exit_with_pipe_stops_the_server_when_the_desktop_app_leaves()
    {
        var (h, host, name) = Start(ServerHarness.Quiet with { ExitWithPipe = true });
        using (h)
        using (host)
        {
            h.Server.Start();
            var desktop = new Desktop(name);
            Assert.True(SpinWait.SpinUntil(() => host.ClientConnected, 3000));
            desktop.Dispose();
            Assert.True(h.Server.WaitForExit(TimeSpan.FromSeconds(3)), "the server kept running");
        }
    }

    [Fact]
    public void Endpoints_are_formatted_from_the_slot_key()
    {
        var address = new IPEndPoint(IPAddress.Parse("192.168.1.20"), 51234).Serialize();
        Span<char> text = stackalloc char[32];
        Assert.Equal("192.168.1.20:51234", text[..ControlHost.FormatEndpoint(PadSlot.KeyOf(address), text)].ToString());
    }

    [Fact]
    public void Sequence_lock_never_hands_out_a_torn_snapshot()
    {
        var publisher = new StatusPublisher(4);
        var slots = Enumerable.Range(0, 4)
            .Select(i => (PadSlot?)new PadSlot(i, new IPEndPoint(IPAddress.Loopback, 1000 + i).Serialize(), 0))
            .ToArray();
        using var stop = new CancellationTokenSource();
        var writer = new Thread(() =>
        {
            long n = 0;
            while (!stop.IsCancellationRequested)
            {
                n++;
                foreach (var slot in slots) slot!.Stats.Accepted = n;
                publisher.Publish(slots, new ServerSnapshot { Datagrams = n });
                // Far more often than the real 250 ms, but with gaps a reader can land in.
                Thread.SpinWait(100);
            }
        });
        writer.Start();
        var pads = new PadSnapshot[4];
        var watch = Stopwatch.StartNew();
        int reads = 0;
        while (watch.ElapsedMilliseconds < 500)
        {
            publisher.Read(pads, out var server);
            // Every field of one snapshot comes from the same Publish.
            Assert.All(pads, p => Assert.Equal(server.Datagrams, p.Accepted));
            reads++;
        }
        stop.Cancel();
        writer.Join();
        Assert.True(reads > 100);
    }
}
