using System.Text.Json;
using System.Text.Json.Serialization;

namespace MobileController.Control;

/// <summary>
/// The server's settings file (default <c>%APPDATA%\MobileController\server.json</c>). The server reads it at start-up
/// (command-line options win over it); the desktop app edits it. Every value is optional: a missing one keeps the
/// built-in default. Keys this version does not know are kept in <see cref="Unknown"/>, so a file written by a newer
/// version survives a save by an older one.
/// </summary>
public sealed class ServerSettings
{
    public const int CurrentSchema = 1;

    public int? Schema { get; set; }
    public int? Port { get; set; }
    public string? Bind { get; set; }
    public int? MaxPads { get; set; }
    public int? NeutralTimeoutMs { get; set; }
    public int? DisconnectTimeoutMs { get; set; }
    public List<string>? Allow { get; set; }
    public string? Name { get; set; }
    public bool? GameDetect { get; set; }
    public bool? Verbose { get; set; }
    public bool? HighPriority { get; set; }

    /// <summary>Write the log to a file too; <see cref="LogFilePath"/> or the default location.</summary>
    public bool? LogToFile { get; set; }
    public string? LogFilePath { get; set; }

    /// <summary>"xbox360" or "ds4".</summary>
    public string? DefaultOutput { get; set; }

    public List<DeviceSettings>? Devices { get; set; }

    [JsonExtensionData]
    public Dictionary<string, JsonElement>? Unknown { get; set; }

    public static string DefaultPath =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "MobileController", "server.json");

    /// <summary>Reads a settings file; <see cref="JsonException"/> for invalid JSON, null for a file that holds JSON null.</summary>
    public static ServerSettings? Read(Stream stream) => JsonSerializer.Deserialize(stream, ServerSettingsContext.Default.ServerSettings);

    public static string ToJson(ServerSettings settings) => JsonSerializer.Serialize(settings, ServerSettingsContext.Default.ServerSettings);

    /// <summary>A deep copy (through JSON, so keys this version does not know are copied too).</summary>
    public ServerSettings Clone() => JsonSerializer.Deserialize(ToJson(this), ServerSettingsContext.Default.ServerSettings)!;

    /// <summary>
    /// Writes <paramref name="settings"/> to <paramref name="path"/> atomically (a temporary file, then a replace), so a
    /// server starting at the same moment reads either the old or the new file, never half of one.
    /// </summary>
    public static void Save(string path, ServerSettings settings)
    {
        settings.Schema ??= CurrentSchema;
        Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(path))!);
        string temp = path + ".tmp";
        File.WriteAllText(temp, ToJson(settings));
        if (File.Exists(path)) File.Replace(temp, path, destinationBackupFileName: null);
        else File.Move(temp, path);
    }
}

/// <summary>One phone, by the 16-hex-digit deviceId it sends in STATUS: the pad it gets and the name shown for it.</summary>
public sealed class DeviceSettings
{
    public string? Id { get; set; }
    public string? Alias { get; set; }

    /// <summary>"xbox360", "ds4", or null for the phone's own choice.</summary>
    public string? Output { get; set; }

    [JsonExtensionData]
    public Dictionary<string, JsonElement>? Unknown { get; set; }
}

[JsonSourceGenerationOptions(
    PropertyNamingPolicy = JsonKnownNamingPolicy.CamelCase,
    ReadCommentHandling = JsonCommentHandling.Skip,
    AllowTrailingCommas = true,
    WriteIndented = true,
    DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull)]
[JsonSerializable(typeof(ServerSettings))]
internal sealed partial class ServerSettingsContext : JsonSerializerContext;
