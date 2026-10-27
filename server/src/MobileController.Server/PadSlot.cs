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
    private PadState _pending;
    private PadState _submitted;

    // Shared with the driver's notification thread.
    private int _lastSeq;
    private int _feedback; // largeMotor | smallMotor << 8 | led << 16 | FeedbackKnown
    private volatile bool _closed;

    public PadSlot(int index, SocketAddress address, long now)
    {
        Index = index;
        Address = address;
        Key = KeyOf(address);
        EndPoint = (IPEndPoint)new IPEndPoint(IPAddress.Any, 0).Create(address);
        LastSeen = now;
        LastAccepted = now;
        NextFeedback = now;
    }

    public int Index { get; }

    /// <summary>Family, port and IPv4 address of the sockaddr_in: enough to identify an endpoint in one compare.</summary>
    public ulong Key { get; }

    /// <summary>Private copy of the client's address, used for every reply.</summary>
    public SocketAddress Address { get; }

    public IPEndPoint EndPoint { get; }
    public IVirtualPad? Pad { get; set; }

    public long LastSeen { get; set; }
    public long LastAccepted { get; private set; }
    public long NextFeedback { get; set; }

    /// <summary>The GAME datagram this client got last; the detector publishes a new array per change, so a reference compare spots it.</summary>
    public byte[]? GameSent { get; set; }
    public long NextGame { get; set; }

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
        return verdict;
    }

    /// <summary>Enters the neutral state: the next flush submits an all-zero report (unless the pad already has one).</summary>
    public void Neutralize()
    {
        TimedOut = true;
        _pending = default;
        _dirty = true;
    }

    /// <summary>
    /// Returns the newest accepted state when it differs from what the pad last received. Called once after a burst
    /// has been drained, so the driver only ever sees the latest snapshot.
    /// </summary>
    public bool TryTakeChange(out PadState state)
    {
        state = _pending;
        if (!_dirty) return false;
        _dirty = false;
        if (state == _submitted) return false;
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
    public void WriteFeedback(Span<byte> destination, FeedbackFlags flags)
    {
        int f = Volatile.Read(ref _feedback);
        Protocol.WriteFeedback(destination, (byte)f, (byte)(f >> 8), PlayerOf(f), flags, LastSeq);
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
