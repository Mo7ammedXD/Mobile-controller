using System.Diagnostics;
using System.Net;
using System.Net.Sockets;

namespace MobileController.Server.Tests;

internal sealed class FakePad(PadFeedbackHandler onFeedback, bool recordHistory) : IVirtualPad
{
    private readonly List<PadState> _history = [];
    private volatile bool _disposed;

    public int SubmitCount { get; private set; }
    public PadState Last { get; private set; }
    public bool ThrowOnSubmit { get; set; }
    public bool Disposed => _disposed;
    public IReadOnlyList<PadState> History => _history;

    public void Submit(in PadState state)
    {
        if (ThrowOnSubmit) throw new InvalidOperationException("simulated driver failure");
        SubmitCount++;
        Last = state;
        if (recordHistory) _history.Add(state);
    }

    /// <summary>Plays the driver's notification thread.</summary>
    public void RaiseFeedback(byte largeMotor, byte smallMotor, byte ledNumber) => onFeedback(largeMotor, smallMotor, ledNumber);

    public void Dispose() => _disposed = true;
}

internal sealed class FakePadFactory(bool recordHistory = true) : IVirtualPadFactory
{
    private readonly Lock _gate = new();
    private readonly List<FakePad> _pads = [];

    public bool Fail { get; set; }

    public IReadOnlyList<FakePad> Pads
    {
        get { lock (_gate) return [.. _pads]; }
    }

    public IVirtualPad Create(PadFeedbackHandler onFeedback)
    {
        if (Fail) throw new InvalidOperationException("simulated plug-in failure");
        var pad = new FakePad(onFeedback, recordHistory);
        lock (_gate) _pads.Add(pad);
        return pad;
    }

    public void Dispose() { }
}

/// <summary>
/// A server on an ephemeral loopback port, pumped on the test thread so every test is deterministic. Game detection
/// is on (as in production) but reads <see cref="ForegroundApp"/> instead of the real desktop, and its thread is not
/// started: the foreground app changes only when a test calls <c>Server.GameDetector.Poll()</c>.
/// </summary>
internal sealed class ServerHarness : IDisposable
{
    public const string RocketLeague = @"C:\Program Files\Epic Games\rocketleague\Binaries\Win64\RocketLeague.exe";

    /// <summary>Timers that never fire on their own; tests shorten the ones they exercise.</summary>
    public static Options Quiet { get; } = new()
    {
        NeutralTimeoutMs = 60_000,
        DisconnectTimeoutMs = 60_000,
        FeedbackIntervalMs = 60_000,
        GameIntervalMs = 60_000,
    };

    private int _foregroundQueries;

    public ServerHarness(Options? options = null, bool dryRun = false, bool recordHistory = true, Func<string?>? foregroundApp = null)
    {
        Factory = new FakePadFactory(recordHistory);
        Options = (options ?? Quiet) with { Port = 0, BindAddress = IPAddress.Loopback, ServerName = "TestPC" };
        Log = new Logger(LogText, synchronous: true);
        Server = new GamepadServer(Options, dryRun ? null : Factory, Log, foregroundApp ?? QueryForegroundApp);
        Server.GameDetector?.Poll(); // what Start() does first
    }

    public FakePadFactory Factory { get; }
    public Options Options { get; }
    public StringWriter LogText { get; } = new();
    public Logger Log { get; }
    public GamepadServer Server { get; }

    /// <summary>What the scripted desktop has in front; null = no foreground window / unreadable process.</summary>
    public string? ForegroundApp { get; set; } = RocketLeague;

    /// <summary>How often the server looked at the (scripted) foreground window.</summary>
    public int ForegroundQueries => Volatile.Read(ref _foregroundQueries);

    public TestClient Connect(IPAddress? local = null) => new(Server.LocalEndPoint, local);

    public bool PumpUntil(Func<bool> condition, int timeoutMs = 2000)
    {
        var watch = Stopwatch.StartNew();
        while (!condition())
        {
            if (watch.ElapsedMilliseconds > timeoutMs) return false;
            Server.PumpOnce(1000);
        }
        return true;
    }

    /// <summary>Pumps until the server has seen <paramref name="count"/> more valid datagrams.</summary>
    public void PumpDatagrams(int count = 1)
    {
        long target = Server.DatagramsReceived + count;
        Assert.True(PumpUntil(() => Server.DatagramsReceived >= target), "server did not receive the datagram(s)");
    }

