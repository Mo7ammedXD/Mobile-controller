using System.IO;
using System.Net;
using System.Text.Json;
using System.Windows.Input;
using MobileController.Control;
using MobileController.Desktop.Services;

namespace MobileController.Desktop.ViewModels;

/// <summary>
/// The Settings page: the server's settings file (<see cref="ServerSettings"/>) plus this app's own preferences. The form
/// edits copies; Save validates, writes the file and restarts the server when a server setting changed.
/// </summary>
public sealed class SettingsViewModel : ObservableObject
{
    private readonly MainViewModel _owner;
    private ServerSettings _file = new();
    private string? _error;
    private string? _saved;

    // Server settings, as the form shows them.
    private string _port = "24680", _bind = "", _maxPads = "4", _neutralTimeout = "500", _disconnectTimeout = "10000", _allow = "", _name = "";
    private bool _gameDetect = true, _verbose, _highPriority;
    private string _defaultOutput = ControlProtocol.KindXbox360;

    // This app.
    private bool _startWithWindows, _startMinimized, _closeToTray, _notifications;
    private AppTheme _theme;

    public SettingsViewModel(MainViewModel owner)
    {
        _owner = owner;
        Save = new RelayCommand(DoSave);
        Revert = new RelayCommand(Load);
        OpenFile = new RelayCommand(() => SystemChecks.Open(ServerSettings.DefaultPath), () => System.IO.File.Exists(ServerSettings.DefaultPath));
        Load();
    }

    public ICommand Save { get; }
    public ICommand Revert { get; }
    public ICommand OpenFile { get; }

    public string? Error { get => _error; private set => Set(ref _error, value); }
    public string? Saved { get => _saved; private set => Set(ref _saved, value); }

    public string Port { get => _port; set => Set(ref _port, value); }

    /// <summary>Empty: listen on every adapter.</summary>
    public string Bind { get => _bind; set => Set(ref _bind, value); }
    public string MaxPads { get => _maxPads; set => Set(ref _maxPads, value); }
    public string NeutralTimeout { get => _neutralTimeout; set => Set(ref _neutralTimeout, value); }
    public string DisconnectTimeout { get => _disconnectTimeout; set => Set(ref _disconnectTimeout, value); }

    /// <summary>Phone addresses allowed to connect, one per line or separated by commas; empty: any phone.</summary>
    public string Allow { get => _allow; set => Set(ref _allow, value); }

    /// <summary>Empty: the computer name.</summary>
    public string Name { get => _name; set => Set(ref _name, value); }
    public bool GameDetect { get => _gameDetect; set => Set(ref _gameDetect, value); }
    public string DefaultOutput { get => _defaultOutput; set => Set(ref _defaultOutput, value); }
    public bool Verbose { get => _verbose; set => Set(ref _verbose, value); }
    public bool HighPriority { get => _highPriority; set => Set(ref _highPriority, value); }

    public bool StartWithWindows { get => _startWithWindows; set => Set(ref _startWithWindows, value); }
    public bool StartMinimized { get => _startMinimized; set => Set(ref _startMinimized, value); }
    public bool CloseToTray { get => _closeToTray; set => Set(ref _closeToTray, value); }
    public bool Notifications { get => _notifications; set => Set(ref _notifications, value); }
    public AppTheme Theme { get => _theme; set => Set(ref _theme, value); }

    public IReadOnlyList<string> OutputChoices { get; } = [ControlProtocol.KindXbox360, ControlProtocol.KindDualShock4];
    public IReadOnlyList<AppTheme> ThemeChoices { get; } = [AppTheme.System, AppTheme.Light, AppTheme.Dark];

    /// <summary>The settings file as last loaded or saved (the per-phone list included).</summary>
    public ServerSettings SettingsFile => _file;

    public void Load()
    {
        _file = ReadFile(out string? problem);
        Error = problem;
        Saved = null;
        Port = (_file.Port ?? 24680).ToString();
        Bind = _file.Bind ?? "";
        MaxPads = (_file.MaxPads ?? 4).ToString();
        NeutralTimeout = (_file.NeutralTimeoutMs ?? 500).ToString();
        DisconnectTimeout = (_file.DisconnectTimeoutMs ?? 10_000).ToString();
        Allow = string.Join(Environment.NewLine, _file.Allow ?? []);
        Name = _file.Name ?? "";
        GameDetect = _file.GameDetect ?? true;
        DefaultOutput = _file.DefaultOutput ?? ControlProtocol.KindXbox360;
        Verbose = _file.Verbose ?? false;
        HighPriority = _file.HighPriority ?? false;

        var desktop = _owner.Desktop;
        StartWithWindows = DesktopSettings.StartWithWindows;
        StartMinimized = desktop.StartMinimized;
        CloseToTray = desktop.CloseToTray;
        Notifications = desktop.Notifications;
        Theme = desktop.Theme;
    }

    private static ServerSettings ReadFile(out string? problem)
    {
        problem = null;
        try
        {
            if (!System.IO.File.Exists(ServerSettings.DefaultPath)) return new ServerSettings();
            using var stream = System.IO.File.OpenRead(ServerSettings.DefaultPath);
            return ServerSettings.Read(stream) ?? new ServerSettings();
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or JsonException)
        {
            problem = $"The settings file could not be read ({e.Message}); saving will replace it.";
            return new ServerSettings();
        }
    }

