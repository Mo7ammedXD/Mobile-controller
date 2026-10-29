using System.Diagnostics.CodeAnalysis;
using System.Globalization;
using System.Net;
using System.Net.Sockets;

namespace MobileController.Server;

internal sealed record Options
{
    public const int DefaultMaxPads = 4;
    public const int MaxMaxPads = 8;
    public const int DefaultTestPatternSeconds = 30;

    /// <summary>0 picks an ephemeral port (tests only; the command line requires 1..65535).</summary>
    public int Port { get; init; } = Protocol.DefaultPort;
    public IPAddress BindAddress { get; init; } = IPAddress.Any;
    public int MaxPads { get; init; } = DefaultMaxPads;
    public int NeutralTimeoutMs { get; init; } = Protocol.DefaultNeutralTimeoutMs;
    public int DisconnectTimeoutMs { get; init; } = Protocol.DefaultDisconnectTimeoutMs;
    public IReadOnlyList<IPAddress> Allow { get; init; } = [];
    public bool DryRun { get; init; }
    public bool NoGameDetect { get; init; }
    public int? TestPatternSeconds { get; init; }
    public bool Verbose { get; init; }
    public bool HighPriority { get; init; }
    public bool Help { get; init; }

    /// <summary>--config: a settings file that must exist; null = the default file, if there is one.</summary>
    public string? ConfigPath { get; init; }

    /// <summary>--no-config: ignore the default settings file.</summary>
    public bool NoConfig { get; init; }

    /// <summary>Write the log to this file too (rotated); null = console only.</summary>
    public string? LogFile { get; init; }

    /// <summary>--diagnose: check driver, port, adapters and power settings, print a report and exit.</summary>
    public bool Diagnose { get; init; }

    /// <summary>--json: machine-readable --diagnose output (for the desktop app).</summary>
    public bool Json { get; init; }

    /// <summary>The pad a phone gets unless a device rule or the phone's own STATUS preference says otherwise (§4).</summary>
    public PadKind DefaultOutput { get; init; } = PadKind.Xbox360;

    /// <summary>Per-phone settings from the settings file, by the deviceId of STATUS (§12).</summary>
    public IReadOnlyDictionary<ulong, DeviceRule> Devices { get; init; } = new Dictionary<ulong, DeviceRule>();

    // Not on the command line: fixed by the protocol (or internal back-off), overridable so tests run fast.
    public int FeedbackIntervalMs { get; init; } = Protocol.FeedbackIntervalMs;
    public int ServerFullIntervalMs { get; init; } = Protocol.ServerFullIntervalMs;
    public int GameIntervalMs { get; init; } = Protocol.GameIntervalMs;
    public int GamePollMs { get; init; } = Protocol.GamePollMs;
    public int StatsIntervalMs { get; init; } = 5_000;
    public int ConnectRetryMs { get; init; } = 500;

    /// <summary>§4: how long a new endpoint's first INPUTs wait for its STATUS before a pad is plugged anyway.</summary>
    public int StatusWaitMs { get; init; } = 100;

    /// <summary>Window of the per-phone timing and loss metrics.</summary>
    public int MetricsIntervalMs { get; init; } = 1_000;

    /// <summary>--control-pipe: serve the desktop app on this pipe name (null = no control pipe).</summary>
    public string? ControlPipe { get; init; }

    /// <summary>--exit-with-pipe: stop when the desktop app that connected to the control pipe goes away.</summary>
    public bool ExitWithPipe { get; init; }

    /// <summary>How often status snapshots and live input go to the desktop app.</summary>
    public int PublishIntervalMs { get; init; } = 250;
    public int LiveIntervalMs { get; init; } = 33;

    /// <summary>Shown to phones by discovery (§8); --name or the settings file, else the computer name.</summary>
    public string ServerName { get; init; } = Environment.MachineName;

    /// <summary>A server name is cut to 32 UTF-8 bytes on the wire; this bounds what is accepted at all.</summary>
    public const int MaxServerNameChars = 64;

