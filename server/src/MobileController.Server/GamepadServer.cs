using System.Buffers.Binary;
using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using MobileController.Server.Control;

namespace MobileController.Server;

/// <summary>
/// UDP receive loop of PROTOCOL.md. Runs on one dedicated thread; in steady state (known clients sending INPUT,
/// PING, heartbeats going out) it performs no heap allocation, so the GC never has a reason to pause it.
/// Allocation happens only on connect/disconnect, in logging and in error paths.
/// </summary>
internal sealed class GamepadServer : IDisposable
{
    /// <summary>Upper bound on how long housekeeping (timeouts, heartbeats, stop flag) can be delayed.</summary>
    public const int PollMicroseconds = 10_000;

    /// <summary>Datagrams handled per wake-up before timers get a turn, so a flood cannot starve housekeeping.</summary>
    private const int MaxBurst = 256;

    private const int SioUdpConnReset = unchecked((int)0x9800000C);
    private const int SioUdpNetReset = unchecked((int)0x9800000F);

    /// <summary>Endpoints remembered as "plug-in failed, wait before retrying"; the oldest entry is replaced first.</summary>
    private const int BackoffSlots = 16;

    /// <summary>After this many plug-ins in a row fail, the driver is in trouble: pause every plug-in for a while.</summary>
    private const int BreakerFailures = 3;

    private static readonly long TicksPerMs = Stopwatch.Frequency / 1000;
    private static readonly long DryRunLogTicks = Stopwatch.Frequency / 10;
    private static readonly long RejectLogTicks = 5 * Stopwatch.Frequency;
    private static readonly long BreakerTicks = 2 * Stopwatch.Frequency;

    /// <summary>§4: a slot does not move to a new endpoint while its old one sent INPUT this recently.</summary>
    private static readonly long MoveGuardTicks = Stopwatch.Frequency / 4;

    /// <summary>Two live endpoints with one deviceId (a cloned install): after this long the newcomer gets its own slot.</summary>
    private static readonly long SharedIdTicks = Stopwatch.Frequency;

    // Control commands from the desktop app.
    private static readonly long IdentifyRepeatTicks = Stopwatch.Frequency / 20;
    private static readonly long DisconnectHoldTicks = 30 * Stopwatch.Frequency;
    private static readonly long RumbleTestTicks = Stopwatch.Frequency / 2;
    private const int OverrideSlots = 16;

    private readonly Options _options;
    private readonly IVirtualPadFactory? _padFactory;
    private readonly Logger _log;
    private readonly Socket _socket;

    // Large enough for any UDP payload, so an oversized datagram is simply dropped by validation instead of
    // surfacing as WSAEMSGSIZE.
    private readonly byte[] _receiveBuffer = GC.AllocateUninitializedArray<byte>(65_536, pinned: true);
    private readonly SocketAddress _from = new(AddressFamily.InterNetwork);
    private readonly PadSlot?[] _slots;
    private readonly uint[] _allowed;
    private readonly byte[] _nameUtf8;
    private readonly FeedbackFlags _slotFlags;
    private readonly GameDetector? _game;
    private readonly PendingEndpoints _pending = new();
    private readonly long _neutralTicks, _disconnectTicks, _feedbackTicks, _serverFullTicks, _statsTicks, _connectRetryTicks, _gameTicks;
    private readonly long _statusWaitTicks, _metricsTicks;
    private long _nextMetrics, _lastMetrics;

    // Control plane: snapshots for the desktop app, its commands, and the receive loop's own health.
    private readonly long _publishTicks, _liveTicks;
    private long _nextPublish, _lastPublish, _nextLive, _passes, _maxPassTicks;
    private volatile bool _publishEnabled, _liveRequested;
    private byte _identifyToken;

    // Pad kinds the desktop app chose per phone (deviceId), ahead of the settings file's device rules: 0 = the phone's
    // own choice, otherwise an OutputPreference value.
    private readonly ulong[] _overrideIds = new ulong[OverrideSlots];
    private readonly byte[] _overrideKinds = new byte[OverrideSlots];
    private int _overrideNext;

    // Endpoints already warned about a protocol version mismatch (§2: log once per endpoint, not per packet).
    private readonly ulong[] _versionWarned = new ulong[32];
    private int _versionWarnedNext;

    // A refused plug-in holds back only that endpoint, so one phone hitting a driver hiccup never locks others out.
    private readonly ulong[] _backoffKeys = new ulong[BackoffSlots];
    private readonly long[] _backoffUntil = new long[BackoffSlots];
    private int _backoffNext, _failuresInARow;
    private long _plugInsPausedUntil;

