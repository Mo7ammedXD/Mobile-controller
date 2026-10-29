using System.Buffers;
using System.Buffers.Binary;
using System.Text;
using System.Text.Unicode;

namespace MobileController.Server;

/// <summary>Low nibble of the <c>verType</c> byte (PROTOCOL.md §2).</summary>
internal enum PacketType : byte
{
    Input = 0x1,
    Ping = 0x2,
    Bye = 0x3,
    Discover = 0x4,
    Status = 0x5,
    Feedback = 0x9,
    Pong = 0xA,
    Game = 0xB,
    DiscoverReply = 0xC,
    Command = 0xD,
}

[Flags]
internal enum FeedbackFlags : byte
{
    None = 0,
    PadAttached = 1 << 0,
    ServerFull = 1 << 1,
    DryRun = 1 << 2,
    TargetDs4 = 1 << 3,
    AwaitingApproval = 1 << 4,
    Denied = 1 << 5,
    StatusAck = 1 << 6,
}

[Flags]
internal enum StatusFlags : byte
{
    None = 0,
    Charging = 1 << 0,
    MenuOpen = 1 << 1,
    MotionActive = 1 << 2,
}

/// <summary>The pad a phone asks for in STATUS (§12), and the pad a slot actually has.</summary>
internal enum OutputPreference : byte
{
    None = 0,
    Xbox360 = 1,
    DualShock4 = 2,
}

/// <summary>Why a datagram was dropped, in the order PROTOCOL.md §2 lists the checks.</summary>
internal enum PacketStatus : byte
{
    Ok,
    TooShort,
    BadMagic,
    BadVersion,
    UnknownType,
    WrongDirection,
    BadLength,

    /// <summary>Well-formed, but a field holds a forbidden value (STATUS with deviceId 0).</summary>
    BadValue,
}

/// <summary>A validated STATUS (§12), read in place: the names are slices of the datagram.</summary>
internal readonly ref struct StatusView
{
    public StatusView(ReadOnlySpan<byte> datagram)
    {
        DeviceId = BinaryPrimitives.ReadUInt64LittleEndian(datagram[2..]);
        Battery = datagram[10];
        Flags = (StatusFlags)datagram[11];
        RttTenthsMs = BinaryPrimitives.ReadUInt16LittleEndian(datagram[12..]);
        RateHz = datagram[14];
        Output = datagram[15] is (byte)OutputPreference.Xbox360 or (byte)OutputPreference.DualShock4
            ? (OutputPreference)datagram[15]
            : OutputPreference.None;
        AppVersion = BinaryPrimitives.ReadUInt16LittleEndian(datagram[16..]);
        int e = datagram[18], d = datagram[19], p = datagram[20];
        DeviceName = datagram.Slice(Protocol.StatusHeaderSize + e, d);
        ProfileName = datagram.Slice(Protocol.StatusHeaderSize + e + d, p);
    }

    public ulong DeviceId { get; }

    /// <summary>0..100, or <see cref="Protocol.UnknownBattery"/>.</summary>
    public byte Battery { get; }
    public StatusFlags Flags { get; }

    /// <summary>Smoothed RTT in 0.1 ms, or <see cref="Protocol.UnknownRtt"/>.</summary>
    public ushort RttTenthsMs { get; }
    public byte RateHz { get; }
    public OutputPreference Output { get; }
    public ushort AppVersion { get; }
    public ReadOnlySpan<byte> DeviceName { get; }
    public ReadOnlySpan<byte> ProfileName { get; }
}

internal enum SequenceVerdict : byte
{
    Accept,
    Duplicate,
    Stale,
}

