namespace MobileController.Server.Tests;

public sealed class LoggerTests : IDisposable
{
    private readonly string _dir = Path.Combine(Path.GetTempPath(), $"mc-log-{Guid.NewGuid():N}");

    public void Dispose()
    {
        if (Directory.Exists(_dir)) Directory.Delete(_dir, recursive: true);
    }

    [Fact]
    public void Log_file_has_wall_clock_times_and_levels()
    {
        string path = Path.Combine(_dir, "server.log");
        using (var log = new Logger(TextWriter.Null, synchronous: true, filePath: path))
        {
            log.Info("hello");
            log.Warn("careful");
            log.Error("broken");
        }
        string[] lines = File.ReadAllLines(path);
        Assert.Equal(3, lines.Length);
        Assert.Matches(@"^\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d{3} hello$", lines[0]);
        Assert.EndsWith(" WARNING: careful", lines[1], StringComparison.Ordinal);
        Assert.EndsWith(" ERROR: broken", lines[2], StringComparison.Ordinal);
    }

    [Fact]
    public void Log_file_rotates_and_keeps_a_bounded_number_of_old_files()
    {
        string path = Path.Combine(_dir, "server.log");
        string line = new('x', 1000);
        using (var log = new Logger(TextWriter.Null, synchronous: true, filePath: path))
        {
            // Enough for six rotations: only KeptFiles old files survive.
            long lines = Logger.MaxFileBytes * (Logger.KeptFiles + 2) / line.Length;
            for (long i = 0; i < lines; i++) log.Info(line);
        }
        Assert.True(File.Exists(path));
        for (int n = 1; n <= Logger.KeptFiles; n++) Assert.True(File.Exists(Path.Combine(_dir, $"server.{n}.log")), $"server.{n}.log");
        Assert.False(File.Exists(Path.Combine(_dir, $"server.{Logger.KeptFiles + 1}.log")));
        Assert.True(new FileInfo(Path.Combine(_dir, "server.1.log")).Length >= Logger.MaxFileBytes);
    }

    [Fact]
    public void Recent_keeps_the_newest_entries_in_order()
    {
        using var log = new Logger(TextWriter.Null, synchronous: true);
        for (int i = 0; i < Logger.RecentCapacity + 20; i++) log.Info($"line {i}");
        var recent = log.Recent();
        Assert.Equal(Logger.RecentCapacity, recent.Length);
        Assert.Equal("line 20", recent[0].Message);
        Assert.Equal($"line {Logger.RecentCapacity + 19}", recent[^1].Message);
    }

    [Fact]
    public void An_unwritable_log_file_is_reported_not_fatal()
    {
        // A directory where the file should be: opening it fails.
        Directory.CreateDirectory(Path.Combine(_dir, "server.log"));
        using var log = new Logger(TextWriter.Null, synchronous: true, filePath: Path.Combine(_dir, "server.log"));
        Assert.NotNull(log.FileProblem);
        log.Info("still logging");
        Assert.Single(log.Recent());
    }

    [Fact]
    public void Listener_sees_every_entry_and_cannot_break_the_log()
    {
        var seen = new List<LogEntry>();
        using var log = new Logger(TextWriter.Null, synchronous: true);
        log.Listener = e =>
        {
            seen.Add(e);
            throw new InvalidOperationException("listener bug");
        };
        log.Warn("one");
        log.Info("two");
        Assert.Equal([LogLevel.Warning, LogLevel.Info], seen.Select(e => e.Level));
    }
}