    private long _nextServerFullReply, _nextRejectLog, _nextUnknownDryRunLog, _nextStats, _lastStats;
    private long _datagrams, _invalidFromUnknown, _filtered;
    private int _padsInUse;
    private Thread? _thread;
    private volatile bool _stopRequested;
    private int _released;

    /// <param name="padFactory">Source of virtual pads; <c>null</c> runs in dry-run mode.</param>
    /// <param name="foregroundApp">Foreground process name source for GAME; <c>null</c> asks Windows.</param>
    public GamepadServer(Options options, IVirtualPadFactory? padFactory, Logger log, Func<string?>? foregroundApp = null)
    {
        _options = options;
        _padFactory = padFactory;
        _log = log;
        _slots = new PadSlot?[options.MaxPads];
        _allowed = options.Allow.Select(ip => BinaryPrimitives.ReadUInt32LittleEndian(ip.GetAddressBytes())).ToArray();
        _slotFlags = padFactory is null ? FeedbackFlags.DryRun : FeedbackFlags.PadAttached;

        Span<byte> name = stackalloc byte[Protocol.MaxNameBytes];
        _nameUtf8 = name[..Protocol.EncodeName(options.ServerName, name)].ToArray();

        _neutralTicks = options.NeutralTimeoutMs * TicksPerMs;
        _disconnectTicks = options.DisconnectTimeoutMs * TicksPerMs;
        _feedbackTicks = options.FeedbackIntervalMs * TicksPerMs;
        _serverFullTicks = options.ServerFullIntervalMs * TicksPerMs;
        _statsTicks = options.StatsIntervalMs * TicksPerMs;
        _connectRetryTicks = options.ConnectRetryMs * TicksPerMs;
        _gameTicks = options.GameIntervalMs * TicksPerMs;
        _statusWaitTicks = options.StatusWaitMs * TicksPerMs;
        _metricsTicks = options.MetricsIntervalMs * TicksPerMs;
        _lastStats = Stopwatch.GetTimestamp();
        _nextStats = _lastStats + _statsTicks;
        _lastMetrics = _lastStats;
        _nextMetrics = _lastStats + _metricsTicks;
        _publishTicks = options.PublishIntervalMs * TicksPerMs;
        _liveTicks = options.LiveIntervalMs * TicksPerMs;
        _lastPublish = _lastStats;
        Publisher = new StatusPublisher(options.MaxPads);

        _socket = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
        try
        {
            // Without these, one ICMP "port unreachable" (a FEEDBACK reaching a phone app that just closed) makes the
            // next receive fail with WSAECONNRESET.
            _socket.IOControl(SioUdpConnReset, new byte[4], null);
            _socket.IOControl(SioUdpNetReset, new byte[4], null);
            // No other process may bind the same port with SO_REUSEADDR and steal our datagrams.
            _socket.ExclusiveAddressUse = true;
            _socket.Bind(new IPEndPoint(options.BindAddress, options.Port));
            // Only Poll may wait: a receive that finds nothing must return instead of blocking housekeeping.
            _socket.Blocking = false;
        }
        catch
        {
            _socket.Dispose();
            throw;
        }
        LocalEndPoint = (IPEndPoint)_socket.LocalEndPoint!;

        // --no-game-detect means the foreground window is never even looked at (privacy), not merely left unsent.
        if (!options.NoGameDetect)
            _game = new GameDetector(foregroundApp ?? Native.GetForegroundProcessImagePath, options.GamePollMs, log);
    }

    public IPEndPoint LocalEndPoint { get; }
    public int PadsInUse => _padsInUse;

    /// <summary>Null with --no-game-detect. Not started until <see cref="Start"/>; tests may <see cref="GameDetector.Poll"/> it.</summary>
    internal GameDetector? GameDetector => _game;

    /// <summary>The slot table; owned by the receive thread (tests read it between pumps).</summary>
    internal IReadOnlyList<PadSlot?> Slots => _slots;

    /// <summary>Snapshots for the control pipe, written by the receive thread while <see cref="PublishEnabled"/>.</summary>
    internal StatusPublisher Publisher { get; }

    /// <summary>Commands from the control pipe (one producer) and their replies (one consumer).</summary>
    internal SpscRing<ControlCommand> Commands { get; } = new(64);
    internal SpscRing<CommandReply> Replies { get; } = new(64);

    /// <summary>A desktop app is attached: publish snapshots. Off, the receive thread does no control-plane work.</summary>
    internal bool PublishEnabled
    {
        get => _publishEnabled;
        set
        {
            _publishEnabled = value;
            if (!value) _liveRequested = false;
        }
    }

