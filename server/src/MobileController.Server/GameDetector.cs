using System.Text;

namespace MobileController.Server;

/// <summary>
/// Foreground-app poller behind GAME (PROTOCOL.md §10). Runs on its own low-priority thread because the Win32
/// queries cost microseconds to milliseconds and allocate, which the receive thread must never do. Each change is
/// published as a complete, never-mutated datagram through one volatile reference, so the receive thread only reads
/// a ready-made array: no lock, no copy, no allocation on its side.
/// </summary>
internal sealed class GameDetector : IDisposable
{
    private readonly Func<string?> _source;
    private readonly int _pollMs;
    private readonly Logger _log;
    private readonly ManualResetEventSlim _stop = new();
    private volatile byte[]? _packet;
    private Thread? _thread;
    private int _disposed;

    /// <param name="source">
    /// Image path or file name of the foreground process, null when unknown. Exceptions count as unknown.
    /// </param>
    public GameDetector(Func<string?> source, int pollMs, Logger log)
    {
        _source = source;
        _pollMs = pollMs;
        _log = log;
    }

    /// <summary>The current GAME datagram; null until the first <see cref="Poll"/>. A new name is a new array.</summary>
    public byte[]? Packet => _packet;

    /// <summary>Polls once right away, so the first client already gets the real name, then keeps polling.</summary>
    public void Start()
    {
        if (_thread is not null) throw new InvalidOperationException("Already started.");
        Poll();
        _thread = new Thread(Run) { Name = "foreground detector", IsBackground = true, Priority = ThreadPriority.BelowNormal };
        _thread.Start();
    }

    /// <summary>One query; publishes and logs only when the name changed. Detector thread only (or before <see cref="Start"/>).</summary>
    public void Poll()
    {
        string? path;
        try
        {
            path = _source();
        }
        catch (Exception)
        {
            path = null;
        }

        Span<byte> name = stackalloc byte[Protocol.MaxGameNameBytes];
        name = name[..(path is null ? 0 : Protocol.NormalizeGameName(path, name))];
        var current = _packet;
        if (current is not null && name.SequenceEqual(current.AsSpan(Protocol.GameHeaderSize))) return;

        var packet = new byte[Protocol.GameHeaderSize + name.Length];
        Protocol.WriteGame(packet, name);
        _packet = packet;
        _log.Info($"Foreground app: {(name.IsEmpty ? "unknown" : Encoding.ASCII.GetString(name))}");
    }

    public void Dispose()
    {
        if (Interlocked.Exchange(ref _disposed, 1) != 0) return;
        _stop.Set();
        _thread?.Join();
        _stop.Dispose();
    }

    private void Run()
    {
        while (!_stop.Wait(_pollMs)) Poll();
    }
}