    public const string Usage = """
        Mobile Controller server - turns phones into virtual Xbox 360 controllers (ViGEmBus).

        Usage: MobileController.Server [options]

        Options:
          --port <n>                 UDP port to listen on (default 24680)
          --bind <ipv4>              Listen on this local IPv4 address only (default: all addresses)
          --max-pads <1-8>           Maximum simultaneous controllers (default 4; XInput games see only 4)
          --neutral-timeout <ms>     Release all inputs after this long without input (default 500)
          --disconnect-timeout <ms>  Unplug a controller after this long without any packet (default 10000)
          --allow <ipv4>             Accept packets only from this phone IP; repeatable (default: any)
          --name <text>              Name phones see when they search for PCs (default: the computer name)
          --default-output <type>    Controller a phone appears as: xbox360 (default) or ds4 (DualShock 4); a phone
                                     can ask for its own, and the settings file can set one per phone
          --no-game-detect           Do not tell phones the foreground app (no automatic game modes)
          --dry-run                  No virtual controllers: track phones and log decoded packets
          --test-pattern [seconds]   No network: plug in one controller and animate it (default 30 s)
          --verbose                  Print per-phone statistics every 5 s
          --high-priority            Run at High process priority (default AboveNormal)
          --log-file [path]          Also write the log to a file, rotated at 2 MB (default path:
                                     %LOCALAPPDATA%\MobileController\logs\server.log)
          --config <path>            Read settings from this JSON file (default: %APPDATA%\MobileController\
                                     server.json if it exists); options on the command line win over it
          --no-config                Ignore the default settings file
          --diagnose [--json]        Check the driver, the port, network adapters and power settings, then exit
          --control-pipe [name]      Serve the desktop app on a local named pipe (default name MobileController)
          --exit-with-pipe           Stop when the desktop app that connected to the control pipe goes away
          -h, --help                 Show this help

        Examples:
          MobileController.Server
          MobileController.Server --test-pattern 10
          MobileController.Server --port 24681 --allow 192.168.1.50 --verbose
          MobileController.Server --diagnose
        """;

    public static bool TryParse(string[] args, [NotNullWhen(true)] out Options? options, [NotNullWhen(false)] out string? error) =>
        TryParse(args, new Options(), out options, out error);

    /// <summary>
    /// Parses <paramref name="args"/> on top of <paramref name="baseline"/> (e.g. the settings file): every option given
    /// replaces the baseline's value, and any --allow replaces the baseline's whole allow list.
    /// </summary>
    public static bool TryParse(string[] args, Options baseline, [NotNullWhen(true)] out Options? options, [NotNullWhen(false)] out string? error)
    {
        try
        {
            options = Parse(args, baseline);
            error = null;
            return true;
        }
        catch (FormatException e)
        {
            options = null;
            error = e.Message;
            return false;
        }
    }

