using System.Buffers.Binary;
using System.Net;
using System.Net.Sockets;

namespace MobileController.Server;

/// <summary>
/// One connected phone (one source endpoint) and its virtual pad. A new instance is created per connection and never
/// reused, so a late driver callback that captured an old instance can never touch a newer client's state.
/// Everything except the feedback fields is owned by the receive thread.
/// </summary>
internal sealed class PadSlot
{
    private const int FeedbackKnown = 1 << 24;

    private bool _hasAccepted;
    private bool _dirty;
    private bool _hasSubmitted;
    private PadState _pending;
    private PadState _submitted;

    // Shared with the driver's notification thread.
    private int _lastSeq;
    private int _feedback; // largeMotor | smallMotor << 8 | led << 16 | FeedbackKnown
    private volatile bool _closed;
    private volatile SocketAddress _address;
    private volatile bool _supportsStatus;

    public PadSlot(int index, SocketAddress address, long now)
    {
        Index = index;
        _address = address;
        Key = KeyOf(address);
        EndPoint = (IPEndPoint)new IPEndPoint(IPAddress.Any, 0).Create(address);
        Connected = now;
        LastSeen = now;
        LastAccepted = now;
        NextFeedback = now;
    }

    public int Index { get; }

    /// <summary>Family, port and IPv4 address of the sockaddr_in: enough to identify an endpoint in one compare.</summary>
    public ulong Key { get; private set; }

    /// <summary>Private copy of the client's address, used for every reply (also from the driver thread).</summary>
    public SocketAddress Address => _address;

    public IPEndPoint EndPoint { get; private set; }
    public IVirtualPad? Pad { get; set; }

    /// <summary>When the slot was created (Stopwatch ticks).</summary>
    public long Connected { get; }

    /// <summary>The flags every FEEDBACK of this slot carries: PAD_ATTACHED or DRY_RUN, and TARGET_DS4.</summary>
    public FeedbackFlags BaseFlags { get; set; }

    /// <summary>The kind of pad this slot has (or, in a dry run, would have).</summary>
    public PadKind Kind { get; set; }

    public SlotMetrics Metrics { get; } = new();

    // --- STATUS (§12). Receive thread, except SupportsStatus. ---------------------------------------------------

    /// <summary>The client sent STATUS: its FEEDBACK carries STATUS_ACK, and it understands COMMAND.</summary>
    public bool SupportsStatus => _supportsStatus;

    public ulong DeviceId { get; private set; }
    public byte Battery { get; private set; } = Protocol.UnknownBattery;
    public StatusFlags StatusFlags { get; private set; }
    public ushort RttTenthsMs { get; private set; } = Protocol.UnknownRtt;
    public byte RateHz { get; private set; }
    public OutputPreference OutputPreference { get; private set; }
    public ushort AppVersion { get; private set; }
    public long LastStatus { get; private set; }

    /// <summary>UTF-8 device and profile names from STATUS (the first <see cref="DeviceNameLength"/> / <see cref="ProfileNameLength"/> bytes).</summary>
    public byte[] DeviceName { get; } = new byte[Protocol.MaxStatusFieldBytes];
    public int DeviceNameLength { get; private set; }
    public byte[] ProfileName { get; } = new byte[Protocol.MaxStatusFieldBytes];
    public int ProfileNameLength { get; private set; }

    /// <summary>Copies a STATUS into the slot. Returns true when it was the client's first.</summary>
    public bool ApplyStatus(in StatusView status, long now)
    {
        bool first = !_supportsStatus;
        DeviceId = status.DeviceId;
        Battery = status.Battery;
        StatusFlags = status.Flags;
        RttTenthsMs = status.RttTenthsMs;
        RateHz = status.RateHz;
        OutputPreference = status.Output;
        AppVersion = status.AppVersion;
        LastStatus = now;
        status.DeviceName.CopyTo(DeviceName);
        DeviceNameLength = status.DeviceName.Length;
        status.ProfileName.CopyTo(ProfileName);
        ProfileNameLength = status.ProfileName.Length;
        _supportsStatus = true;
        return first;
    }

    /// <summary>
    /// The same phone now sends from <paramref name="address"/> (a new socket after a network change): the slot, and
    /// with it the virtual pad, moves there. The new socket restarts its sequence numbers (§3.3 rule 1).
    /// </summary>
    public void Rebind(SocketAddress address, long now)
    {
        Key = KeyOf(address);
        EndPoint = (IPEndPoint)new IPEndPoint(IPAddress.Any, 0).Create(address);
        _address = address;
        _hasAccepted = false;
        LastSeen = now;
        Metrics.OnPause();
    }

    public long LastSeen { get; set; }
    public long LastAccepted { get; private set; }
    public long NextFeedback { get; set; }

    /// <summary>The GAME datagram this client got last; the detector publishes a new array per change, so a reference compare spots it.</summary>
    public byte[]? GameSent { get; set; }
    public long NextGame { get; set; }

    /// <summary>--dry-run: when this client's next decoded packet may be logged (each phone has its own budget).</summary>
    public long NextDryRunLog { get; set; }

    // Desktop-app commands in progress (receive thread).
    public PadKind? ReplugTo { get; set; }
    public byte IdentifyToken { get; set; }
    public int IdentifyLeft { get; set; }
    public long NextIdentify { get; set; }
    public long RumbleTestUntil { get; set; }

    /// <summary>Sets the rumble motors, keeping the player LED. Returns true when anything changed.</summary>
    public bool SetRumble(byte largeMotor, byte smallMotor)
    {
        int old, next;
        do
        {
            old = Volatile.Read(ref _feedback);
            next = (old & ~0xFFFF) | largeMotor | smallMotor << 8;
        }
        while (Interlocked.CompareExchange(ref _feedback, next, old) != old);
        return next != old;
    }

