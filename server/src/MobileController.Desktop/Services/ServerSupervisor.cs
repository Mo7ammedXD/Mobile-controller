using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Windows.Threading;

namespace MobileController.Desktop.Services;

public enum ServerState
{
    Stopped,
    Starting,
    Running,

    /// <summary>The server exited by itself (see <see cref="ServerSupervisor.Problem"/>).</summary>
    Exited,
}

/// <summary>
/// Starts MobileController.Server.exe (next to this app) as a child process serving the control pipe, and keeps it
/// running: a crash is restarted with a growing delay; an exit that a restart cannot fix (bad settings, missing driver,
/// port in use) is explained instead. The child is in a Windows job object that kills it when this app ends, however it
/// ends, so a server never outlives its desktop app (and --exit-with-pipe stops it when the pipe closes anyway).
/// </summary>
public sealed partial class ServerSupervisor : ObservableObject, IDisposable
{
    // Exit codes of MobileController.Server (Program.cs).
    private const int ExitFailure = 1;
    private const int ExitBadArguments = 2;
    private const int ExitViGEm = 3;
    private const int ExitNetwork = 4;

    private static readonly TimeSpan[] RestartDelays = [TimeSpan.FromSeconds(1), TimeSpan.FromSeconds(3), TimeSpan.FromSeconds(10)];

    private readonly Dispatcher _dispatcher;
    private readonly JobObject _job = new();
    private readonly List<string> _errorLines = [];
    private Process? _process;
    private ServerState _state;
    private string? _problem;
    private int? _exitCode;
    private int _crashes;
    private DispatcherTimer? _restartTimer;
    private bool _disposed;

    public ServerSupervisor(Dispatcher dispatcher)
    {
        _dispatcher = dispatcher;
        ServerPath = Path.Combine(AppContext.BaseDirectory, "MobileController.Server.exe");
        PipeName = $"MobileController-{Environment.ProcessId}";
    }

    public string ServerPath { get; set; }

    /// <summary>A pipe name of our own, so two desktop apps (two users) never talk to each other's server.</summary>
    public string PipeName { get; }

    public ServerState State
    {
        get => _state;
        private set => Set(ref _state, value);
    }

    /// <summary>Why the server is not running, in words a user can act on (null while it runs).</summary>
    public string? Problem
    {
        get => _problem;
        private set => Set(ref _problem, value);
    }

    public int? ExitCode
    {
        get => _exitCode;
        private set => Set(ref _exitCode, value);
    }

    /// <summary>The last lines the server wrote to its error output.</summary>
    public string ErrorOutput
    {
        get
        {
            lock (_errorLines) return string.Join(Environment.NewLine, _errorLines);
        }
    }

    public event EventHandler? Started;