/// <summary>
/// One complete controller snapshot. The field order and sizes are those of <c>XUSB_REPORT</c> / <c>XINPUT_GAMEPAD</c>
/// (12 bytes), so it maps 1:1 onto the virtual pad without translation.
/// </summary>
internal readonly struct PadState(ushort buttons, byte leftTrigger, byte rightTrigger,
    short thumbLX, short thumbLY, short thumbRX, short thumbRY) : IEquatable<PadState>
{
    public ushort Buttons { get; } = buttons;
    public byte LeftTrigger { get; } = leftTrigger;
    public byte RightTrigger { get; } = rightTrigger;
    public short ThumbLX { get; } = thumbLX;
    public short ThumbLY { get; } = thumbLY;
    public short ThumbRX { get; } = thumbRX;
    public short ThumbRY { get; } = thumbRY;

    public bool Equals(PadState other) =>
        Buttons == other.Buttons && LeftTrigger == other.LeftTrigger && RightTrigger == other.RightTrigger &&
        ThumbLX == other.ThumbLX && ThumbLY == other.ThumbLY && ThumbRX == other.ThumbRX && ThumbRY == other.ThumbRY;

    public override bool Equals(object? obj) => obj is PadState other && Equals(other);
    public override int GetHashCode() => HashCode.Combine(Buttons, LeftTrigger, RightTrigger, ThumbLX, ThumbLY, ThumbRX, ThumbRY);
    public static bool operator ==(PadState left, PadState right) => left.Equals(right);
    public static bool operator !=(PadState left, PadState right) => !left.Equals(right);

    public override string ToString() =>
        $"btn=0x{Buttons:X4} LT={LeftTrigger} RT={RightTrigger} L=({ThumbLX},{ThumbLY}) R=({ThumbRX},{ThumbRY})";
}

/// <summary>Decoded <c>FEEDBACK</c> datagram (server → client).</summary>
internal readonly record struct FeedbackPacket(byte LargeMotor, byte SmallMotor, byte PlayerIndex, FeedbackFlags Flags, ushort LastSeq);

/// <summary>
/// Wire protocol v1 (docs/PROTOCOL.md, normative). Every helper is span-based and allocation-free so it can sit on
/// the receive hot path.
/// </summary>
internal static class Protocol
{
    public const byte Magic = 0xA5;
    public const int Version = 1;
    public const int DefaultPort = 24680;

    /// <summary>Bit 0x0800 is reserved; the server must clear it (§3.1).</summary>
    public const ushort ButtonMask = 0xF7FF;

    public const int InputSize = 16;
    public const int PingSize = 8;
    public const int PongSize = 8;
    public const int ByeSize = 2;
    public const int DiscoverSize = 4;
    public const int FeedbackSize = 8;
    public const int DiscoverReplyHeaderSize = 7;
    public const int MaxNameBytes = 32;
    public const int GameHeaderSize = 3;
    public const int MaxGameNameBytes = 64;
    public const int StatusHeaderSize = 21;
    public const int MaxStatusFieldBytes = 32;
    public const int MaxStatusSize = StatusHeaderSize + 3 * MaxStatusFieldBytes;
    public const int CommandHeaderSize = 4;
    public const int MaxCommandArgBytes = 32;

    /// <summary>The largest server → client datagram: a GAME with a 64-byte name.</summary>
    public const int MaxDatagramSize = GameHeaderSize + MaxGameNameBytes;

    public const byte UnknownPlayer = 0xFF;
    public const byte UnknownBattery = 0xFF;
    public const ushort UnknownRtt = 0xFFFF;

    // COMMAND ops (§13).
    public const byte CommandIdentify = 0x01;
    public const byte CommandLightbar = 0x02;

    /// <summary>An INPUT whose seq is 1..64 behind the last accepted one is a reordered straggler (§3.3).</summary>
    public const int StaleWindow = 64;

    public const int DefaultNeutralTimeoutMs = 500;
    public const int DefaultDisconnectTimeoutMs = 10_000;
    public const int FeedbackIntervalMs = 100;
    public const int ServerFullIntervalMs = 250;
    public const int GameIntervalMs = 2000;
    public const int GamePollMs = 500;

    public static byte VerType(PacketType type) => (byte)(Version << 4 | (byte)type);

    /// <summary>Types 0x1–0x7 travel client → server, 0x8–0xF server → client.</summary>
    public static bool IsClientToServer(PacketType type) => (byte)type <= 0x7;

