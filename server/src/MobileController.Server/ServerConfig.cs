using System.Diagnostics.CodeAnalysis;
using System.Globalization;
using System.Net;
using System.Text.Json;
using MobileController.Control;

namespace MobileController.Server;

/// <summary>
/// Applies the settings file (<see cref="ServerSettings"/>, shared with the desktop app) to the options. Problems that
/// make a value unusable are errors: the server must not start on half a configuration. Unknown keys and a newer
/// schema are only warnings, so a file written by a newer desktop app still starts an older server.
/// </summary>
internal static class ServerConfig
{
    public static bool TryLoad(
        string path, Options defaults, [NotNullWhen(true)] out Options? options, List<string> warnings, [NotNullWhen(false)] out string? error)
    {
        options = null;
        ServerSettings? settings;
        try
        {
            using var stream = File.OpenRead(path);
            settings = ServerSettings.Read(stream);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            error = $"settings file {path}: {e.Message}";
            return false;
        }
        catch (JsonException e)
        {
            error = $"settings file {path} is not valid JSON (line {e.LineNumber + 1}): {e.Message}";
            return false;
        }
        if (settings is null)
        {
            error = $"settings file {path} must contain a JSON object";
            return false;
        }

        try
        {
            options = ApplyTo(settings, defaults, warnings);
            error = null;
            return true;
        }
        catch (FormatException e)
        {
            error = $"settings file {path}: {e.Message}";
            return false;
        }
    }

    public static Options ApplyTo(ServerSettings s, Options o, List<string> warnings)
    {
        if (s.Schema > ServerSettings.CurrentSchema)
            warnings.Add($"The settings file was written for a newer version (schema {s.Schema}); settings this server does not know are ignored.");
        if (s.Unknown is { Count: > 0 })
            warnings.Add($"Unknown settings ignored: {string.Join(", ", s.Unknown.Keys.Order(StringComparer.Ordinal))}");

        if (s.Port is int port) o = o with { Port = Range("port", port, 1, 65535) };
        if (s.Bind is not null)
            o = o with { BindAddress = Options.TryParseIPv4(s.Bind, out var bind) ? bind : throw new FormatException($"bind must be an IPv4 address, got '{s.Bind}'") };
        if (s.MaxPads is int pads) o = o with { MaxPads = Range("maxPads", pads, 1, Options.MaxMaxPads) };
        if (s.NeutralTimeoutMs is int neutral) o = o with { NeutralTimeoutMs = Range("neutralTimeoutMs", neutral, 1, 600_000) };
        if (s.DisconnectTimeoutMs is int disconnect) o = o with { DisconnectTimeoutMs = Range("disconnectTimeoutMs", disconnect, 1, 3_600_000) };
        if (s.Allow is not null)
        {
            var allow = new List<IPAddress>(s.Allow.Count);
            foreach (string text in s.Allow)
                allow.Add(Options.TryParseIPv4(text, out var ip) ? ip : throw new FormatException($"allow entries must be IPv4 addresses, got '{text}'"));
            o = o with { Allow = allow };
        }
        if (s.Name is not null) o = o with { ServerName = Options.Name(s.Name) };
        if (s.GameDetect is bool detect) o = o with { NoGameDetect = !detect };
        if (s.Verbose is bool verbose) o = o with { Verbose = verbose };
        if (s.HighPriority is bool high) o = o with { HighPriority = high };
        if (s.LogToFile is bool toFile) o = o with { LogFile = toFile ? s.LogFilePath ?? Options.DefaultLogFile : null };
        if (s.DefaultOutput is not null)
            o = o with { DefaultOutput = Options.TryParseKind(s.DefaultOutput, out var kind) ? kind : throw new FormatException($"defaultOutput must be xbox360 or ds4, got '{s.DefaultOutput}'") };
        if (s.Devices is not null)
        {
            var rules = new Dictionary<ulong, DeviceRule>();
            foreach (var device in s.Devices)
            {
                if (!TryParseDeviceId(device.Id, out ulong id))
                    throw new FormatException($"devices: id must be the phone's 16-digit hexadecimal device id, got '{device.Id}'");
                PadKind? output = null;
                if (!string.IsNullOrEmpty(device.Output))
                    output = Options.TryParseKind(device.Output, out var kind) ? kind : throw new FormatException($"devices: output must be xbox360 or ds4, got '{device.Output}'");
                rules[id] = new DeviceRule(output, string.IsNullOrWhiteSpace(device.Alias) ? null : device.Alias.Trim());
            }
            o = o with { Devices = rules };
        }
        return o;
    }

    public static bool TryParseDeviceId(string? text, out ulong id) =>
        ulong.TryParse(text, NumberStyles.HexNumber, CultureInfo.InvariantCulture, out id) && id != 0;

    private static int Range(string name, int value, int min, int max) =>
        value >= min && value <= max ? value : throw new FormatException($"{name} must be between {min} and {max}, got {value}");
}