    public long InvalidFromUnknown => Volatile.Read(ref _invalidFromUnknown);

    /// <summary>Valid datagrams received (any type, any sender).</summary>
    public long DatagramsReceived => Volatile.Read(ref _datagrams);

    /// <summary>True when the receive loop died from an unexpected exception.</summary>
    public bool Faulted { get; private set; }

    public void Start()
    {
        if (_thread is not null) throw new InvalidOperationException("Already started.");
        _game?.Start();
        _thread = new Thread(Run) { Name = "UDP receive", IsBackground = true, Priority = ThreadPriority.Highest };
        _thread.Start();
    }

    /// <summary>Asks the loop to stop; it notices within one poll interval. Safe from any thread, any number of times.</summary>
    public void RequestStop() => _stopRequested = true;

    public bool WaitForExit(TimeSpan timeout) => _thread is null || _thread.Join(timeout);

    public void WaitForExit() => _thread?.Join();

    public void Dispose()
    {
        RequestStop();
        if (_thread is not null && _thread != Thread.CurrentThread) _thread.Join();
        ReleaseAll();
        _game?.Dispose();
        _socket.Dispose();
    }

    private void Run()
    {
        try
        {
            while (!_stopRequested) PumpOnce(PollMicroseconds);
        }
        catch (Exception e)
        {
            Faulted = true;
            _log.Error($"Receive loop stopped unexpectedly: {e}");
        }
        finally
        {
            ReleaseAll();
        }
    }

    /// <summary>
    /// One loop iteration: wait up to <paramref name="pollMicroseconds"/> for data, drain the queue, submit changed
    /// pads, then run timers. Public so tests can drive the exact production path on their own thread.
    /// </summary>
    public void PumpOnce(int pollMicroseconds)
    {
        bool readable = _socket.Poll(pollMicroseconds, SelectMode.SelectRead);
        long now = Stopwatch.GetTimestamp();
        // The loop's health for the desktop app: passes per second (this one included, as it may publish) and the
        // slowest pass, excluding the wait.
        _passes++;
        if (readable) Drain(now);
        Housekeep(now);
        long work = Stopwatch.GetTimestamp() - now;
        if (work > _maxPassTicks) _maxPassTicks = work;
    }

    private void Drain(long now)
    {
        int budget = MaxBurst;
        do
        {
            int length;
            try
            {
                // The SocketAddress overload fills a caller-owned address; the EndPoint overloads allocate per call.
                length = _socket.ReceiveFrom(_receiveBuffer, SocketFlags.None, _from);
            }
            catch (SocketException e) when (e.SocketErrorCode == SocketError.WouldBlock)
            {
                break;
            }
            catch (SocketException e) when (e.SocketErrorCode is SocketError.ConnectionReset or SocketError.NetworkReset)
            {
                continue; // Stale ICMP error from an earlier send; the datagram queue itself is intact.
            }
            Handle(new ReadOnlySpan<byte>(_receiveBuffer, 0, length), now);
        }
        while (--budget > 0 && _socket.Available > 0);

        // Submit once per burst so the driver call carries only the newest state.
        foreach (var slot in _slots)
        {
            if (slot is not null) Flush(slot);
        }
    }

    private void Handle(ReadOnlySpan<byte> datagram, long now)
    {
        ulong key = PadSlot.KeyOf(_from);
        if (_allowed.Length != 0 && Array.IndexOf(_allowed, (uint)(key >> 32)) < 0)
        {
            _filtered++;
            return;
        }

        var slot = Find(key);
        var status = Protocol.Validate(datagram, atServer: true, out var type);
        if (status != PacketStatus.Ok)
        {
            if (slot is not null) slot.Stats.Invalid++;
            else _invalidFromUnknown++;
            if (status == PacketStatus.BadVersion) WarnVersionOnce(key, datagram[1] >> 4);
            return;
        }

        Volatile.Write(ref _datagrams, _datagrams + 1);
        if (slot is not null)
        {
            slot.LastSeen = now;
            slot.Stats.Datagrams++;
        }

        switch (type)
        {
            case PacketType.Input:
                OnInput(slot, datagram, now);
                break;
            case PacketType.Ping:
                Span<byte> pong = stackalloc byte[Protocol.PongSize];
                Protocol.WritePong(pong, datagram);
                TrySend(pong, _from);
                LogDryRun(slot, now, "PING");
                break;
            case PacketType.Bye:
                LogDryRun(slot, now, "BYE");
                if (slot is not null) Release(slot, "bye");
                break;
            case PacketType.Discover:
                Span<byte> reply = stackalloc byte[Protocol.MaxDatagramSize];
                int length = Protocol.WriteDiscoverReply(reply, BinaryPrimitives.ReadUInt16LittleEndian(datagram[2..]),
                    (byte)_padsInUse, (byte)_slots.Length, _nameUtf8);
                TrySend(reply[..length], _from);
                LogDryRun(slot, now, "DISCOVER");
                break;
            case PacketType.Status:
                OnStatus(slot, datagram, key, now);
                LogDryRun(slot, now, "STATUS");
                break;
        }
    }

