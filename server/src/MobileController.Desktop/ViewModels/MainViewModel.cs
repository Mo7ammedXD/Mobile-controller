using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Net.NetworkInformation;
using System.Text;
using System.Windows;
using System.Windows.Data;
using System.Windows.Input;
using System.Windows.Threading;
using MobileController.Control;
using MobileController.Desktop.Services;

namespace MobileController.Desktop.ViewModels;

/// <summary>
/// Everything the window shows: the server process and its control pipe, the phones, the setup checklist, the log, the
/// network adapters and the settings. Lives on the UI thread; the pipe's messages arrive there already.
/// </summary>
public sealed class MainViewModel : ObservableObject, IDisposable
{
    private const int MaxLogLines = 5000;

    private readonly Dispatcher _dispatcher;
    private readonly ControlClient _client;
    private readonly Dictionary<int, Action<ReplyMessage>> _pending = [];
    private readonly DispatcherTimer _tick;

    private HelloMessage? _hello;
    private SnapshotMessage? _snapshot;
    private DiagnosticsReport? _report;
    private bool _liveWanted, _checking, _disposed;
    private Level _health = Level.Busy;
    private string _healthTitle = "Starting the server…";
    private string _healthDetail = "";
    private string? _healthActionText;
    private ICommand? _healthAction;
    private string? _statusMessage;
    private string _logLevel = "All";
    private string _logSearch = "";
    private AdapterItem? _pairing;

    public MainViewModel(Dispatcher dispatcher)
    {
        _dispatcher = dispatcher;
        Desktop = DesktopSettings.Load();
        Server = new ServerSupervisor(dispatcher);
        _client = new ControlClient(dispatcher);
        Settings = new SettingsViewModel(this);

        Driver = new CheckItem("Controller driver (ViGEmBus)");
        Firewall = new CheckItem("Windows Firewall");
        Network = new CheckItem("Network connection");
        Phone = new CheckItem("Phone");
        Checklist = [Driver, Firewall, Network, Phone];

        LogsView = CollectionViewSource.GetDefaultView(Logs);
        LogsView.Filter = o => o is LogItem item && MatchesLogFilter(item);

        RestartServerCommand = new RelayCommand(RestartServer);
        RunChecksCommand = new RelayCommand(() => _ = RunChecksAsync(), () => !_checking);
        InstallDriverCommand = new RelayCommand(() => SystemChecks.Open(SystemChecks.ViGEmDownload));
        FixFirewallCommand = new RelayCommand(() => _ = FixFirewallAsync(), () => !_checking);
        OpenLogFolderCommand = new RelayCommand(OpenLogFolder);
        CopyLogCommand = new RelayCommand(CopyLog);
        ClearLogCommand = new RelayCommand(() => Logs.Clear());
        CopyTextCommand = new RelayCommand(p => CopyText(p as string));
        ExportDiagnosticsCommand = new RelayCommand(() => _ = ExportDiagnosticsAsync(), () => !_checking);
        TestPatternCommand = new RelayCommand(p => RunTestPattern(p as string));
        DismissSetupCommand = new RelayCommand(DismissSetup);

        _client.Hello += OnHello;
        _client.Snapshot += OnSnapshot;
        _client.Live += OnLive;
        _client.Log += m => AddLog(new LogItem(m.Time, m.Level, m.Message));
        _client.Reply += OnReply;
        _client.Disconnected += OnDisconnected;
        Server.Started += (_, _) => _client.Connect(Server.PipeName);
        Server.PropertyChanged += (_, _) => UpdateHealth();
        NetworkChange.NetworkAddressChanged += OnNetworkChanged;

        _tick = new DispatcherTimer(TimeSpan.FromSeconds(1), DispatcherPriority.Background, (_, _) => UpdateHealth(), dispatcher);
        RefreshAdapters();
    }

    public DesktopSettings Desktop { get; }
    public ServerSupervisor Server { get; }
    public SettingsViewModel Settings { get; }

    public ObservableCollection<PadViewModel> Pads { get; } = [];
    public ObservableCollection<LogItem> Logs { get; } = [];
    public ICollectionView LogsView { get; }
    public ObservableCollection<AdapterItem> Adapters { get; } = [];
    public IReadOnlyList<CheckItem> Checklist { get; }
    public CheckItem Driver { get; }
    public CheckItem Firewall { get; }
    public CheckItem Network { get; }
    public CheckItem Phone { get; }

