using System.Text.Json;
using System.Text.Json.Serialization;

namespace MobileController.Control;

// What `MobileController.Server --diagnose --json` prints (the desktop app's setup wizard and dashboard read it).

public sealed record DiagnosticsReport(
    string Version,
    int ProtocolVersion,
    string Os,
    string Architecture,
    string Computer,
    string ServerName,
    DriverCheck Driver,
    PortCheck Port,
    List<AdapterInfo> Adapters,
    PowerCheck Power,
    ConfigCheck Config);

public sealed record DriverCheck(bool Ok, string? Problem);

public sealed record PortCheck(int Port, string Bind, bool Ok, bool InUse, string? Problem);

/// <summary><see cref="Kind"/> is an <see cref="AdapterKind"/> name.</summary>
public sealed record AdapterInfo(string Address, string Name, string Description, string Kind, bool Reachable);

public sealed record PowerCheck(bool Ok, string? Problem);

public sealed record ConfigCheck(string? Path, List<string> Warnings);

[JsonSourceGenerationOptions(PropertyNamingPolicy = JsonKnownNamingPolicy.CamelCase, WriteIndented = true)]
[JsonSerializable(typeof(DiagnosticsReport))]
internal sealed partial class DiagnosticsContext : JsonSerializerContext;

public static class DiagnosticsJson
{
    public static string Serialize(DiagnosticsReport report) => JsonSerializer.Serialize(report, DiagnosticsContext.Default.DiagnosticsReport);

    /// <summary>Null when <paramref name="json"/> is not a report (e.g. an older server printed text).</summary>
    public static DiagnosticsReport? Parse(string json)
    {
        try
        {
            return JsonSerializer.Deserialize(json, DiagnosticsContext.Default.DiagnosticsReport);
        }
        catch (JsonException)
        {
            return null;
        }
    }
}
