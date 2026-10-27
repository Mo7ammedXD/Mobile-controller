using System.Collections.Concurrent;
using System.Diagnostics;

namespace MobileController.Server;

/// <summary>
/// Line logger with timestamps relative to start-up. In the default asynchronous mode lines are handed to a
/// low-priority writer thread: a Windows console blocks writers while the user selects text (QuickEdit), and the
/// receive thread must never stall on that. Logging allocates, so it is only used for events, never per packet.
/// </summary>
internal sealed class Logger : IDisposable
{
    private readonly TextWriter _output;
    private readonly long _start = Stopwatch.GetTimestamp();
    private readonly BlockingCollection<string>? _queue;
    private readonly Thread? _writer;
    private readonly Lock _syncGate = new();

    public Logger(TextWriter output, bool synchronous = false)
    {
        _output = output;
        if (synchronous) return;
        _queue = new BlockingCollection<string>(boundedCapacity: 4096);
        _writer = new Thread(WriteLoop) { IsBackground = true, Name = "log writer", Priority = ThreadPriority.BelowNormal };
        _writer.Start();
    }

    public static Logger Null { get; } = new(TextWriter.Null, synchronous: true);

    public void Info(string message) => Post("", message);
    public void Warn(string message) => Post("WARNING: ", message);
    public void Error(string message) => Post("ERROR: ", message);

    private void Post(string level, string message)
    {
        string line = $"[{Stopwatch.GetElapsedTime(_start).TotalSeconds,9:F3}] {level}{message}";
        if (_queue is null)
        {
            lock (_syncGate) _output.WriteLine(line);
        }
        else
        {
            // Never block the caller; when the console is stuck for that long, dropping lines is the lesser evil.
            try { _queue.TryAdd(line); }
            catch (InvalidOperationException) { /* disposed during shutdown */ }
        }
    }

    private void WriteLoop()
    {
        foreach (string line in _queue!.GetConsumingEnumerable())
        {
            _output.WriteLine(line);
        }
        _output.Flush();
    }

    public void Dispose()
    {
        if (_queue is null || _queue.IsAddingCompleted) return;
        _queue.CompleteAdding();
        _writer!.Join(TimeSpan.FromSeconds(2));
    }
}