    /// <summary>Applies every drop rule of §2 for a datagram received by the server (<paramref name="atServer"/>) or the client.</summary>
    public static PacketStatus Validate(ReadOnlySpan<byte> datagram, bool atServer, out PacketType type)
    {
        type = default;
        if (datagram.Length < 2) return PacketStatus.TooShort;
        if (datagram[0] != Magic) return PacketStatus.BadMagic;
        if (datagram[1] >> 4 != Version) return PacketStatus.BadVersion;

        type = (PacketType)(datagram[1] & 0x0F);
        int expected = type switch
        {
            PacketType.Input => InputSize,
            PacketType.Ping => PingSize,
            PacketType.Bye => ByeSize,
            PacketType.Discover => DiscoverSize,
            PacketType.Feedback => FeedbackSize,
            PacketType.Pong => PongSize,
            PacketType.Game => datagram.Length >= GameHeaderSize && datagram[2] <= MaxGameNameBytes
                ? GameHeaderSize + datagram[2]
                : -1,
            PacketType.DiscoverReply => datagram.Length >= DiscoverReplyHeaderSize && datagram[6] <= MaxNameBytes
                ? DiscoverReplyHeaderSize + datagram[6]
                : -1,
            PacketType.Status => datagram.Length >= StatusHeaderSize &&
                                 datagram[18] <= MaxStatusFieldBytes && datagram[19] <= MaxStatusFieldBytes && datagram[20] <= MaxStatusFieldBytes
                ? StatusHeaderSize + datagram[18] + datagram[19] + datagram[20]
                : -1,
            PacketType.Command => datagram.Length >= CommandHeaderSize && datagram[3] <= MaxCommandArgBytes
                ? CommandHeaderSize + datagram[3]
                : -1,
            _ => 0,
        };
        if (expected == 0) return PacketStatus.UnknownType;
        if (IsClientToServer(type) != atServer) return PacketStatus.WrongDirection;
        if (datagram.Length != expected) return PacketStatus.BadLength;
        if (type == PacketType.Status && BinaryPrimitives.ReadUInt64LittleEndian(datagram[2..]) == 0) return PacketStatus.BadValue;
        return PacketStatus.Ok;
    }

    public static bool TryParseStatus(ReadOnlySpan<byte> datagram, out StatusView status)
    {
        bool ok = Validate(datagram, atServer: true, out var type) == PacketStatus.Ok && type == PacketType.Status;
        status = ok ? new StatusView(datagram) : default;
        return ok;
    }

    /// <summary>Client side; on success <paramref name="args"/> is the argument slice of <paramref name="datagram"/>.</summary>
    public static bool TryParseCommand(ReadOnlySpan<byte> datagram, out byte op, out ReadOnlySpan<byte> args)
    {
        bool ok = Validate(datagram, atServer: false, out var type) == PacketStatus.Ok && type == PacketType.Command;
        op = ok ? datagram[2] : (byte)0;
        args = ok ? datagram.Slice(CommandHeaderSize, datagram[3]) : default;
        return ok;
    }

    /// <summary>Decodes an already validated INPUT; the reserved button bit is masked off here.</summary>
    public static PadState ReadInput(ReadOnlySpan<byte> datagram, out ushort seq)
    {
        seq = BinaryPrimitives.ReadUInt16LittleEndian(datagram[2..]);
        return new PadState(
            (ushort)(BinaryPrimitives.ReadUInt16LittleEndian(datagram[4..]) & ButtonMask),
            datagram[6],
            datagram[7],
            BinaryPrimitives.ReadInt16LittleEndian(datagram[8..]),
            BinaryPrimitives.ReadInt16LittleEndian(datagram[10..]),
            BinaryPrimitives.ReadInt16LittleEndian(datagram[12..]),
            BinaryPrimitives.ReadInt16LittleEndian(datagram[14..]));
    }

    public static bool TryParseInput(ReadOnlySpan<byte> datagram, out ushort seq, out PadState state)
    {
        if (Validate(datagram, atServer: true, out var type) != PacketStatus.Ok || type != PacketType.Input)
        {
            seq = 0;
            state = default;
            return false;
        }
        state = ReadInput(datagram, out seq);
        return true;
    }