    private static Options Parse(string[] args, Options baseline)
    {
        var result = baseline;
        var allow = new List<IPAddress>();

        for (int i = 0; i < args.Length; i++)
        {
            string name = args[i];
            string? inlineValue = null;
            int eq = name.IndexOf('=');
            if (name.StartsWith("--", StringComparison.Ordinal) && eq > 2)
            {
                inlineValue = name[(eq + 1)..];
                name = name[..eq];
            }

            result = name switch
            {
                "-h" or "--help" or "/?" => result with { Help = Flag() },
                "--port" => result with { Port = Int(1, 65535) },
                "--bind" => result with { BindAddress = IPv4() },
                "--max-pads" => result with { MaxPads = Int(1, MaxMaxPads) },
                "--neutral-timeout" => result with { NeutralTimeoutMs = Int(1, 600_000) },
                "--disconnect-timeout" => result with { DisconnectTimeoutMs = Int(1, 3_600_000) },
                "--allow" => AddAllowed(IPv4()),
                "--dry-run" => result with { DryRun = Flag() },
                "--no-game-detect" => result with { NoGameDetect = Flag() },
                // The value is optional: only a following token that is not another option is taken.
                "--test-pattern" => result with
                {
                    TestPatternSeconds = inlineValue is not null || (i + 1 < args.Length && !args[i + 1].StartsWith('-'))
                        ? Int(1, 86_400)
                        : DefaultTestPatternSeconds,
                },
                "--verbose" => result with { Verbose = Flag() },
                "--high-priority" => result with { HighPriority = Flag() },
                "--name" => result with { ServerName = Name(Value()) },
                "--default-output" => result with
                {
                    DefaultOutput = TryParseKind(Value(), out var kind)
                        ? kind
                        : throw new FormatException($"{name} needs xbox360 or ds4, got '{args[i]}'"),
                },
                // Like --test-pattern, the path is optional: only a following token that is not an option is taken.
                "--log-file" => result with
                {
                    LogFile = inlineValue is not null || (i + 1 < args.Length && !args[i + 1].StartsWith('-'))
                        ? Value()
                        : DefaultLogFile,
                },
                "--config" => result with { ConfigPath = Value() },
                "--no-config" => result with { NoConfig = Flag() },
                "--diagnose" => result with { Diagnose = Flag() },
                "--control-pipe" => result with
                {
                    ControlPipe = inlineValue is not null || (i + 1 < args.Length && !args[i + 1].StartsWith('-'))
                        ? PipeName(Value())
                        : MobileController.Control.ControlProtocol.DefaultPipeName,
                },
                "--exit-with-pipe" => result with { ExitWithPipe = Flag() },
                "--json" => result with { Json = Flag() },
                _ => throw new FormatException($"unknown option '{args[i]}'"),
            };

            Options AddAllowed(IPAddress address)
            {
                allow.Add(address);
                return result;
            }

            bool Flag() => inlineValue is null ? true : throw new FormatException($"{name} does not take a value");

            string Value() => inlineValue ?? (i + 1 < args.Length ? args[++i] : throw new FormatException($"{name} needs a value"));

            int Int(int min, int max)
            {
                string text = Value();
                return int.TryParse(text, NumberStyles.None, CultureInfo.InvariantCulture, out int value) && value >= min && value <= max
                    ? value
                    : throw new FormatException($"{name} needs a whole number between {min} and {max}, got '{text}'");
            }

            IPAddress IPv4()
            {
                string text = Value();
                return TryParseIPv4(text, out var address)
                    ? address
                    : throw new FormatException($"{name} needs an IPv4 address such as 192.168.1.50, got '{text}'");
            }
        }

        if (result.DryRun && result.TestPatternSeconds is not null)
            throw new FormatException("--dry-run and --test-pattern cannot be combined (the test pattern needs ViGEmBus)");
        if (result.ConfigPath is not null && result.NoConfig)
            throw new FormatException("--config and --no-config cannot be combined");
        if (result.Json && !result.Diagnose)
            throw new FormatException("--json only applies to --diagnose");
        if (result.ExitWithPipe && result.ControlPipe is null)
            throw new FormatException("--exit-with-pipe needs --control-pipe");

        return allow.Count == 0 ? result : result with { Allow = allow };
    }

    /// <summary>A dotted-quad IPv4 address (<see cref="IPAddress.TryParse(string, out IPAddress)"/> also takes shorthand such as "10").</summary>
    public static bool TryParseIPv4(string text, [NotNullWhen(true)] out IPAddress? address) =>
        IPAddress.TryParse(text, out address) && address.AddressFamily == AddressFamily.InterNetwork && text.Count(c => c == '.') == 3;

    /// <summary>A trimmed, non-empty server name without control characters, at most <see cref="MaxServerNameChars"/> long.</summary>
    public static string Name(string text)
    {
        string name = text.Trim();
        if (name.Length == 0 || name.Length > MaxServerNameChars || name.Any(char.IsControl))
            throw new FormatException($"a server name needs 1 to {MaxServerNameChars} printable characters, got '{text}'");
        return name;
    }

    /// <summary>A pipe name: letters, digits, '.', '-' and '_' only (it becomes part of a path on some systems).</summary>
    private static string PipeName(string text) =>
        text.Length is > 0 and <= 64 && text.All(c => char.IsAsciiLetterOrDigit(c) || c is '.' or '-' or '_')
            ? text
            : throw new FormatException($"--control-pipe needs a name of letters, digits, '.', '-' and '_', got '{text}'");

    public static string DefaultLogFile =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "MobileController", "logs", "server.log");

    /// <summary>"xbox360" (also "xbox", "x360") or "ds4" (also "dualshock4"), any case.</summary>
    public static bool TryParseKind(string text, out PadKind kind)
    {
        switch (text.Trim().ToLowerInvariant())
        {
            case "xbox360" or "xbox" or "x360":
                kind = PadKind.Xbox360;
                return true;
            case "ds4" or "dualshock4":
                kind = PadKind.DualShock4;
                return true;
            default:
                kind = default;
                return false;
        }
    }
}

/// <summary>Settings for one phone (by deviceId): the pad it gets, and the name the desktop app shows for it.</summary>
internal sealed record DeviceRule(PadKind? Output, string? Alias);