    public ICommand RestartServerCommand { get; }
    public ICommand RunChecksCommand { get; }
    public ICommand InstallDriverCommand { get; }
    public ICommand FixFirewallCommand { get; }
    public ICommand OpenLogFolderCommand { get; }
    public ICommand CopyLogCommand { get; }
    public ICommand ClearLogCommand { get; }
    public ICommand CopyTextCommand { get; }
    public ICommand ExportDiagnosticsCommand { get; }
    public ICommand TestPatternCommand { get; }
    public ICommand DismissSetupCommand { get; }

    /// <summary>A phone connected or left: title and text for a notification.</summary>
    public event Action<string, string>? Notify;

    public HelloMessage? Hello { get => _hello; private set => Set(ref _hello, value); }
    public SnapshotMessage? Snapshot { get => _snapshot; private set => Set(ref _snapshot, value); }
    public DiagnosticsReport? Report { get => _report; private set => Set(ref _report, value); }

    public Level Health { get => _health; private set => Set(ref _health, value); }
    public string HealthTitle { get => _healthTitle; private set => Set(ref _healthTitle, value); }
    public string HealthDetail { get => _healthDetail; private set => Set(ref _healthDetail, value); }
    public string? HealthActionText { get => _healthActionText; private set => Set(ref _healthActionText, value); }
    public ICommand? HealthAction { get => _healthAction; private set => Set(ref _healthAction, value); }

    /// <summary>A short message about the last thing the user did (a command failed, a file was saved...).</summary>
    public string? StatusMessage { get => _statusMessage; set => Set(ref _statusMessage, value); }

    public bool ShowSetup => !Desktop.SetupDone || Checklist.Any(c => c.Level is Level.Error or Level.Warning);

    /// <summary>The address and pairing link the Connect page shows big (USB tethering first, then Wi-Fi / Ethernet).</summary>
    public AdapterItem? Pairing { get => _pairing; private set => Set(ref _pairing, value); }

    public string ServerName => Hello?.ServerName ?? Environment.MachineName;
    public int Port => Hello?.Port ?? 24680;
    public string UptimeText => Snapshot is { } s ? PadViewModel.FormatDuration(s.Uptime) : "—";
    public string ServerVersionText => Hello is { } h ? $"Server {h.ServerVersion} · protocol v{h.ProtocolVersion} · control v{h.ControlVersion}" : "";

    public IReadOnlyList<string> LogLevels { get; } = ["All", "Warnings and errors", "Errors"];

    public string LogLevel
    {
        get => _logLevel;
        set
        {
            if (Set(ref _logLevel, value)) LogsView.Refresh();
        }
    }

    public string LogSearch
    {
        get => _logSearch;
        set
        {
            if (Set(ref _logSearch, value)) LogsView.Refresh();
        }
    }

    /// <summary>The Controllers page is visible: ask the server for live input (and stop again when it is not).</summary>
    public bool LiveWanted
    {
        get => _liveWanted;
        set
        {
            if (!Set(ref _liveWanted, value)) return;
            Send(ControlProtocol.OpLive, value: value ? "on" : "off");
            if (!value)
            {
                foreach (var pad in Pads) pad.Live = null;
            }
        }
    }

    public void Start()
    {
        App.ApplyTheme(Desktop.Theme);
        Server.Start();
        _tick.Start();
        _ = RunChecksAsync();
    }

    public void RestartServer()
    {
        _client.Disconnect();
        Pads.Clear();
        Hello = null;
        Snapshot = null;
        Server.Restart();
    }

    // --- Pipe events ---------------------------------------------------------------------------------------------

    private void OnHello(HelloMessage hello)
    {
        Hello = hello;
        Server.MarkRunning();
        if (_liveWanted) Send(ControlProtocol.OpLive, value: "on");
        RefreshAdapters();
        OnPropertyChanged(nameof(ServerName));
        OnPropertyChanged(nameof(Port));
        OnPropertyChanged(nameof(ServerVersionText));
        UpdateHealth();
    }

    private void OnSnapshot(SnapshotMessage snapshot)
    {
        Snapshot = snapshot;
        var present = new HashSet<PadViewModel>();
        foreach (var info in snapshot.Pads)
        {
            string identity = info.DeviceId ?? info.EndPoint;
            var pad = Pads.FirstOrDefault(p => p.Identity == identity);
            if (pad is null)
            {
                pad = new PadViewModel(info, this);
                Pads.Add(pad);
                pad.Alias = AliasOf(info);
                if (Desktop.Notifications) Notify?.Invoke("Phone connected", $"{pad.Title} is controller {info.Index + 1} ({pad.KindText}).");
            }
            pad.Update(info);
            present.Add(pad);
        }
        foreach (var gone in Pads.Where(p => !present.Contains(p)).ToList())
        {
            Pads.Remove(gone);
            if (Desktop.Notifications) Notify?.Invoke("Phone disconnected", $"{gone.Title} left controller {gone.Index + 1}.");
        }
        OnPropertyChanged(nameof(UptimeText));
        UpdatePhoneCheck();
        UpdateHealth();
    }