    public static bool TryParsePing(ReadOnlySpan<byte> datagram, out ushort pingId, out uint clientTime) =>
        TryParseIdAndTime(datagram, atServer: true, PacketType.Ping, out pingId, out clientTime);

    public static bool TryParsePong(ReadOnlySpan<byte> datagram, out ushort pingId, out uint clientTime) =>
        TryParseIdAndTime(datagram, atServer: false, PacketType.Pong, out pingId, out clientTime);

    public static bool TryParseDiscover(ReadOnlySpan<byte> datagram, out ushort nonce)
    {
        bool ok = Validate(datagram, atServer: true, out var type) == PacketStatus.Ok && type == PacketType.Discover;
        nonce = ok ? BinaryPrimitives.ReadUInt16LittleEndian(datagram[2..]) : (ushort)0;
        return ok;
    }

    public static bool TryParseFeedback(ReadOnlySpan<byte> datagram, out FeedbackPacket feedback)
    {
        bool ok = Validate(datagram, atServer: false, out var type) == PacketStatus.Ok && type == PacketType.Feedback;
        feedback = ok
            ? new FeedbackPacket(datagram[2], datagram[3], datagram[4], (FeedbackFlags)datagram[5],
                BinaryPrimitives.ReadUInt16LittleEndian(datagram[6..]))
            : default;
        return ok;
    }

    /// <summary>On success <paramref name="nameUtf8"/> is the name slice of <paramref name="datagram"/>.</summary>
    public static bool TryParseDiscoverReply(ReadOnlySpan<byte> datagram, out ushort nonce, out byte padsInUse,
        out byte maxPads, out ReadOnlySpan<byte> nameUtf8)
    {
        bool ok = Validate(datagram, atServer: false, out var type) == PacketStatus.Ok && type == PacketType.DiscoverReply;
        nonce = ok ? BinaryPrimitives.ReadUInt16LittleEndian(datagram[2..]) : (ushort)0;
        padsInUse = ok ? datagram[4] : (byte)0;
        maxPads = ok ? datagram[5] : (byte)0;
        nameUtf8 = ok ? datagram.Slice(DiscoverReplyHeaderSize, datagram[6]) : default;
        return ok;
    }

    /// <summary>Client side; on success <paramref name="name"/> is the name slice of <paramref name="datagram"/> (empty = unknown).</summary>
    public static bool TryParseGame(ReadOnlySpan<byte> datagram, out ReadOnlySpan<byte> name)
    {
        bool ok = Validate(datagram, atServer: false, out var type) == PacketStatus.Ok && type == PacketType.Game;
        name = ok ? datagram.Slice(GameHeaderSize, datagram[2]) : default;
        return ok;
    }

    public static int WriteInput(Span<byte> destination, ushort seq, in PadState state)
    {
        WriteHeader(destination, PacketType.Input, InputSize);
        BinaryPrimitives.WriteUInt16LittleEndian(destination[2..], seq);
        BinaryPrimitives.WriteUInt16LittleEndian(destination[4..], state.Buttons);
        destination[6] = state.LeftTrigger;
        destination[7] = state.RightTrigger;
        BinaryPrimitives.WriteInt16LittleEndian(destination[8..], state.ThumbLX);
        BinaryPrimitives.WriteInt16LittleEndian(destination[10..], state.ThumbLY);
        BinaryPrimitives.WriteInt16LittleEndian(destination[12..], state.ThumbRX);
        BinaryPrimitives.WriteInt16LittleEndian(destination[14..], state.ThumbRY);
        return InputSize;
    }

    public static int WritePing(Span<byte> destination, ushort pingId, uint clientTime)
    {
        WriteHeader(destination, PacketType.Ping, PingSize);
        BinaryPrimitives.WriteUInt16LittleEndian(destination[2..], pingId);
        BinaryPrimitives.WriteUInt32LittleEndian(destination[4..], clientTime);
        return PingSize;
    }