    private void DoSave()
    {
        Saved = null;
        if (!TryBuild(out var next, out string? error))
        {
            Error = error;
            return;
        }
        Error = null;
        bool serverChanged = ServerSettings.ToJson(next) != ServerSettings.ToJson(_file);
        if (serverChanged && !TryWrite(next)) return;
        _file = next;

        var desktop = _owner.Desktop;
        desktop.StartMinimized = StartMinimized;
        desktop.CloseToTray = CloseToTray;
        desktop.Notifications = Notifications;
        desktop.Theme = Theme;
        try
        {
            desktop.Save();
            DesktopSettings.StartWithWindows = StartWithWindows;
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            Error = $"Could not save the app preferences: {e.Message}";
            return;
        }
        App.ApplyTheme(Theme);

        if (serverChanged)
        {
            Saved = "Saved. The server is restarting with the new settings (phones reconnect by themselves).";
            _owner.RestartServer();
        }
        else
        {
            Saved = "Saved.";
        }
    }

    private bool TryWrite(ServerSettings settings)
    {
        try
        {
            ServerSettings.Save(ServerSettings.DefaultPath, settings);
            return true;
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            Error = $"Could not save {ServerSettings.DefaultPath}: {e.Message}";
            return false;
        }
    }

    /// <summary>Validates the form with the server's own limits; on success a copy of the file with the form applied.</summary>
    internal bool TryBuild(out ServerSettings settings, out string? error)
    {
        // Start from the file, so per-phone entries and keys a newer version wrote are kept.
        settings = _file.Clone();
        if ((error = Number(Port, "Port", 1, 65535, out int port)) is not null) return false;
        if ((error = Number(MaxPads, "Maximum controllers", 1, 8, out int pads)) is not null) return false;
        if ((error = Number(NeutralTimeout, "Release inputs after", 1, 600_000, out int neutral)) is not null) return false;
        if ((error = Number(DisconnectTimeout, "Unplug after", 1, 3_600_000, out int disconnect)) is not null) return false;
        if (Bind.Trim().Length > 0 && !IsIPv4(Bind.Trim()))
        {
            error = $"Listen address must be an IPv4 address such as 192.168.1.20 (or empty for all), not '{Bind}'.";
            return false;
        }
        var allow = Allow.Split([',', ';', '\n', '\r', ' '], StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries).ToList();
        if (allow.FirstOrDefault(a => !IsIPv4(a)) is { } badAddress)
        {
            error = $"Allowed phones must be IPv4 addresses, not '{badAddress}'.";
            return false;
        }
        string name = Name.Trim();
        if (name.Length > 64 || name.Any(char.IsControl))
        {
            error = "The PC name may have at most 64 printable characters.";
            return false;
        }

        settings.Schema = ServerSettings.CurrentSchema;
        settings.Port = port == 24680 ? null : port;
        settings.Bind = Bind.Trim().Length == 0 ? null : Bind.Trim();
        settings.MaxPads = pads == 4 ? null : pads;
        settings.NeutralTimeoutMs = neutral == 500 ? null : neutral;
        settings.DisconnectTimeoutMs = disconnect == 10_000 ? null : disconnect;
        settings.Allow = allow.Count == 0 ? null : allow;
        settings.Name = name.Length == 0 ? null : name;
        settings.GameDetect = GameDetect ? null : false;
        settings.DefaultOutput = DefaultOutput == ControlProtocol.KindXbox360 ? null : DefaultOutput;
        settings.Verbose = Verbose ? true : null;
        settings.HighPriority = HighPriority ? true : null;
        return true;
    }

    private static string? Number(string text, string label, int min, int max, out int value) =>
        int.TryParse(text.Trim(), out value) && value >= min && value <= max ? null : $"{label} must be a whole number from {min} to {max}.";

    private static bool IsIPv4(string text) =>
        IPAddress.TryParse(text, out var address) && address.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork && text.Count(c => c == '.') == 3;

    // --- Per-phone settings (from the Controllers page), saved at once ------------------------------------------

    public DeviceSettings? Device(string deviceId) =>
        _file.Devices?.FirstOrDefault(d => string.Equals(d.Id, deviceId, StringComparison.OrdinalIgnoreCase));

    /// <summary>Stores a phone's name and/or pad kind (null alias / output = unchanged; "" = cleared). Takes effect at once.</summary>
    public void SetDevice(string deviceId, string? alias = null, string? output = null)
    {
        var next = _file.Clone();
        next.Devices ??= [];
        var device = next.Devices.FirstOrDefault(d => string.Equals(d.Id, deviceId, StringComparison.OrdinalIgnoreCase));
        if (device is null)
        {
            device = new DeviceSettings { Id = deviceId };
            next.Devices.Add(device);
        }
        if (alias is not null) device.Alias = alias.Trim().Length == 0 ? null : alias.Trim();
        if (output is not null) device.Output = output.Length == 0 ? null : output;
        if (device.Alias is null && device.Output is null && device.Unknown is null) next.Devices.Remove(device);
        if (next.Devices.Count == 0) next.Devices = null;
        if (TryWrite(next)) _file = next;
    }
}