    public void PumpFor(int milliseconds)
    {
        var watch = Stopwatch.StartNew();
        while (watch.ElapsedMilliseconds < milliseconds) Server.PumpOnce(1000);
    }

    public void Dispose()
    {
        Server.Dispose();
        Log.Dispose();
    }

    private string? QueryForegroundApp()
    {
        Interlocked.Increment(ref _foregroundQueries);
        return ForegroundApp;
    }
}

internal sealed class TestClient : IDisposable
{
    private readonly byte[] _buffer = new byte[2048];

    public TestClient(IPEndPoint server, IPAddress? local = null)
    {
        Socket = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
        Socket.Bind(new IPEndPoint(local ?? IPAddress.Loopback, 0));
        Socket.Connect(server);
    }

    public Socket Socket { get; }

    public void Send(ReadOnlySpan<byte> datagram) => Socket.Send(datagram);

    public void SendHex(string hex) => Send(Hex.Bytes(hex));

    public void SendInput(ushort seq, in PadState state)
    {
        Span<byte> packet = stackalloc byte[Protocol.InputSize];
        Protocol.WriteInput(packet, seq, state);
        Socket.Send(packet);
    }

    public void SendBye()
    {
        Span<byte> packet = stackalloc byte[Protocol.ByeSize];
        Protocol.WriteBye(packet);
        Socket.Send(packet);
    }

    public byte[]? Receive(int timeoutMs = 1000)
    {
        if (!Socket.Poll(timeoutMs * 1000, SelectMode.SelectRead)) return null;
        int length = Socket.Receive(_buffer);
        return _buffer[..length];
    }

    /// <summary>Next FEEDBACK, skipping any other datagram type.</summary>
    public FeedbackPacket? ReceiveFeedback(int timeoutMs = 1000)
    {
        var watch = Stopwatch.StartNew();
        while (watch.ElapsedMilliseconds <= timeoutMs)
        {
            var datagram = Receive(Math.Max(1, timeoutMs - (int)watch.ElapsedMilliseconds));
            if (datagram is null) return null;
            if (Protocol.TryParseFeedback(datagram, out var feedback)) return feedback;
        }
        return null;
    }

    /// <summary>Waits up to <paramref name="timeoutMs"/> for a FEEDBACK that satisfies <paramref name="match"/>.</summary>
    public FeedbackPacket? WaitForFeedback(Func<FeedbackPacket, bool> match, int timeoutMs = 2000)
    {
        var watch = Stopwatch.StartNew();
        while (watch.ElapsedMilliseconds <= timeoutMs)
        {
            var feedback = ReceiveFeedback(Math.Max(1, timeoutMs - (int)watch.ElapsedMilliseconds));
            if (feedback is { } f && match(f)) return f;
        }
        return null;
    }

    /// <summary>Every datagram already queued, without waiting.</summary>
    public List<byte[]> ReceiveQueued()
    {
        var result = new List<byte[]>();
        while (Socket.Available > 0) result.Add(_buffer[..Socket.Receive(_buffer)]);
        return result;
    }

    /// <summary>Every FEEDBACK already queued, without waiting.</summary>
    public List<FeedbackPacket> ReceiveQueuedFeedback()
    {
        var result = new List<FeedbackPacket>();
        while (Socket.Available > 0)
        {
            int length = Socket.Receive(_buffer);
            if (Protocol.TryParseFeedback(_buffer.AsSpan(0, length), out var feedback)) result.Add(feedback);
        }
        return result;
    }

    public int Drain()
    {
        int count = 0;
        while (Socket.Available > 0)
        {
            Socket.Receive(_buffer);
            count++;
        }
        return count;
    }

    public void Dispose() => Socket.Dispose();
}

internal static class Hex
{
    public static byte[] Bytes(string hex) => Convert.FromHexString(hex.Replace(" ", "", StringComparison.Ordinal));

    /// <summary>The GAME datagram for an already normalised (lower-case ASCII) name.</summary>
    public static byte[] Game(string name) => [Protocol.Magic, 0x1B, (byte)name.Length, .. System.Text.Encoding.ASCII.GetBytes(name)];
}