    /// <summary>PONG echoes bytes 2..7 of the PING verbatim; clientTime is opaque to the server (§6).</summary>
    public static int WritePong(Span<byte> destination, ReadOnlySpan<byte> ping)
    {
        WriteHeader(destination, PacketType.Pong, PongSize);
        ping.Slice(2, PingSize - 2).CopyTo(destination[2..]);
        return PongSize;
    }

    public static int WriteBye(Span<byte> destination)
    {
        WriteHeader(destination, PacketType.Bye, ByeSize);
        return ByeSize;
    }

    public static int WriteDiscover(Span<byte> destination, ushort nonce)
    {
        WriteHeader(destination, PacketType.Discover, DiscoverSize);
        BinaryPrimitives.WriteUInt16LittleEndian(destination[2..], nonce);
        return DiscoverSize;
    }

    public static int WriteFeedback(Span<byte> destination, byte largeMotor, byte smallMotor, byte playerIndex,
        FeedbackFlags flags, ushort lastSeq)
    {
        WriteHeader(destination, PacketType.Feedback, FeedbackSize);
        destination[2] = largeMotor;
        destination[3] = smallMotor;
        destination[4] = playerIndex;
        destination[5] = (byte)flags;
        BinaryPrimitives.WriteUInt16LittleEndian(destination[6..], lastSeq);
        return FeedbackSize;
    }

    /// <param name="nameUtf8">At most <see cref="MaxNameBytes"/> bytes; use <see cref="EncodeName"/> to produce it.</param>
    public static int WriteDiscoverReply(Span<byte> destination, ushort nonce, byte padsInUse, byte maxPads,
        ReadOnlySpan<byte> nameUtf8)
    {
        ArgumentOutOfRangeException.ThrowIfGreaterThan(nameUtf8.Length, MaxNameBytes, nameof(nameUtf8));
        int length = DiscoverReplyHeaderSize + nameUtf8.Length;
        WriteHeader(destination, PacketType.DiscoverReply, length);
        BinaryPrimitives.WriteUInt16LittleEndian(destination[2..], nonce);
        destination[4] = padsInUse;
        destination[5] = maxPads;
        destination[6] = (byte)nameUtf8.Length;
        nameUtf8.CopyTo(destination[DiscoverReplyHeaderSize..]);
        return length;
    }

    /// <summary>
    /// UTF-8 encodes <paramref name="name"/> into at most <see cref="MaxNameBytes"/> bytes, cutting only at a scalar
    /// boundary (§8, vector P7). <see cref="Utf8.FromUtf16"/> never emits a partial sequence when the destination is
    /// too small, which is exactly the truncation rule.
    /// </summary>
    public static int EncodeName(ReadOnlySpan<char> name, Span<byte> destination)
    {
        var limited = destination[..Math.Min(destination.Length, MaxNameBytes)];
        var status = Utf8.FromUtf16(name, limited, out _, out int written);
        return status is OperationStatus.Done or OperationStatus.DestinationTooSmall ? written : 0;
    }

    /// <summary>Client side of §12 (tests and tools); names must already be encoded with <see cref="EncodeName"/>.</summary>
    public static int WriteStatus(Span<byte> destination, ulong deviceId, byte battery, StatusFlags flags, ushort rttTenthsMs,
        byte rateHz, OutputPreference output, ushort appVersion, ReadOnlySpan<byte> deviceName, ReadOnlySpan<byte> profileName)
    {
        ArgumentOutOfRangeException.ThrowIfGreaterThan(deviceName.Length, MaxStatusFieldBytes, nameof(deviceName));
        ArgumentOutOfRangeException.ThrowIfGreaterThan(profileName.Length, MaxStatusFieldBytes, nameof(profileName));
        int length = StatusHeaderSize + deviceName.Length + profileName.Length;
        WriteHeader(destination, PacketType.Status, length);
        BinaryPrimitives.WriteUInt64LittleEndian(destination[2..], deviceId);
        destination[10] = battery;
        destination[11] = (byte)flags;
        BinaryPrimitives.WriteUInt16LittleEndian(destination[12..], rttTenthsMs);
        destination[14] = rateHz;
        destination[15] = (byte)output;
        BinaryPrimitives.WriteUInt16LittleEndian(destination[16..], appVersion);
        destination[18] = 0;
        destination[19] = (byte)deviceName.Length;
        destination[20] = (byte)profileName.Length;
        deviceName.CopyTo(destination[StatusHeaderSize..]);
        profileName.CopyTo(destination[(StatusHeaderSize + deviceName.Length)..]);
        return length;
    }