    public void Start()
    {
        if (_disposed || State is ServerState.Starting or ServerState.Running) return;
        _restartTimer?.Stop();
        if (!File.Exists(ServerPath))
        {
            State = ServerState.Exited;
            Problem = $"MobileController.Server.exe was not found next to this app ({ServerPath}). Reinstall the app.";
            return;
        }
        lock (_errorLines) _errorLines.Clear();
        var info = new ProcessStartInfo(ServerPath)
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            WorkingDirectory = AppContext.BaseDirectory,
        };
        info.ArgumentList.Add("--control-pipe");
        info.ArgumentList.Add(PipeName);
        info.ArgumentList.Add("--exit-with-pipe");
        info.ArgumentList.Add("--log-file");
        var process = new Process { StartInfo = info, EnableRaisingEvents = true };
        process.OutputDataReceived += (_, _) => { }; // the log arrives through the pipe; just keep the pipe drained
        process.ErrorDataReceived += (_, e) =>
        {
            if (e.Data is null) return;
            lock (_errorLines)
            {
                _errorLines.Add(e.Data);
                if (_errorLines.Count > 40) _errorLines.RemoveAt(0);
            }
        };
        process.Exited += (_, _) => _dispatcher.BeginInvoke(() => OnExited(process));
        State = ServerState.Starting;
        Problem = null;
        ExitCode = null;
        try
        {
            process.Start();
        }
        catch (Exception e) when (e is Win32Exception or InvalidOperationException)
        {
            State = ServerState.Exited;
            Problem = $"The server could not be started: {e.Message}";
            return;
        }
        _job.Assign(process);
        process.BeginOutputReadLine();
        process.BeginErrorReadLine();
        _process = process;
        Started?.Invoke(this, EventArgs.Empty);
    }

    /// <summary>The control pipe answered: the server is up.</summary>
    public void MarkRunning()
    {
        if (State == ServerState.Starting) State = ServerState.Running;
        _crashes = 0;
    }

    /// <summary>Stops the server (it exits on its own once the pipe closes; this waits for that, then insists).</summary>
    public void Stop()
    {
        _restartTimer?.Stop();
        var process = _process;
        _process = null;
        if (process is null) return;
        try
        {
            if (!process.HasExited && !process.WaitForExit(3000)) process.Kill(entireProcessTree: true);
        }
        catch (InvalidOperationException)
        {
        }
        process.Dispose();
        State = ServerState.Stopped;
    }

    public void Restart()
    {
        Stop();
        _crashes = 0;
        Start();
    }

    private void OnExited(Process process)
    {
        if (!ReferenceEquals(process, _process)) return; // an intentional Stop()
        int code = process.ExitCode;
        _process = null;
        process.Dispose();
        ExitCode = code;
        State = ServerState.Exited;
        string detail = LastError();
        Problem = code switch
        {
            ExitBadArguments => "The server settings are invalid: " + (detail.Length > 0 ? detail : "check Settings."),
            ExitViGEm => detail.Length > 0 ? detail : "The ViGEmBus driver is missing or not working. Install it from the Setup checklist.",
            ExitNetwork => detail.Length > 0 ? detail : "The UDP port is not available. Choose another port in Settings.",
            0 => "The server stopped.",
            _ => $"The server stopped unexpectedly (exit code {code}). {detail}".Trim(),
        };
        // Settings, driver and port problems need the user; a crash gets a few automatic retries.
        if (_disposed || code is ExitBadArguments or ExitViGEm or ExitNetwork or 0) return;
        if (code == ExitFailure || code < 0 || code > ExitNetwork)
        {
            if (_crashes >= RestartDelays.Length) return;
            var delay = RestartDelays[_crashes++];
            Problem += $" Restarting in {delay.TotalSeconds:F0} s…";
            _restartTimer ??= new DispatcherTimer(DispatcherPriority.Normal, _dispatcher);
            _restartTimer.Interval = delay;
            _restartTimer.Tick -= OnRestartTick;
            _restartTimer.Tick += OnRestartTick;
            _restartTimer.Start();
        }
    }

    private void OnRestartTick(object? sender, EventArgs e)
    {
        _restartTimer!.Stop();
        Start();
    }

    /// <summary>The server's error output, without its "Error: " prefix (it explains the fix).</summary>
    private string LastError()
    {
        lock (_errorLines)
        {
            var lines = _errorLines.Where(l => l.Length > 0 && !l.StartsWith("Usage:", StringComparison.Ordinal)).Take(6).ToList();
            return string.Join(" ", lines).Replace("Error: ", "", StringComparison.Ordinal).Trim();
        }
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        Stop();
        _job.Dispose();
    }

    /// <summary>A job object with KILL_ON_JOB_CLOSE: when this process ends, Windows ends every process assigned to it.</summary>
    private sealed partial class JobObject : IDisposable
    {
        private const int JobObjectExtendedLimitInformation = 9;
        private const uint JobObjectLimitKillOnJobClose = 0x2000;

        private readonly nint _handle = CreateJobObjectW(0, null);

        public JobObject()
        {
            if (_handle == 0) return;
            var info = new ExtendedLimitInformation { BasicLimitInformation = new BasicLimitInformation { LimitFlags = JobObjectLimitKillOnJobClose } };
            SetInformationJobObject(_handle, JobObjectExtendedLimitInformation, ref info, (uint)Marshal.SizeOf<ExtendedLimitInformation>());
        }

        public void Assign(Process process)
        {
            if (_handle != 0) AssignProcessToJobObject(_handle, process.Handle);
        }

        public void Dispose()
        {
            if (_handle != 0) CloseHandle(_handle);
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct BasicLimitInformation
        {
            public long PerProcessUserTimeLimit;
            public long PerJobUserTimeLimit;
            public uint LimitFlags;
            public nuint MinimumWorkingSetSize;
            public nuint MaximumWorkingSetSize;
            public uint ActiveProcessLimit;
            public nuint Affinity;
            public uint PriorityClass;
            public uint SchedulingClass;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct IoCounters
        {
            public ulong ReadOperationCount, WriteOperationCount, OtherOperationCount, ReadTransferCount, WriteTransferCount, OtherTransferCount;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct ExtendedLimitInformation
        {
            public BasicLimitInformation BasicLimitInformation;
            public IoCounters IoInfo;
            public nuint ProcessMemoryLimit;
            public nuint JobMemoryLimit;
            public nuint PeakProcessMemoryUsed;
            public nuint PeakJobMemoryUsed;
        }

        [LibraryImport("kernel32.dll", StringMarshalling = StringMarshalling.Utf16)]
        private static partial nint CreateJobObjectW(nint attributes, string? name);

        [LibraryImport("kernel32.dll")]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static partial bool SetInformationJobObject(nint job, int infoClass, ref ExtendedLimitInformation info, uint length);

        [LibraryImport("kernel32.dll")]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static partial bool AssignProcessToJobObject(nint job, nint process);

        [LibraryImport("kernel32.dll")]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static partial bool CloseHandle(nint handle);
    }
}