    private void OnLive(LiveMessage live)
    {
        foreach (var pad in live.Pads)
        {
            var vm = Pads.FirstOrDefault(p => p.Index == pad.Index);
            if (vm is not null) vm.Live = pad;
        }
    }

    private void OnReply(ReplyMessage reply)
    {
        if (_pending.Remove(reply.Id, out var callback)) callback(reply);
        if (!reply.Ok) StatusMessage = $"The server could not do that: {reply.Error}";
    }

    private void OnDisconnected()
    {
        Hello = null;
        Pads.Clear();
        UpdateHealth();
    }

    private void AddLog(LogItem item)
    {
        Logs.Add(item);
        if (Logs.Count > MaxLogLines) Logs.RemoveAt(0);
    }

    private bool MatchesLogFilter(LogItem item)
    {
        if (_logLevel == "Errors" && !item.IsError) return false;
        if (_logLevel == "Warnings and errors" && !(item.IsError || item.IsWarning)) return false;
        return _logSearch.Length == 0 || item.Message.Contains(_logSearch, StringComparison.OrdinalIgnoreCase);
    }

    // --- Commands to the server ----------------------------------------------------------------------------------

    public void Send(string op, int? index = null, string? value = null, Action<ReplyMessage>? onReply = null)
    {
        int id = _client.Send(op, index, value);
        if (id < 0)
        {
            if (op != ControlProtocol.OpLive) StatusMessage = "Not connected to the server yet.";
            return;
        }
        if (onReply is not null) _pending[id] = onReply;
    }

    public void DisconnectPad(PadViewModel pad) =>
        Send(ControlProtocol.OpDisconnect, pad.Index, onReply: r =>
        {
            if (r.Ok) StatusMessage = $"{pad.Title} was disconnected. It can reconnect in 30 seconds.";
        });

    /// <summary>"xbox360", "ds4" or "" (the phone's own choice): remembered for this phone and applied at once.</summary>
    public void SetOutput(PadViewModel pad, string kind)
    {
        if (pad.Info.DeviceId is { } id) Settings.SetDevice(id, output: kind);
        Send(ControlProtocol.OpOutput, pad.Index, kind, r =>
        {
            if (r.Ok) StatusMessage = kind.Length == 0
                ? $"{pad.Title} now chooses its own controller type."
                : $"{pad.Title} is now a{(kind == ControlProtocol.KindDualShock4 ? " DualShock 4" : "n Xbox 360 controller")}. Games see it unplugged and plugged in again.";
        });
    }

    public void RenamePad(PadViewModel pad, string? alias)
    {
        if (pad.Info.DeviceId is not { } id) return;
        Settings.SetDevice(id, alias: alias ?? "");
        pad.Alias = string.IsNullOrWhiteSpace(alias) ? null : alias.Trim();
    }

    private string? AliasOf(PadInfo info) => info.DeviceId is { } id ? Settings.Device(id)?.Alias : null;

    // --- Checks --------------------------------------------------------------------------------------------------

    public async Task RunChecksAsync()
    {
        if (_checking) return;
        _checking = true;
        foreach (var item in new[] { Driver, Firewall }) item.Show(Level.Busy, "Checking…");
        try
        {
            var reportTask = SystemChecks.DiagnoseAsync(Server.ServerPath);
            var firewallTask = SystemChecks.FirewallRuleExistsAsync();
            Report = await reportTask;
            bool? firewall = await firewallTask;

            if (Report is null)
                Driver.Show(Level.Warning, "Could not ask the server to check the driver.", "Check again", RunChecksCommand);
            else if (Report.Driver.Ok)
                Driver.Show(Level.Good, "Installed and working.");
            else
                Driver.Show(Level.Error, Report.Driver.Problem ?? "Not installed.", "Download ViGEmBus", InstallDriverCommand);

            if (firewall == true)
                Firewall.Show(Level.Good, "Phones on your network are allowed to connect.");
            else if (firewall == false)
                Firewall.Show(Level.Warning, "No rule for Mobile Controller yet: Windows may block phones (it usually asks once).",
                    "Allow phones (administrator)", FixFirewallCommand);
            else
                Firewall.Show(Level.Info, "Could not read the firewall rules.", "Check again", RunChecksCommand);
        }
        finally
        {
            _checking = false;
            CommandManager.InvalidateRequerySuggested();
            UpdateNetworkCheck();
            UpdatePhoneCheck();
            UpdateHealth();
            OnPropertyChanged(nameof(ShowSetup));
        }
    }