    /// <summary>Takes over the STATUS of <paramref name="old"/>, the same phone's previous slot.</summary>
    public void CopyStatusFrom(PadSlot old)
    {
        if (!old.SupportsStatus) return;
        DeviceId = old.DeviceId;
        Battery = old.Battery;
        StatusFlags = old.StatusFlags;
        RttTenthsMs = old.RttTenthsMs;
        RateHz = old.RateHz;
        OutputPreference = old.OutputPreference;
        AppVersion = old.AppVersion;
        LastStatus = old.LastStatus;
        old.DeviceName.AsSpan(0, old.DeviceNameLength).CopyTo(DeviceName);
        DeviceNameLength = old.DeviceNameLength;
        old.ProfileName.AsSpan(0, old.ProfileNameLength).CopyTo(ProfileName);
        ProfileNameLength = old.ProfileNameLength;
        _supportsStatus = true;
    }

    /// <summary>Neutral state of §4: inputs were released after a silence, the next INPUT is accepted unconditionally.</summary>
    public bool TimedOut { get; private set; }

    public ushort LastSeq => (ushort)Volatile.Read(ref _lastSeq);
    public bool IsClosed => _closed;
    public SlotStats Stats;

    public static ulong KeyOf(SocketAddress address) => BinaryPrimitives.ReadUInt64LittleEndian(address.Buffer.Span);

    /// <summary>Applies §3.3; an accepted packet replaces the whole pending state.</summary>
    public SequenceVerdict Accept(ushort seq, in PadState state, long now, out bool resumed)
    {
        var verdict = Protocol.CheckSequence(_hasAccepted, TimedOut, LastSeq, seq);
        resumed = false;
        switch (verdict)
        {
            case SequenceVerdict.Duplicate: Stats.Duplicates++; return verdict;
            case SequenceVerdict.Stale: Stats.Stale++; return verdict;
        }

        resumed = TimedOut;
        _hasAccepted = true;
        TimedOut = false;
        LastAccepted = now;
        Volatile.Write(ref _lastSeq, seq);
        _pending = state;
        _dirty = true;
        Stats.Accepted++;
        Metrics.OnAccepted(now, seq);
        return verdict;
    }

    /// <summary>Enters the neutral state: the next flush submits an all-zero report (unless the pad already has one).</summary>
    public void Neutralize()
    {
        TimedOut = true;
        _pending = default;
        _dirty = true;
        Metrics.OnPause();
    }

    /// <summary>The state last submitted to the pad (what the game sees).</summary>
    public PadState Submitted => _submitted;

    /// <summary>
    /// Returns the newest accepted state when it differs from what the pad last received. Called once after a burst
    /// has been drained, so the driver only ever sees the latest snapshot.
    /// </summary>
    public bool TryTakeChange(out PadState state)
    {
        state = _pending;
        if (!_dirty) return false;
        _dirty = false;
        // The first state always goes out, even an all-zero one: until a fresh ViGEm Xbox 360 pad gets its first report,
        // XInput reads its sticks slightly off centre.
        if (_hasSubmitted && state == _submitted) return false;
        _hasSubmitted = true;
        _submitted = state;
        return true;
    }

    /// <summary>Driver thread: stores rumble/LED, returning true when anything changed.</summary>
    public bool SetFeedback(byte largeMotor, byte smallMotor, byte ledNumber)
    {
        int packed = largeMotor | smallMotor << 8 | ledNumber << 16 | FeedbackKnown;
        return Interlocked.Exchange(ref _feedback, packed) != packed;
    }

    /// <summary>XInput user index 0..3, or <see cref="Protocol.UnknownPlayer"/> until Windows has assigned one.</summary>
    public byte PlayerIndex => PlayerOf(Volatile.Read(ref _feedback));

    /// <summary>Receive thread: the player index last announced in the log.</summary>
    public byte LoggedPlayerIndex { get; set; } = Protocol.UnknownPlayer;

    /// <summary>Any thread: serialises this client's FEEDBACK.</summary>
    public void WriteFeedback(Span<byte> destination)
    {
        int f = Volatile.Read(ref _feedback);
        var flags = BaseFlags | (_supportsStatus ? FeedbackFlags.StatusAck : FeedbackFlags.None);
        Protocol.WriteFeedback(destination, (byte)f, (byte)(f >> 8), PlayerOf(f), flags, LastSeq);
    }

    /// <summary>Rumble motors the game set last (0..255 each), for status displays.</summary>
    public (byte Large, byte Small) Rumble
    {
        get
        {
            int f = Volatile.Read(ref _feedback);
            return ((byte)f, (byte)(f >> 8));
        }
    }

    // The wrapper's IXbox360Controller.UserIndex is nothing but the LedNumber of the last notification, so taking it
    // from here avoids its exception while unknown. 0..3 is the XInput user index; anything else means "not assigned".
    private static byte PlayerOf(int feedback)
    {
        byte led = (byte)(feedback >> 16);
        return (feedback & FeedbackKnown) != 0 && led <= 3 ? led : Protocol.UnknownPlayer;
    }

    public void Close() => _closed = true;

    public override string ToString() => $"{EndPoint} (controller {Index + 1})";
}

/// <summary>Per-client counters for --verbose.</summary>
internal struct SlotStats
{
    public long Datagrams;
    public long Accepted;
    public long Duplicates;
    public long Stale;
    public long Invalid;
    public long Submits;
    public long DatagramsAtLastReport;
}