    private void OnStatus(PadSlot? slot, ReadOnlySpan<byte> datagram, ulong key, long now)
    {
        var status = new StatusView(datagram);
        if (slot is not null)
        {
            ApplyStatus(slot, status, now);
            var kind = ResolveKind(status);
            if (kind == slot.Kind) return;
            if (_padFactory is null)
            {
                slot.Kind = kind;
                return;
            }
            // §4: the phone (or its device rule) wants the other kind of pad. A pad's kind is fixed, so it is unplugged
            // and the next INPUT plugs the right one at once (the STATUS is kept for it).
            Release(slot, $"switching to {Describe(kind)}");
            _pending.SetStatus(_pending.Get(key, now), datagram);
            return;
        }
        // No slot: a phone about to send its first INPUT, or one whose socket changed. Moving its old slot right away
        // makes FEEDBACK (and rumble) follow the phone at once.
        int entry = _pending.Get(key, now);
        _pending.SetStatus(entry, datagram);
        var owner = FindDevice(status.DeviceId);
        if (owner is not null && now - owner.LastAccepted >= MoveGuardTicks)
        {
            Move(owner, now);
            _pending.Remove(entry);
            ApplyStatus(owner, status, now);
        }
    }

    private void ApplyStatus(PadSlot slot, in StatusView status, long now)
    {
        if (!slot.ApplyStatus(status, now)) return;
        // The first STATUS: acknowledge it at once instead of with the next heartbeat.
        _log.Info($"{slot} is \"{System.Text.Encoding.UTF8.GetString(status.DeviceName)}\" (device {status.DeviceId:X16})");
        SendFeedback(slot);
    }

    /// <summary>Moves <paramref name="slot"/> (and its pad) to the endpoint in <c>_from</c>: the same phone on a new socket.</summary>
    private void Move(PadSlot slot, long now)
    {
        var old = slot.EndPoint;
        var address = new SocketAddress(AddressFamily.InterNetwork, _from.Size);
        _from.Buffer.Span[.._from.Size].CopyTo(address.Buffer.Span);
        slot.Rebind(address, now);
        // Tell the phone at once (this housekeeping pass) that it is still connected, and with STATUS_ACK.
        slot.NextFeedback = now;
        _log.Info($"{old} moved to {slot.EndPoint} (same phone) -> controller {slot.Index + 1} kept");
    }

    private PadKind ResolveKind(in StatusView status) => ResolveKind(status.DeviceId, status.Output);

    /// <summary>§4 output type: the desktop app's choice, else a device rule, else the phone's preference, else the default.</summary>
    private PadKind ResolveKind(ulong deviceId, OutputPreference preference)
    {
        int o = deviceId == 0 ? -1 : Array.IndexOf(_overrideIds, deviceId);
        if (o >= 0)
        {
            // An override of 0 means "the phone decides", even over a device rule the settings file still has.
            if (_overrideKinds[o] != 0) preference = (OutputPreference)_overrideKinds[o];
        }
        else if (_options.Devices.TryGetValue(deviceId, out var rule) && rule.Output is PadKind ruled)
        {
            return ruled;
        }
        return preference switch
        {
            OutputPreference.DualShock4 => PadKind.DualShock4,
            OutputPreference.Xbox360 => PadKind.Xbox360,
            _ => _options.DefaultOutput,
        };
    }

    /// <summary>Unplugs <paramref name="old"/> and plugs a <paramref name="kind"/> pad for the same phone (the sender of <c>_from</c>).</summary>
    private PadSlot? Replug(PadSlot old, PadKind kind, long now)
    {
        Release(old, $"switching to {Describe(kind)}");
        var slot = TryConnect(old.Index, now, kind);
        if (slot is null) return null;
        slot.CopyStatusFrom(old);
        slot.Stats.Datagrams++;
        return slot;
    }

    // --- Control commands (from the desktop app, through the control pipe) ------------------------------------

    private void DrainCommands(long now)
    {
        while (Commands.TryDequeue(out var command)) Replies.TryEnqueue(new CommandReply(command.Id, Execute(command, now)));
    }

