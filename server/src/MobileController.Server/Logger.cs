using System.Collections.Concurrent;
using System.Diagnostics;
using System.Globalization;

namespace MobileController.Server;

internal enum LogLevel
{
    Info,
    Warning,
    Error,
}

/// <summary>One logged line: wall-clock time, seconds since start-up, level and text.</summary>
internal readonly record struct LogEntry(DateTime Time, double Seconds, LogLevel Level, string Message);

/// <summary>
/// Line logger with timestamps relative to start-up. In the default asynchronous mode lines are handed to a
/// low-priority writer thread: a Windows console blocks writers while the user selects text (QuickEdit), and the
/// receive thread must never stall on that. Logging allocates, so it is only used for events, never per packet.
///
/// Optionally the writer thread also appends every line, with its wall-clock time, to a log file that is rotated at
/// <see cref="MaxFileBytes"/> (keeping <see cref="KeptFiles"/> old ones), and the last <see cref="RecentCapacity"/>
/// entries are kept so a desktop app that attaches later can show what happened before.
/// </summary>
internal sealed class Logger : IDisposable
{
    public const long MaxFileBytes = 2 * 1024 * 1024;
    public const int KeptFiles = 4;
    public const int RecentCapacity = 500;

    private readonly TextWriter _output;
    private readonly long _start = Stopwatch.GetTimestamp();
    private readonly BlockingCollection<LogEntry>? _queue;
    private readonly Thread? _writer;
    private readonly Lock _syncGate = new();
    private readonly string? _filePath;
    private StreamWriter? _file;

    private readonly LogEntry[] _recent = new LogEntry[RecentCapacity];
    private int _recentNext, _recentCount;

    public Logger(TextWriter output, bool synchronous = false, string? filePath = null)
    {
        _output = output;
        _filePath = filePath;
        if (filePath is not null) OpenFile();
        if (synchronous) return;
        _queue = new BlockingCollection<LogEntry>(boundedCapacity: 4096);
        _writer = new Thread(WriteLoop) { IsBackground = true, Name = "log writer", Priority = ThreadPriority.BelowNormal };
        _writer.Start();
    }

    public static Logger Null { get; } = new(TextWriter.Null, synchronous: true);

    /// <summary>Why the log file could not be opened, or null (also null without a log file).</summary>
    public string? FileProblem { get; private set; }

    /// <summary>Called on the writer thread (the caller's thread when synchronous) for every entry. Must not throw.</summary>
    public Action<LogEntry>? Listener { get; set; }

    public void Info(string message) => Post(LogLevel.Info, message);
    public void Warn(string message) => Post(LogLevel.Warning, message);
    public void Error(string message) => Post(LogLevel.Error, message);

    /// <summary>The newest entries, oldest first (at most <see cref="RecentCapacity"/>).</summary>
    public LogEntry[] Recent()
    {
        lock (_recent)
        {
            var result = new LogEntry[_recentCount];
            int first = (_recentNext - _recentCount + RecentCapacity) % RecentCapacity;
            for (int i = 0; i < _recentCount; i++) result[i] = _recent[(first + i) % RecentCapacity];
            return result;
        }
    }

    private void Post(LogLevel level, string message)
    {
        var entry = new LogEntry(DateTime.Now, Stopwatch.GetElapsedTime(_start).TotalSeconds, level, message);
        if (_queue is null)
        {
            lock (_syncGate) Write(entry);
        }
        else
        {
            // Never block the caller; when the console is stuck for that long, dropping lines is the lesser evil.
            try { _queue.TryAdd(entry); }
            catch (InvalidOperationException) { /* disposed during shutdown */ }
        }
    }

    private void WriteLoop()
    {
        foreach (var entry in _queue!.GetConsumingEnumerable()) Write(entry);
        _output.Flush();
        _file?.Dispose();
    }

    private void Write(LogEntry entry)
    {
        string prefix = entry.Level switch
        {
            LogLevel.Warning => "WARNING: ",
            LogLevel.Error => "ERROR: ",
            _ => "",
        };
        _output.WriteLine($"[{entry.Seconds,9:F3}] {prefix}{entry.Message}");
        lock (_recent)
        {
            _recent[_recentNext] = entry;
            _recentNext = (_recentNext + 1) % RecentCapacity;
            if (_recentCount < RecentCapacity) _recentCount++;
        }
        WriteFile(entry, prefix);
        try { Listener?.Invoke(entry); }
        catch (Exception) { /* a listener must not take the log down */ }
    }

    private void WriteFile(LogEntry entry, string prefix)
    {
        if (_file is null) return;
        try
        {
            _file.WriteLine(entry.Time.ToString("yyyy-MM-dd HH:mm:ss.fff", CultureInfo.InvariantCulture) + " " + prefix + entry.Message);
            _file.Flush();
            if (_file.BaseStream.Length >= MaxFileBytes) Rotate();
        }
        catch (IOException e)
        {
            // A full disk must not stop the server: carry on without the file.
            FileProblem = e.Message;
            _file?.Dispose();
            _file = null;
            _output.WriteLine($"WARNING: log file {_filePath} stopped: {e.Message}");
        }
    }

    /// <summary>server.log -> server.1.log -> ... -> server.<see cref="KeptFiles"/>.log (dropped).</summary>
    private void Rotate()
    {
        _file!.Dispose();
        _file = null;
        string path = _filePath!;
        for (int i = KeptFiles; i >= 1; i--)
        {
            string from = i == 1 ? path : Numbered(path, i - 1);
            string to = Numbered(path, i);
            if (File.Exists(from)) File.Move(from, to, overwrite: true);
        }
        OpenFile();
    }

    private static string Numbered(string path, int n) =>
        Path.Combine(Path.GetDirectoryName(path) ?? "", $"{Path.GetFileNameWithoutExtension(path)}.{n}{Path.GetExtension(path)}");

    private void OpenFile()
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(_filePath!))!);
            // Shared read, so the desktop app (or Notepad) can follow the file while the server writes it.
            var stream = new FileStream(_filePath!, FileMode.Append, FileAccess.Write, FileShare.ReadWrite | FileShare.Delete);
            _file = new StreamWriter(stream) { AutoFlush = false };
            FileProblem = null;
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or ArgumentException or NotSupportedException)
        {
            FileProblem = e.Message;
            _file = null;
        }
    }

    public void Dispose()
    {
        if (_queue is null)
        {
            lock (_syncGate)
            {
                _file?.Dispose();
                _file = null;
            }
            return;
        }
        if (_queue.IsAddingCompleted) return;
        _queue.CompleteAdding();
        _writer!.Join(TimeSpan.FromSeconds(2));
    }
}
