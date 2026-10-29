using System.Text.Json;
using System.Text.Json.Serialization;

namespace MobileController.Control;

/// <summary>
/// The local control channel between the server and the desktop app: two one-way named pipes carrying one JSON object
/// per line (NDJSON). The server writes events (<see cref="EventsPipe"/>): a <see cref="HelloMessage"/> on connect, a
/// <see cref="SnapshotMessage"/> about four times a second, <see cref="LiveMessage"/>s while a client wants them, every
/// <see cref="LogMessage"/>, and a <see cref="ReplyMessage"/> per command. The desktop app writes
/// <see cref="CommandMessage"/>s (<see cref="CommandsPipe"/>). Two pipes rather than one duplex pipe, because
/// synchronous I/O on one Windows pipe handle is serialised: a pending read would stall every write.
/// Both pipes are created for the current user only.
/// </summary>
public static class ControlProtocol
{
    /// <summary>Bumped when a message changes incompatibly; new fields alone do not bump it (readers ignore unknown ones).</summary>
    public const int Version = 1;

    public const string DefaultPipeName = "MobileController";

    public static string EventsPipe(string name) => name + ".events";
    public static string CommandsPipe(string name) => name + ".commands";

    // Command ops.
    public const string OpLive = "live";
    public const string OpIdentify = "identify";
    public const string OpDisconnect = "disconnect";
    public const string OpRumbleTest = "rumble";
    public const string OpOutput = "output";

    // Pad kinds and output preferences, as written in messages and in the settings file.
    public const string KindXbox360 = "xbox360";
    public const string KindDualShock4 = "ds4";
    public const string PreferenceNone = "none";

    /// <summary>JSON options matching what the server writes, for readers that do not use <see cref="ControlJsonContext"/>.</summary>
    public static JsonSerializerOptions JsonOptions => ControlJsonContext.Default.Options;

    private static readonly HashSet<string> KnownTypes = ["hello", "snapshot", "live", "log", "reply"];

    /// <summary>
    /// Parses one line of the events pipe; null for a blank line or a message type this version does not know (a newer
    /// server may send more). Throws <see cref="JsonException"/> for a line that is not a JSON object.
    /// </summary>
    public static ControlMessage? ParseEvent(string line)
    {
        if (string.IsNullOrWhiteSpace(line)) return null;
        using var document = JsonDocument.Parse(line);
        if (document.RootElement.ValueKind != JsonValueKind.Object ||
            !document.RootElement.TryGetProperty("type", out var type) ||
            type.ValueKind != JsonValueKind.String ||
            !KnownTypes.Contains(type.GetString()!))
            return null;
        return document.RootElement.Deserialize(ControlJsonContext.Default.ControlMessage);
    }

    public static string Serialize(CommandMessage command) => JsonSerializer.Serialize(command, ControlJsonContext.Default.CommandMessage);

    /// <summary>One event as the server writes it (used to save snapshots in a diagnostics bundle).</summary>
    public static string Serialize(ControlMessage message) => JsonSerializer.Serialize(message, ControlJsonContext.Default.ControlMessage);

    public static CommandMessage? ParseCommand(string line) => JsonSerializer.Deserialize(line, ControlJsonContext.Default.CommandMessage);
}

[JsonPolymorphic(TypeDiscriminatorPropertyName = "type", IgnoreUnrecognizedTypeDiscriminators = false)]
[JsonDerivedType(typeof(HelloMessage), "hello")]
[JsonDerivedType(typeof(SnapshotMessage), "snapshot")]
[JsonDerivedType(typeof(LiveMessage), "live")]
[JsonDerivedType(typeof(LogMessage), "log")]
[JsonDerivedType(typeof(ReplyMessage), "reply")]
public abstract record ControlMessage;

/// <summary>First message on every connection: who the server is and how it was started.</summary>
public sealed record HelloMessage(
    int ControlVersion,
    string ServerVersion,
    int ProtocolVersion,
    int Pid,
    string ServerName,
    int Port,
    string Bind,
    int MaxPads,
    bool DryRun,
    string DefaultOutput,
    string? LogFile,
    DateTime StartedAt) : ControlMessage;

public sealed record SnapshotMessage(
    double Uptime,
    long Datagrams,
    long InvalidFromUnknown,
    long Blocked,
    int PadsInUse,
    LoopHealth Loop,
    GcHealth Gc,
    IReadOnlyList<PadInfo> Pads) : ControlMessage;

/// <summary>The receive loop: how often it ran per second and its slowest pass in the last window.</summary>
public sealed record LoopHealth(double PassesPerSecond, double MaxPassMs);

/// <summary>Garbage collections of the server process so far, and their total pause time.</summary>
public sealed record GcHealth(int Gen0, int Gen1, int Gen2, double PauseMs);

/// <summary>One connected phone (one controller slot).</summary>
public sealed record PadInfo(
    int Index,
    string EndPoint,
    double ConnectedSeconds,
    string? DeviceId,
    string? DeviceName,
    string? Profile,
    string? Alias,
    int AppVersion,
    int? Battery,
    bool Charging,
    bool MenuOpen,
    bool MotionActive,
    double? PhoneRttMs,
    int RateHz,
    string Preference,
    string Kind,
    int? Player,
    int LargeMotor,
    int SmallMotor,
    bool TimedOut,
    double InputsPerSecond,
    double LossPercent,
    double JitterMs,
    double GapP50Ms,
    double GapP95Ms,
    double GapP99Ms,
    double GapMaxMs,
    long Accepted,
    long Duplicates,
    long Stale,
    long Invalid,
    long Submits);

/// <summary>What the games see right now, per slot (sent at up to 30 Hz while a client asked for it).</summary>
public sealed record LiveMessage(IReadOnlyList<LivePad> Pads) : ControlMessage;

public sealed record LivePad(int Index, int Buttons, int LeftTrigger, int RightTrigger, int LX, int LY, int RX, int RY, int LargeMotor, int SmallMotor);

public sealed record LogMessage(DateTime Time, string Level, string Message) : ControlMessage;

public sealed record ReplyMessage(int Id, bool Ok, string? Error) : ControlMessage;

/// <summary>
/// A request to the server: <c>live</c> (Value "on"/"off"), <c>identify</c>, <c>disconnect</c>, <c>rumble</c> (a short
/// test buzz) and <c>output</c> (Value "xbox360"/"ds4"/"" for the phone's own choice) for the slot <see cref="Index"/>.
/// </summary>
public sealed record CommandMessage(int Id, string Op, int? Index = null, string? Value = null);

[JsonSourceGenerationOptions(
    PropertyNamingPolicy = JsonKnownNamingPolicy.CamelCase,
    DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
    AllowOutOfOrderMetadataProperties = true)]
[JsonSerializable(typeof(ControlMessage))]
[JsonSerializable(typeof(CommandMessage))]
internal sealed partial class ControlJsonContext : JsonSerializerContext;