    private CommandError Execute(in ControlCommand c, long now)
    {
        if (c.Op == CommandOp.Live)
        {
            _liveRequested = c.Value != 0;
            return CommandError.None;
        }
        var slot = (uint)c.Index < (uint)_slots.Length ? _slots[c.Index] : null;
        if (slot is null) return CommandError.NoSuchSlot;
        switch (c.Op)
        {
            case CommandOp.Identify:
                if (!slot.SupportsStatus) return CommandError.NeedsStatus;
                slot.IdentifyToken = ++_identifyToken;
                slot.IdentifyLeft = 3;
                slot.NextIdentify = now;
                return CommandError.None;
            case CommandOp.Disconnect:
                // The phone keeps sending; hold its endpoint off for a while, or it would just get a new pad.
                ulong key = slot.Key;
                Release(slot, "disconnected from the desktop app");
                BackOff(key, now + DisconnectHoldTicks);
                return CommandError.None;
            case CommandOp.RumbleTest:
                if (slot.SetRumble(RumbleTestStrength, RumbleTestStrength)) SendFeedback(slot);
                slot.RumbleTestUntil = now + RumbleTestTicks;
                return CommandError.None;
            case CommandOp.Output:
                if (!slot.SupportsStatus) return CommandError.NeedsStatus;
                SetOverride(slot.DeviceId, (byte)c.Value);
                var kind = ResolveKind(slot.DeviceId, slot.OutputPreference);
                if (kind != slot.Kind)
                {
                    if (_padFactory is null) slot.Kind = kind;
                    else slot.ReplugTo = kind;
                }
                return CommandError.None;
            default:
                return CommandError.None;
        }
    }

    private const byte RumbleTestStrength = 200;

    private void SetOverride(ulong deviceId, byte kind)
    {
        int i = Array.IndexOf(_overrideIds, deviceId);
        if (i < 0)
        {
            i = _overrideNext;
            _overrideNext = (_overrideNext + 1) % OverrideSlots;
            _overrideIds[i] = deviceId;
        }
        _overrideKinds[i] = kind;
    }

    /// <summary>Per-slot command work due at <paramref name="now"/>: IDENTIFY repetitions and the end of a test rumble.</summary>
    private void RunSlotCommands(PadSlot slot, long now)
    {
        if (slot.IdentifyLeft > 0 && now >= slot.NextIdentify)
        {
            slot.IdentifyLeft--;
            slot.NextIdentify = now + IdentifyRepeatTicks;
            Span<byte> command = stackalloc byte[Protocol.CommandHeaderSize + 2];
            Span<byte> args = [slot.IdentifyToken, 0];
            Protocol.WriteCommand(command, Protocol.CommandIdentify, args);
            TrySend(command, slot.Address);
        }
        if (slot.RumbleTestUntil != 0 && now >= slot.RumbleTestUntil)
        {
            slot.RumbleTestUntil = 0;
            if (slot.SetRumble(0, 0)) SendFeedback(slot);
        }
    }

    private void Publish(long now)
    {
        double seconds = (double)(now - _lastPublish) / Stopwatch.Frequency;
        _lastPublish = now;
        _nextPublish = now + _publishTicks;
        var server = new ServerSnapshot
        {
            Taken = now,
            Datagrams = _datagrams,
            InvalidFromUnknown = _invalidFromUnknown,
            Blocked = _filtered,
            PadsInUse = _padsInUse,
            PassesPerSecond = seconds > 0 ? _passes / seconds : 0,
            MaxPassMs = _maxPassTicks * 1000.0 / Stopwatch.Frequency,
        };
        _passes = 0;
        _maxPassTicks = 0;
        Publisher.Publish(_slots, server);
    }

    public static string Describe(PadKind kind) => kind == PadKind.DualShock4 ? "DualShock 4" : "Xbox 360 controller";

    private PadSlot? FindDevice(ulong deviceId)
    {
        foreach (var slot in _slots)
        {
            if (slot is not null && slot.SupportsStatus && slot.DeviceId == deviceId) return slot;
        }
        return null;
    }

    private void OnInput(PadSlot? slot, ReadOnlySpan<byte> datagram, long now)
    {
        var state = Protocol.ReadInput(datagram, out ushort seq);
        if (slot is null)
        {
            slot = AdmitNewEndpoint(now);
            if (slot is null) return;
        }
        else if (slot.ReplugTo is PadKind kind)
        {
            // The desktop app chose another kind of pad for this phone: swap it now that the phone is sending.
            slot = Replug(slot, kind, now);
            if (slot is null) return;
        }

        var verdict = slot.Accept(seq, state, now, out bool resumed);
        if (resumed) _log.Info($"{slot}: input resumed");
        if (_padFactory is null && now >= slot.NextDryRunLog)
        {
            slot.NextDryRunLog = now + DryRunLogTicks;
            _log.Info($"{slot} INPUT seq={seq} {state} -> {verdict}");
        }
    }