    private async Task FixFirewallAsync()
    {
        StatusMessage = "Windows will ask for permission to add the firewall rule…";
        bool ok = await SystemChecks.FixFirewallAsync(Port, Server.ServerPath);
        StatusMessage = ok ? "Firewall rule added." : "The firewall rule was not added (permission declined, or the script failed).";
        await RunChecksAsync();
    }

    private void OnNetworkChanged(object? sender, EventArgs e) => _dispatcher.BeginInvoke(() =>
    {
        RefreshAdapters();
        UpdateNetworkCheck();
        UpdateHealth();
    });

    private void RefreshAdapters()
    {
        Adapters.Clear();
        string name = Uri.EscapeDataString(ServerName);
        foreach (var a in NetworkHints.LocalIPv4Addresses()
                     .OrderBy(a => a.Kind switch { AdapterKind.UsbTethering => 0, AdapterKind.Lan => 1, _ => 2 }))
        {
            Adapters.Add(new AdapterItem(a.Address.ToString(), a.Name, a.Description, a.Kind,
                $"mobilecontroller://connect?host={a.Address}&port={Port}&name={name}"));
        }
        Pairing = Adapters.FirstOrDefault(a => a.Usable);
    }

    private void UpdateNetworkCheck()
    {
        var usable = Adapters.Where(a => a.Usable).ToList();
        if (usable.Count == 0)
            Network.Show(Level.Error, "No network a phone can reach. Join the phone's Wi-Fi, or connect the phone by USB and turn on USB tethering.");
        else if (usable.Any(a => a.Kind == AdapterKind.UsbTethering))
            Network.Show(Level.Good, $"USB tethering at {usable.First(a => a.Kind == AdapterKind.UsbTethering).Address} (the fastest link).");
        else
            Network.Show(Level.Good, $"Phones on the same network can use {string.Join(", ", usable.Select(a => a.Address))}.");
    }

    private void UpdatePhoneCheck()
    {
        if (Pads.Count > 0)
            Phone.Show(Level.Good, Pads.Count == 1 ? $"{Pads[0].Title} is connected." : $"{Pads.Count} phones are connected.");
        else
            Phone.Show(Level.Info, "Open Mobile Controller on the phone and tap Find PC, or scan the code on the Connect page.");
        OnPropertyChanged(nameof(ShowSetup));
    }

    private void DismissSetup()
    {
        Desktop.SetupDone = true;
        try
        {
            Desktop.Save();
        }
        catch (IOException)
        {
        }
        OnPropertyChanged(nameof(ShowSetup));
    }

    /// <summary>The banner at the top: the one thing most worth knowing right now, and what to do about it.</summary>
    private void UpdateHealth()
    {
        if (_disposed) return;
        OnPropertyChanged(nameof(UptimeText));
        if (Server.State == ServerState.Exited)
            ShowHealth(Level.Error, "The server is not running", Server.Problem ?? "", "Start again", RestartServerCommand);
        else if (Server.State == ServerState.Starting || Hello is null)
            ShowHealth(Level.Busy, "Starting the server…", "This takes a moment.");
        else if (Driver.Level == Level.Error)
            ShowHealth(Level.Error, "The controller driver is missing", "Games cannot see the phones until ViGEmBus is installed.", "Download ViGEmBus", InstallDriverCommand);
        else if (Network.Level == Level.Error)
            ShowHealth(Level.Error, "No network for phones", Network.Detail);
        else if (Pads.Count == 0)
            ShowHealth(Level.Info, "Ready: waiting for a phone",
                $"Open Mobile Controller on the phone and tap Find PC, or enter {Pairing?.Address ?? "this PC's address"} and port {Port}." +
                (Firewall.Level == Level.Warning ? " If it does not connect, allow it through Windows Firewall." : ""),
                Firewall.Level == Level.Warning ? "Allow phones" : null, Firewall.Level == Level.Warning ? FixFirewallCommand : null);
        else
            ShowHealth(Pads.Any(p => p.Quality == "Poor") ? Level.Warning : Level.Good,
                Pads.Count == 1 ? "1 controller connected" : $"{Pads.Count} controllers connected",
                string.Join("   ", Pads.Select(p => $"{p.Title}: {p.Quality.ToLowerInvariant()} link, {p.LatencyText}")));
    }

