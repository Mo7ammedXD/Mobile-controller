using System.Runtime.CompilerServices;

namespace MobileController.Server.Control;

/// <summary>32 bytes inline: a STATUS name inside a struct, so snapshots hold no references.</summary>
[InlineArray(Protocol.MaxStatusFieldBytes)]
internal struct Name32
{
    private byte _first;
}

/// <summary>Everything the desktop app shows about one slot, copied out of the receive thread's state.</summary>
internal struct PadSnapshot
{
    public bool Present;
    public int Index;

    /// <summary><see cref="PadSlot.Key"/>: family, port and IPv4 address of the phone's endpoint.</summary>
    public ulong EndpointKey;
    public long Connected;
    public bool SupportsStatus;
    public ulong DeviceId;
    public byte Battery;
    public StatusFlags StatusFlags;
    public ushort RttTenthsMs;
    public byte RateHz;
    public OutputPreference Preference;
    public ushort AppVersion;
    public Name32 DeviceName;
    public byte DeviceNameLength;
    public Name32 ProfileName;
    public byte ProfileNameLength;
    public PadKind Kind;
    public byte Player;
    public byte LargeMotor;
    public byte SmallMotor;
    public bool TimedOut;
    public double InputsPerSecond, LossPercent, JitterMs, GapP50Ms, GapP95Ms, GapP99Ms, GapMaxMs;
    public long Accepted, Duplicates, Stale, Invalid, Submits;
}

/// <summary>Server-wide numbers of a snapshot.</summary>
internal struct ServerSnapshot
{
    public long Taken;
    public long Datagrams, InvalidFromUnknown, Blocked;
    public int PadsInUse;
    public double PassesPerSecond, MaxPassMs;
}

/// <summary>
/// Hands status from the receive thread to the control thread without a lock and without allocating: the receive
/// thread writes into preallocated structs between two increments of a version counter (odd while writing); the reader
/// copies them out and retries if the version moved meanwhile (a sequence lock). The writer never waits, which is the
/// point: the receive thread must not block on the desktop app.
/// </summary>
internal sealed class StatusPublisher(int maxPads)
{
    private readonly PadSnapshot[] _pads = new PadSnapshot[maxPads];
    private readonly PadState[] _live = new PadState[maxPads];
    private readonly byte[] _liveMotors = new byte[maxPads * 2];
    private readonly bool[] _livePresent = new bool[maxPads];
    private ServerSnapshot _server;
    private int _version;
    private int _liveVersion;

    public int MaxPads => _pads.Length;

    /// <summary>Changes with every published snapshot (even numbers only once complete).</summary>
    public int Version => Volatile.Read(ref _version);
    public int LiveVersion => Volatile.Read(ref _liveVersion);

    // --- Receive thread --------------------------------------------------------------------------------------

    public void Publish(PadSlot?[] slots, in ServerSnapshot server)
    {
        Volatile.Write(ref _version, _version + 1);
        Interlocked.MemoryBarrier();
        for (int i = 0; i < _pads.Length; i++)
        {
            ref var p = ref _pads[i];
            var slot = i < slots.Length ? slots[i] : null;
            p.Present = slot is not null;
            if (slot is null) continue;
            Fill(ref p, slot);
        }
        _server = server;
        Interlocked.MemoryBarrier();
        Volatile.Write(ref _version, _version + 1);
    }

    public void PublishLive(PadSlot?[] slots)
    {
        Volatile.Write(ref _liveVersion, _liveVersion + 1);
        Interlocked.MemoryBarrier();
        for (int i = 0; i < _live.Length; i++)
        {
            var slot = i < slots.Length ? slots[i] : null;
            _livePresent[i] = slot is not null;
            if (slot is null) continue;
            _live[i] = slot.Submitted;
            var (large, small) = slot.Rumble;
            _liveMotors[2 * i] = large;
            _liveMotors[2 * i + 1] = small;
        }
        Interlocked.MemoryBarrier();
        Volatile.Write(ref _liveVersion, _liveVersion + 1);
    }

    private static void Fill(ref PadSnapshot p, PadSlot slot)
    {
        p.Index = slot.Index;
        p.EndpointKey = slot.Key;
        p.Connected = slot.Connected;
        p.SupportsStatus = slot.SupportsStatus;
        p.DeviceId = slot.DeviceId;
        p.Battery = slot.Battery;
        p.StatusFlags = slot.StatusFlags;
        p.RttTenthsMs = slot.RttTenthsMs;
        p.RateHz = slot.RateHz;
        p.Preference = slot.OutputPreference;
        p.AppVersion = slot.AppVersion;
        slot.DeviceName.AsSpan(0, slot.DeviceNameLength).CopyTo(p.DeviceName);
        p.DeviceNameLength = (byte)slot.DeviceNameLength;
        slot.ProfileName.AsSpan(0, slot.ProfileNameLength).CopyTo(p.ProfileName);
        p.ProfileNameLength = (byte)slot.ProfileNameLength;
        p.Kind = slot.Kind;
        p.Player = slot.PlayerIndex;
        (p.LargeMotor, p.SmallMotor) = slot.Rumble;
        p.TimedOut = slot.TimedOut;
        var m = slot.Metrics;
        p.InputsPerSecond = m.InputsPerSecond;
        p.LossPercent = m.LossPercent;
        p.JitterMs = m.JitterMs;
        p.GapP50Ms = m.GapP50Ms;
        p.GapP95Ms = m.GapP95Ms;
        p.GapP99Ms = m.GapP99Ms;
        p.GapMaxMs = m.GapMaxMs;
        p.Accepted = slot.Stats.Accepted;
        p.Duplicates = slot.Stats.Duplicates;
        p.Stale = slot.Stats.Stale;
        p.Invalid = slot.Stats.Invalid;
        p.Submits = slot.Stats.Submits;
    }

    // --- Any other thread ------------------------------------------------------------------------------------

    /// <summary>Copies the latest complete snapshot into <paramref name="pads"/> (at least <see cref="MaxPads"/> long).</summary>
    public int Read(PadSnapshot[] pads, out ServerSnapshot server)
    {
        var spin = new SpinWait();
        while (true)
        {
            int before = Volatile.Read(ref _version);
            if ((before & 1) == 0)
            {
                Interlocked.MemoryBarrier();
                Array.Copy(_pads, pads, _pads.Length);
                server = _server;
                Interlocked.MemoryBarrier();
                if (Volatile.Read(ref _version) == before) return before;
            }
            spin.SpinOnce();
        }
    }

    /// <summary>Copies the latest live state: per slot whether it is in use, its report, and its two motors.</summary>
    public int ReadLive(bool[] present, PadState[] states, byte[] motors)
    {
        var spin = new SpinWait();
        while (true)
        {
            int before = Volatile.Read(ref _liveVersion);
            if ((before & 1) == 0)
            {
                Interlocked.MemoryBarrier();
                Array.Copy(_livePresent, present, _livePresent.Length);
                Array.Copy(_live, states, _live.Length);
                Array.Copy(_liveMotors, motors, _liveMotors.Length);
                Interlocked.MemoryBarrier();
                if (Volatile.Read(ref _liveVersion) == before) return before;
            }
            spin.SpinOnce();
        }
    }
}