    /// <summary>
    /// The first INPUT of an endpoint without a slot (§4): wait briefly for its STATUS, move the slot of the same phone if
    /// it changed sockets, else plug a new pad. Returns null while the INPUT is to be dropped.
    /// </summary>
    private PadSlot? AdmitNewEndpoint(long now)
    {
        ulong key = PadSlot.KeyOf(_from);
        int entry = _pending.Get(key, now);
        long firstInput = _pending.MarkInput(entry, now);
        if (_pending.HasStatus(entry))
        {
            var status = new StatusView(_pending.Status(entry));
            var owner = FindDevice(status.DeviceId);
            if (owner is not null)
            {
                if (now - owner.LastAccepted >= MoveGuardTicks)
                {
                    Move(owner, now);
                    _pending.Remove(entry);
                    ApplyStatus(owner, status, now);
                    owner.Stats.Datagrams++;
                    return owner;
                }
                // The old socket may still be draining; two phones really sharing an id get separate slots after a while.
                if (now - firstInput < SharedIdTicks) return null;
                _log.Warn($"{EndPointOf(_from)} uses the device id of {owner} while that one is still active (a copied " +
                          "app install?); giving it a controller of its own");
            }
        }
        else if (now - firstInput < _statusWaitTicks)
        {
            // Give STATUS a moment, so the first pad plugged in is already the kind this phone wants.
            return null;
        }

        int free = Array.IndexOf(_slots, null);
        if (free < 0)
        {
            RejectFull(now);
            return null;
        }
        // A refused plug-in is logged and retried on a later packet; the phone gets no FEEDBACK meanwhile, so it
        // correctly shows "not connected" rather than "server full".
        var kind = _pending.HasStatus(entry) ? ResolveKind(new StatusView(_pending.Status(entry))) : _options.DefaultOutput;
        var slot = TryConnect(free, now, kind);
        if (slot is null) return null;
        slot.Stats.Datagrams++;
        if (_pending.HasStatus(entry)) ApplyStatus(slot, new StatusView(_pending.Status(entry)), now);
        _pending.Remove(entry);
        return slot;
    }

    private PadSlot? TryConnect(int index, long now, PadKind kind)
    {
        ulong key = PadSlot.KeyOf(_from);
        if (now < _plugInsPausedUntil || IsBackingOff(key, now)) return null;

        var address = new SocketAddress(AddressFamily.InterNetwork, _from.Size);
        _from.Buffer.Span[.._from.Size].CopyTo(address.Buffer.Span);
        var slot = new PadSlot(index, address, now) { Kind = kind, BaseFlags = _slotFlags };

        if (_padFactory is null)
        {
            _log.Info($"{slot.EndPoint} connected -> controller {index + 1} (dry run, no virtual pad)");
        }
        else
        {
            long started = Stopwatch.GetTimestamp();
            try
            {
                slot.Pad = CreatePad(_padFactory, slot, kind);
                if (kind == PadKind.DualShock4) slot.BaseFlags |= FeedbackFlags.TargetDs4;
            }
            catch (Exception e)
            {
                // Plugging in costs milliseconds; do not retry at packet rate while the driver keeps refusing.
                BackOff(key, now + _connectRetryTicks);
                string breaker = "";
                if (++_failuresInARow >= BreakerFailures)
                {
                    _failuresInARow = 0;
                    _plugInsPausedUntil = now + BreakerTicks;
                    breaker = $" ({BreakerFailures} failures in a row: pausing every plug-in for {BreakerTicks / TicksPerMs} ms)";
                }
                _log.Error($"{slot.EndPoint}: could not plug in a virtual controller, retrying in {_options.ConnectRetryMs} ms: " +
                           ViGEmPadFactory.Describe(e) + breaker);
                return null;
            }
            _failuresInARow = 0;
            _log.Info($"{slot.EndPoint} connected -> controller {index + 1} plugged in" +
                      (kind == PadKind.DualShock4 ? " as a DualShock 4" : "") +
                      $" ({Stopwatch.GetElapsedTime(started).TotalMilliseconds:F0} ms)");
        }

        _slots[index] = slot;
        _padsInUse++;
        return slot;
    }