    private void ShowHealth(Level level, string title, string detail, string? actionText = null, ICommand? action = null)
    {
        Health = level;
        HealthTitle = title;
        HealthDetail = detail;
        HealthActionText = actionText;
        HealthAction = action;
    }

    // --- Tools ---------------------------------------------------------------------------------------------------

    private string LogFolder => Path.GetDirectoryName(Hello?.LogFile ?? "") is { Length: > 0 } dir
        ? dir
        : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "MobileController", "logs");

    private void OpenLogFolder()
    {
        Directory.CreateDirectory(LogFolder);
        SystemChecks.Open(LogFolder);
    }

    private void CopyLog()
    {
        var text = new StringBuilder();
        foreach (LogItem item in LogsView) text.Append(item.Time.ToString("HH:mm:ss.fff")).Append(' ').Append(item.Message).AppendLine();
        CopyText(text.ToString());
    }

    private void CopyText(string? text)
    {
        if (string.IsNullOrEmpty(text)) return;
        try
        {
            Clipboard.SetText(text);
            StatusMessage = "Copied.";
        }
        catch (Exception e) when (e is System.Runtime.InteropServices.COMException or InvalidOperationException)
        {
            StatusMessage = "The clipboard is busy; try again.";
        }
    }

    /// <summary>Plugs one extra controller for 10 s and animates it (no phone needed), to check the driver in a game or tester.</summary>
    private void RunTestPattern(string? kind)
    {
        if (!File.Exists(Server.ServerPath)) return;
        var info = new ProcessStartInfo(Server.ServerPath) { UseShellExecute = false, CreateNoWindow = true };
        info.ArgumentList.Add("--test-pattern");
        info.ArgumentList.Add("10");
        info.ArgumentList.Add("--default-output");
        info.ArgumentList.Add(kind == ControlProtocol.KindDualShock4 ? kind : ControlProtocol.KindXbox360);
        info.ArgumentList.Add("--no-config");
        try
        {
            Process.Start(info)?.Dispose();
            StatusMessage = "A test controller is plugged in for 10 seconds: its sticks circle and its buttons cycle. Watch it in a game or in joy.cpl.";
        }
        catch (Win32Exception e)
        {
            StatusMessage = $"The test pattern could not start: {e.Message}";
        }
    }

    /// <summary>A zip on the desktop with what a bug report needs: logs, settings, a fresh check and the last snapshot.</summary>
    private async Task ExportDiagnosticsAsync()
    {
        string file = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory),
            $"MobileController-diagnostics-{DateTime.Now:yyyyMMdd-HHmmss}.zip");
        StatusMessage = "Collecting diagnostics…";
        var report = await SystemChecks.DiagnoseAsync(Server.ServerPath);
        try
        {
            using var zip = ZipFile.Open(file, ZipArchiveMode.Create);
            void AddText(string name, string text)
            {
                using var writer = new StreamWriter(zip.CreateEntry(name).Open());
                writer.Write(text);
            }
            if (Directory.Exists(LogFolder))
            {
                foreach (string log in Directory.EnumerateFiles(LogFolder, "*.log"))
                {
                    // The server keeps writing: open shared rather than through CreateEntryFromFile.
                    using var source = new FileStream(log, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
                    using var target = zip.CreateEntry("logs/" + Path.GetFileName(log)).Open();
                    await source.CopyToAsync(target);
                }
            }
            if (File.Exists(ServerSettings.DefaultPath)) zip.CreateEntryFromFile(ServerSettings.DefaultPath, "server.json");
            if (File.Exists(DesktopSettings.Path)) zip.CreateEntryFromFile(DesktopSettings.Path, "desktop.json");
            if (report is not null) AddText("diagnose.json", DiagnosticsJson.Serialize(report));
            if (Snapshot is not null) AddText("snapshot.json", ControlProtocol.Serialize(Snapshot));
            if (Hello is not null) AddText("hello.json", ControlProtocol.Serialize(Hello));
            AddText("app.txt", $"{Environment.OSVersion}\n{System.Runtime.InteropServices.RuntimeInformation.FrameworkDescription}\n" +
                               $"Server state: {Server.State}\n{Server.Problem}\n\nServer error output:\n{Server.ErrorOutput}\n");
            StatusMessage = $"Saved {Path.GetFileName(file)} on the desktop.";
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            StatusMessage = $"Could not save the diagnostics: {e.Message}";
        }
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        NetworkChange.NetworkAddressChanged -= OnNetworkChanged;
        _tick.Stop();
        _client.Dispose();
        Server.Dispose();
    }
}
