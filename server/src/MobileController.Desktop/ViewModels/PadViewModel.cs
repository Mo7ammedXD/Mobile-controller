using System.Collections.ObjectModel;
using System.Windows.Input;
using MobileController.Control;

namespace MobileController.Desktop.ViewModels;

/// <summary>One connected phone, refreshed from every snapshot (about four times a second).</summary>
public sealed class PadViewModel : ObservableObject
{
    /// <summary>Seconds of history the sparklines show (one point per snapshot).</summary>
    public const int HistoryPoints = 120;

    private PadInfo _info;
    private LivePad? _live;
    private string? _alias;

    public PadViewModel(PadInfo info, MainViewModel owner)
    {
        _info = info;
        Identify = new RelayCommand(() => owner.Send(ControlProtocol.OpIdentify, Index), () => SupportsStatus);
        Disconnect = new RelayCommand(() => owner.DisconnectPad(this));
        RumbleTest = new RelayCommand(() => owner.Send(ControlProtocol.OpRumbleTest, Index));
        SetOutput = new RelayCommand(p => owner.SetOutput(this, p as string ?? ""), _ => SupportsStatus);
        Rename = new RelayCommand(p => owner.RenamePad(this, p as string), _ => SupportsStatus);
    }

    public PadInfo Info => _info;

    public int Index => _info.Index;

    /// <summary>The same phone across snapshots: its deviceId, or its endpoint for apps without STATUS.</summary>
    public string Identity => _info.DeviceId ?? _info.EndPoint;

    public bool SupportsStatus => _info.DeviceId is not null;

    /// <summary>The name the user gave this phone in the desktop app, if any.</summary>
    public string? Alias
    {
        get => _alias;
        set
        {
            if (Set(ref _alias, value)) OnPropertyChanged(nameof(Title));
        }
    }

    public string Title => _alias ?? _info.DeviceName ?? $"Phone at {_info.EndPoint}";

    public string Subtitle => string.Join("  ·  ", new[]
    {
        $"Controller {_info.Index + 1}",
        _info.Player is int p ? $"Player {p}" : null,
        KindText,
        _info.Profile is { Length: > 0 } profile ? profile : null,
    }.Where(s => s is not null));

    public string KindText => _info.Kind == ControlProtocol.KindDualShock4 ? "DualShock 4" : "Xbox 360";

    public bool IsDualShock4 => _info.Kind == ControlProtocol.KindDualShock4;

    /// <summary>What the phone itself asked for: "none", "xbox360" or "ds4".</summary>
    public string Preference => _info.Preference;

    public string EndPoint => _info.EndPoint;

    public string BatteryText => _info.Battery is int b ? $"{b} %{(_info.Charging ? " ⚡" : "")}" : "—";

    public bool BatteryLow => _info.Battery is < 20 && !_info.Charging;

    public string LatencyText => _info.PhoneRttMs is double rtt ? $"{rtt:F1} ms" : "—";

    public string RateText => $"{_info.InputsPerSecond:F0}/s";

    public string LossText => $"{_info.LossPercent:F1} %";

    public string JitterText => $"{_info.JitterMs:F1} ms";

    public string ConnectedText => FormatDuration(_info.ConnectedSeconds);

    /// <summary>A short word for how the link is doing, from loss, latency and gaps.</summary>
    public string Quality => _info switch
    {
        { TimedOut: true } => "Idle",
        { LossPercent: > 5 } or { GapP99Ms: > 60 } or { PhoneRttMs: > 30 } => "Poor",
        { LossPercent: > 1 } or { GapP99Ms: > 30 } or { PhoneRttMs: > 15 } => "Fair",
        _ => "Good",
    };

    public string StateText => _info switch
    {
        { MenuOpen: true } => "In the phone's settings",
        { TimedOut: true } => "No input (phone paused or asleep)",
        { MotionActive: true } => "Playing · motion controls on",
        _ => "Playing",
    };

    public bool Rumbling => _info.LargeMotor > 0 || _info.SmallMotor > 0;

    public ObservableCollection<double> LatencyHistory { get; } = [];
    public ObservableCollection<double> RateHistory { get; } = [];

    /// <summary>The latest live report (only while the Controllers page is open).</summary>
    public LivePad? Live
    {
        get => _live;
        set => Set(ref _live, value);
    }

    public ICommand Identify { get; }
    public ICommand Disconnect { get; }
    public ICommand RumbleTest { get; }
    public ICommand SetOutput { get; }
    public ICommand Rename { get; }

    public void Update(PadInfo info)
    {
        _info = info;
        Add(LatencyHistory, info.PhoneRttMs ?? 0);
        Add(RateHistory, info.InputsPerSecond);
        // Every display property derives from Info.
        OnPropertyChanged(string.Empty);
    }

    private static void Add(ObservableCollection<double> history, double value)
    {
        history.Add(value);
        while (history.Count > HistoryPoints) history.RemoveAt(0);
    }

    internal static string FormatDuration(double seconds)
    {
        var t = TimeSpan.FromSeconds(seconds);
        return t.TotalHours >= 1 ? $"{(int)t.TotalHours} h {t.Minutes} min" : t.TotalMinutes >= 1 ? $"{t.Minutes} min {t.Seconds} s" : $"{t.Seconds} s";
    }
}

public enum Level
{
    Good,
    Info,
    Warning,
    Error,
    Busy,
}

/// <summary>One row of the setup checklist.</summary>
public sealed class CheckItem(string title) : ObservableObject
{
    private Level _level = Level.Busy;
    private string _detail = "Checking…";
    private string? _actionText;
    private ICommand? _action;

    public string Title { get; } = title;

    public Level Level
    {
        get => _level;
        set => Set(ref _level, value);
    }

    public string Detail
    {
        get => _detail;
        set => Set(ref _detail, value);
    }

    public string? ActionText
    {
        get => _actionText;
        set => Set(ref _actionText, value);
    }

    public ICommand? Action
    {
        get => _action;
        set => Set(ref _action, value);
    }

    public void Show(Level level, string detail, string? actionText = null, ICommand? action = null)
    {
        Level = level;
        Detail = detail;
        ActionText = actionText;
        Action = action;
    }
}

public sealed record LogItem(DateTime Time, string Level, string Message)
{
    public bool IsWarning => Level == "warning";
    public bool IsError => Level == "error";
}

/// <summary>One of the PC's network adapters, as the Connect page lists it.</summary>
public sealed record AdapterItem(string Address, string Name, string Description, AdapterKind Kind, string PairingUri)
{
    public string Hint => Kind switch
    {
        AdapterKind.UsbTethering => "USB tethering: the fastest, most stable link",
        AdapterKind.Virtual => "Virtual adapter: phones cannot reach it",
        AdapterKind.LinkLocal => "No network address assigned (link-local only)",
        _ => "Wi-Fi or Ethernet: the phone must be on the same network",
    };

    public bool Usable => Kind is AdapterKind.Lan or AdapterKind.UsbTethering;
}