    public static int WriteCommand(Span<byte> destination, byte op, ReadOnlySpan<byte> args)
    {
        ArgumentOutOfRangeException.ThrowIfGreaterThan(args.Length, MaxCommandArgBytes, nameof(args));
        int length = CommandHeaderSize + args.Length;
        WriteHeader(destination, PacketType.Command, length);
        destination[2] = op;
        destination[3] = (byte)args.Length;
        args.CopyTo(destination[CommandHeaderSize..]);
        return length;
    }

    /// <param name="name">At most <see cref="MaxGameNameBytes"/> bytes; use <see cref="NormalizeGameName"/> to produce it.</param>
    public static int WriteGame(Span<byte> destination, ReadOnlySpan<byte> name)
    {
        ArgumentOutOfRangeException.ThrowIfGreaterThan(name.Length, MaxGameNameBytes, nameof(name));
        int length = GameHeaderSize + name.Length;
        WriteHeader(destination, PacketType.Game, length);
        destination[2] = (byte)name.Length;
        name.CopyTo(destination[GameHeaderSize..]);
        return length;
    }

    /// <summary>
    /// The GAME name of §10 from a process image path or bare file name: the file name only, lower-cased, every
    /// character outside 0x20..0x7E replaced by '?', cut at <see cref="MaxGameNameBytes"/>. One '?' per Unicode
    /// scalar (a surrogate pair is one character, not two), so the result has one byte per character of the name.
    /// Casing is ASCII-only: culture-free, and a non-ASCII letter becomes '?' anyway.
    /// </summary>
    public static int NormalizeGameName(ReadOnlySpan<char> path, Span<byte> destination)
    {
        // QueryFullProcessImageNameW returns backslashes, but '/' is a separator on Windows as well.
        var name = path[(path.LastIndexOfAny('\\', '/') + 1)..];
        var limited = destination[..Math.Min(destination.Length, MaxGameNameBytes)];
        int written = 0;
        while (!name.IsEmpty && written < limited.Length)
        {
            // A lone surrogate decodes as U+FFFD and consumes one char, so the loop always advances.
            Rune.DecodeFromUtf16(name, out var rune, out int consumed);
            name = name[consumed..];
            int c = rune.Value;
            limited[written++] = c is < 0x20 or > 0x7E ? (byte)'?' : (byte)(c is >= 'A' and <= 'Z' ? c | 0x20 : c);
        }
        return written;
    }

    /// <summary>Server acceptance rule of §3.3; the first matching row wins.</summary>
    public static SequenceVerdict CheckSequence(bool hasAccepted, bool timedOut, ushort lastAccepted, ushort seq)
    {
        if (!hasAccepted || timedOut) return SequenceVerdict.Accept;
        int d = (ushort)(seq - lastAccepted);
        if (d == 0) return SequenceVerdict.Duplicate;
        return d >= 65536 - StaleWindow ? SequenceVerdict.Stale : SequenceVerdict.Accept;
    }

    private static void WriteHeader(Span<byte> destination, PacketType type, int length)
    {
        ArgumentOutOfRangeException.ThrowIfLessThan(destination.Length, length, nameof(destination));
        destination[0] = Magic;
        destination[1] = VerType(type);
    }

    private static bool TryParseIdAndTime(ReadOnlySpan<byte> datagram, bool atServer, PacketType expected,
        out ushort id, out uint time)
    {
        bool ok = Validate(datagram, atServer, out var type) == PacketStatus.Ok && type == expected;
        id = ok ? BinaryPrimitives.ReadUInt16LittleEndian(datagram[2..]) : (ushort)0;
        time = ok ? BinaryPrimitives.ReadUInt32LittleEndian(datagram[4..]) : 0;
        return ok;
    }
}
