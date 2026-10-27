using System.Buffers.Binary;
using System.Diagnostics;
using System.Net;
using System.Net.Sockets;

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

    private static readonly long TicksPerMs = Stopwatch.Frequency / 1000;
    private static readonly long DryRunLogTicks = Stopwatch.Frequency / 10;
    private static readonly long RejectLogTicks = 5 * Stopwatch.Frequency;

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
    private readonly long _neutralTicks, _disconnectTicks, _feedbackTicks, _serverFullTicks, _statsTicks, _connectRetryTicks, _gameTicks;

    // Endpoints already warned about a protocol version mismatch (§2: log once per endpoint, not per packet).
    private readonly ulong[] _versionWarned = new ulong[32];
    private int _versionWarnedNext;

    private long _nextServerFullReply, _nextRejectLog, _nextConnectAttempt, _nextDryRunLog, _nextStats, _lastStats;
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
        _lastStats = Stopwatch.GetTimestamp();
        _nextStats = _lastStats + _statsTicks;

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
        if (readable) Drain(now);
        Housekeep(now);
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
        }
    }

    private void OnInput(PadSlot? slot, ReadOnlySpan<byte> datagram, long now)
    {
        var state = Protocol.ReadInput(datagram, out ushort seq);
        if (slot is null)
        {
            int free = Array.IndexOf(_slots, null);
            if (free < 0)
            {
                RejectFull(now);
                return;
            }
            // A refused plug-in is logged and retried on a later packet; the phone gets no FEEDBACK meanwhile, so it
            // correctly shows "not connected" rather than "server full".
            slot = TryConnect(free, now);
            if (slot is null) return;
            slot.Stats.Datagrams++;
        }

        var verdict = slot.Accept(seq, state, now, out bool resumed);
        if (resumed) _log.Info($"{slot}: input resumed");
        if (_padFactory is null && now >= _nextDryRunLog)
        {
            _nextDryRunLog = now + DryRunLogTicks;
            _log.Info($"{slot} INPUT seq={seq} {state} -> {verdict}");
        }
    }

    private PadSlot? TryConnect(int index, long now)
    {
        if (now < _nextConnectAttempt) return null;

        var address = new SocketAddress(AddressFamily.InterNetwork, _from.Size);
        _from.Buffer.Span[.._from.Size].CopyTo(address.Buffer.Span);
        var slot = new PadSlot(index, address, now);

        if (_padFactory is null)
        {
            _log.Info($"{slot.EndPoint} connected -> controller {index + 1} (dry run, no virtual pad)");
        }
        else
        {
            long started = Stopwatch.GetTimestamp();
            try
            {
                slot.Pad = CreatePad(_padFactory, slot);
            }
            catch (Exception e)
            {
                // Plugging in costs milliseconds; do not retry at packet rate while the driver keeps refusing.
                _nextConnectAttempt = now + _connectRetryTicks;
                _log.Error($"{slot.EndPoint}: could not plug in a virtual controller, retrying in {_options.ConnectRetryMs} ms: " +
                           ViGEmPadFactory.Describe(e));
                return null;
            }
            _log.Info($"{slot.EndPoint} connected -> controller {index + 1} plugged in " +
                      $"({Stopwatch.GetElapsedTime(started).TotalMilliseconds:F0} ms)");
        }

        _slots[index] = slot;
        _padsInUse++;
        return slot;
    }

    // A separate method so the closure is only allocated when a pad is really created, not on every rejected INPUT.
    private IVirtualPad CreatePad(IVirtualPadFactory factory, PadSlot slot) =>
        factory.Create((large, small, led) => OnPadFeedback(slot, large, small, led));

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
        slot.WriteFeedback(feedback, _slotFlags);
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
        if (_padFactory is not null || now < _nextDryRunLog) return;
        _nextDryRunLog = now + DryRunLogTicks;
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
            _log.Info($"[stats] {slot}: {rate:F0} pkt/s, accepted {s.Accepted}, dropped duplicate {s.Duplicates} / " +
                      $"stale {s.Stale}, invalid {s.Invalid}, submitted {s.Submits}, last seq {slot.LastSeq}");
        }
        if (_invalidFromUnknown != 0 || _filtered != 0)
            _log.Info($"[stats] other senders: invalid {_invalidFromUnknown}, blocked by --allow {_filtered}");
    }

    private static IPEndPoint EndPointOf(SocketAddress address) => (IPEndPoint)new IPEndPoint(IPAddress.Any, 0).Create(address);
}