    private bool IsBackingOff(ulong key, long now)
    {
        for (int i = 0; i < BackoffSlots; i++)
        {
            if (_backoffKeys[i] == key && now < _backoffUntil[i]) return true;
        }
        return false;
    }

    private void BackOff(ulong key, long until)
    {
        int i = Array.IndexOf(_backoffKeys, key);
        if (i < 0)
        {
            i = _backoffNext;
            _backoffNext = (_backoffNext + 1) % BackoffSlots;
            _backoffKeys[i] = key;
        }
        _backoffUntil[i] = until;
    }

    // A separate method so the closure is only allocated when a pad is really created, not on every rejected INPUT.
    private IVirtualPad CreatePad(IVirtualPadFactory factory, PadSlot slot, PadKind kind) =>
        factory.Create(kind, (large, small, led) => OnPadFeedback(slot, large, small, led));

    private void RejectFull(long now)
    {
        if (now < _nextServerFullReply) return;
        _nextServerFullReply = now + _serverFullTicks;

        Span<byte> feedback = stackalloc byte[Protocol.FeedbackSize];
        var flags = FeedbackFlags.ServerFull | (_padFactory is null ? FeedbackFlags.DryRun : FeedbackFlags.None);
        Protocol.WriteFeedback(feedback, 0, 0, Protocol.UnknownPlayer, flags, 0);
        TrySend(feedback, _from);

        if (now >= _nextRejectLog)
        {
            _nextRejectLog = now + RejectLogTicks;
            _log.Warn($"{EndPointOf(_from)} rejected: all {_slots.Length} controller slots are in use (see --max-pads)");
        }
    }

    private void Flush(PadSlot slot)
    {
        if (!slot.TryTakeChange(out var state) || slot.Pad is null) return;
        try
        {
            slot.Pad.Submit(in state);
            slot.Stats.Submits++;
        }
        catch (Exception e)
        {
            _log.Error($"{slot}: driver rejected the report: {ViGEmPadFactory.Describe(e)}");
            Release(slot, "driver error");
        }
    }

    private void Housekeep(long now)
    {
        // One volatile read per pass; the detector replaces the array on a change and never writes into a published one.
        byte[]? game = _game?.Packet;
        foreach (var slot in _slots)
        {
            if (slot is null) continue;
            if (now - slot.LastSeen >= _disconnectTicks)
            {
                Release(slot, "timeout");
                continue;
            }
            if (!slot.TimedOut && now - slot.LastAccepted >= _neutralTicks)
            {
                slot.Neutralize();
                Flush(slot);
                _log.Info($"{slot}: no input for {_options.NeutralTimeoutMs} ms, inputs released");
                if (slot.IsClosed) continue;
            }
            if (now >= slot.NextFeedback)
            {
                slot.NextFeedback = now + _feedbackTicks;
                SendFeedback(slot);
            }
            // A new slot (nothing sent yet), a new name (a different array) or the repetition for loss (§10). After the
            // first FEEDBACK, so the phone already counts as connected when it reads the name.
            if (game is not null && (!ReferenceEquals(game, slot.GameSent) || now >= slot.NextGame))
            {
                slot.GameSent = game;
                slot.NextGame = now + _gameTicks;
                TrySend(game, slot.Address);
            }
            AnnouncePlayer(slot);
            RunSlotCommands(slot, now);
        }

        if (_publishEnabled)
        {
            DrainCommands(now);
            if (now >= _nextPublish) Publish(now);
            if (_liveRequested && now >= _nextLive)
            {
                _nextLive = now + _liveTicks;
                Publisher.PublishLive(_slots);
            }
        }
        if (now >= _nextMetrics)
        {
            double seconds = (double)(now - _lastMetrics) / Stopwatch.Frequency;
            _lastMetrics = now;
            _nextMetrics = now + _metricsTicks;
            foreach (var slot in _slots) slot?.Metrics.Roll(seconds);
        }
        if (_options.Verbose && now >= _nextStats) LogStats(now);
    }

    /// <summary>Driver notification thread. Must not block or throw; only touches the captured slot.</summary>
    private void OnPadFeedback(PadSlot slot, byte largeMotor, byte smallMotor, byte ledNumber)
    {
        // Games call XInputSetState every frame; only a change is worth a datagram, the heartbeat repeats it.
        if (slot.SetFeedback(largeMotor, smallMotor, ledNumber) && !slot.IsClosed) SendFeedback(slot);
    }

    /// <summary>Tells the user which player the phone became (the slot number need not match: a real pad may be player 1).</summary>
    private void AnnouncePlayer(PadSlot slot)
    {
        byte player = slot.PlayerIndex;
        if (player == slot.LoggedPlayerIndex) return;
        slot.LoggedPlayerIndex = player;
        if (player != Protocol.UnknownPlayer) _log.Info($"{slot} is XInput player {player + 1}");
    }

