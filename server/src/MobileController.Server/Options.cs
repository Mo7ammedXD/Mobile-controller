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

    // Not on the command line: fixed by the protocol (or internal back-off), overridable so tests run fast.
    public int FeedbackIntervalMs { get; init; } = Protocol.FeedbackIntervalMs;
    public int ServerFullIntervalMs { get; init; } = Protocol.ServerFullIntervalMs;
    public int GameIntervalMs { get; init; } = Protocol.GameIntervalMs;
    public int GamePollMs { get; init; } = Protocol.GamePollMs;
    public int StatsIntervalMs { get; init; } = 5_000;
    public int ConnectRetryMs { get; init; } = 500;
    public string ServerName { get; init; } = Environment.MachineName;

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
          --no-game-detect           Do not tell phones the foreground app (no automatic game modes)
          --dry-run                  No virtual controllers: track phones and log decoded packets
          --test-pattern [seconds]   No network: plug in one controller and animate it (default 30 s)
          --verbose                  Print per-phone statistics every 5 s
          --high-priority            Run at High process priority (default AboveNormal)
          -h, --help                 Show this help

        Examples:
          MobileController.Server
          MobileController.Server --test-pattern 10
          MobileController.Server --port 24681 --allow 192.168.1.50 --verbose
        """;

    public static bool TryParse(string[] args, [NotNullWhen(true)] out Options? options, [NotNullWhen(false)] out string? error)
    {
        try
        {
            options = Parse(args);
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

    private static Options Parse(string[] args)
    {
        var result = new Options();
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
                // IPAddress.TryParse also accepts shorthand such as "10" or "10.1"; insist on dotted quads.
                return IPAddress.TryParse(text, out var address) && address.AddressFamily == AddressFamily.InterNetwork &&
                       text.Count(c => c == '.') == 3
                    ? address
                    : throw new FormatException($"{name} needs an IPv4 address such as 192.168.1.50, got '{text}'");
            }
        }

        if (result.DryRun && result.TestPatternSeconds is not null)
            throw new FormatException("--dry-run and --test-pattern cannot be combined (the test pattern needs ViGEmBus)");

        return result with { Allow = allow };
    }
}