    private void SendFeedback(PadSlot slot)
    {
        // stackalloc keeps this safe to call from the receive and driver threads concurrently.
        Span<byte> feedback = stackalloc byte[Protocol.FeedbackSize];
        slot.WriteFeedback(feedback);
        TrySend(feedback, slot.Address);
    }

    private void TrySend(ReadOnlySpan<byte> datagram, SocketAddress to)
    {
        try
        {
            _socket.SendTo(datagram, SocketFlags.None, to);
        }
        catch (SocketException)
        {
            // Unreachable phone or full send buffer: UDP is best effort and the next heartbeat retries.
        }
        catch (ObjectDisposedException)
        {
            // A driver callback raced with shutdown.
        }
    }

    private PadSlot? Find(ulong key)
    {
        foreach (var slot in _slots)
        {
            if (slot is not null && slot.Key == key) return slot;
        }
        return null;
    }

    private void Release(PadSlot slot, string reason)
    {
        slot.Close();
        _slots[slot.Index] = null;
        _padsInUse--;
        try
        {
            slot.Pad?.Dispose();
        }
        catch (Exception e)
        {
            _log.Error($"{slot}: unplugging failed: {ViGEmPadFactory.Describe(e)}");
        }
        _log.Info($"{slot.EndPoint} disconnected ({reason}) -> controller {slot.Index + 1} " +
                  (slot.Pad is null ? "slot freed" : "unplugged"));
    }

    private void ReleaseAll()
    {
        if (Interlocked.Exchange(ref _released, 1) != 0) return;
        foreach (var slot in _slots)
        {
            if (slot is not null) Release(slot, "shutdown");
        }
    }

    private void WarnVersionOnce(ulong key, int version)
    {
        if (Array.IndexOf(_versionWarned, key) >= 0) return;
        _versionWarned[_versionWarnedNext] = key;
        _versionWarnedNext = (_versionWarnedNext + 1) % _versionWarned.Length;
        _log.Warn($"{EndPointOf(_from)} speaks protocol version {version}, this server speaks version {Protocol.Version}; " +
                  "update the phone app or the server so both match");
    }

    private void LogDryRun(PadSlot? slot, long now, string what)
    {
        if (_padFactory is not null) return;
        // Each phone has its own budget, so a busy one cannot hide what the others send.
        if (slot is not null)
        {
            if (now < slot.NextDryRunLog) return;
            slot.NextDryRunLog = now + DryRunLogTicks;
        }
        else
        {
            if (now < _nextUnknownDryRunLog) return;
            _nextUnknownDryRunLog = now + DryRunLogTicks;
        }
        _log.Info($"{(object?)slot ?? EndPointOf(_from)} {what}");
    }

    private void LogStats(long now)
    {
        double seconds = (double)(now - _lastStats) / Stopwatch.Frequency;
        _lastStats = now;
        _nextStats = now + _statsTicks;
        foreach (var slot in _slots)
        {
            if (slot is null) continue;
            ref var s = ref slot.Stats;
            double rate = (s.Datagrams - s.DatagramsAtLastReport) / seconds;
            s.DatagramsAtLastReport = s.Datagrams;
            var m = slot.Metrics;
            string phone = slot.SupportsStatus
                ? $", battery {(slot.Battery == Protocol.UnknownBattery ? "?" : slot.Battery + " %")}" +
                  $", phone RTT {(slot.RttTenthsMs == Protocol.UnknownRtt ? "?" : (slot.RttTenthsMs / 10.0).ToString("F1") + " ms")}"
                : "";
            _log.Info($"[stats] {slot}: {rate:F0} pkt/s, accepted {s.Accepted}, dropped duplicate {s.Duplicates} / " +
                      $"stale {s.Stale}, invalid {s.Invalid}, submitted {s.Submits}, last seq {slot.LastSeq}; " +
                      $"gaps p50 {m.GapP50Ms:F1} / p99 {m.GapP99Ms:F1} / max {m.GapMaxMs:F1} ms, jitter {m.JitterMs:F1} ms, " +
                      $"loss {m.LossPercent:F1} %{phone}");
        }
        if (_invalidFromUnknown != 0 || _filtered != 0)
            _log.Info($"[stats] other senders: invalid {_invalidFromUnknown}, blocked by --allow {_filtered}");
    }

    private static IPEndPoint EndPointOf(SocketAddress address) => (IPEndPoint)new IPEndPoint(IPAddress.Any, 0).Create(address);
}
