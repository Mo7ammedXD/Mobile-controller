using System.Diagnostics;
using System.Net.Sockets;

namespace MobileController.Server.Tests;

/// <summary>
/// The hard requirement: no heap allocation on the steady-state receive path, so the GC never pauses it. The test
/// runs the production loop (<see cref="GamepadServer.PumpOnce"/>: Poll, ReceiveFrom into a SocketAddress, validate,
/// sequence check, change detection, Submit, timers, FEEDBACK/PONG/GAME sends) on its own thread and counts that
/// thread's allocations.
/// </summary>
public sealed class AllocationTests
{
    private const int Chunk = 10_000;
    private const int Measured = 100_000;

    // Different lengths, so the client can tell a name change from a repetition.
    private static readonly string[] Apps = [ServerHarness.RocketLeague, @"C:\Windows\explorer.exe"];

    [Fact]
    public void Steady_state_receive_path_allocates_nothing()
    {
        // Heartbeats and GAME repetitions every 5 ms and a PING every 64 packets put the send paths inside the
        // measurement too. The detector thread really runs and switches the foreground app on every poll, so freshly
        // published GAME arrays are picked up and sent by the measured thread (the detector's own allocations happen
        // on its thread and are not counted).
        int polls = 0;
        using var h = new ServerHarness(ServerHarness.Quiet with { FeedbackIntervalMs = 5, GameIntervalMs = 5, GamePollMs = 1 },
            recordHistory: false, foregroundApp: () => Apps[Interlocked.Increment(ref polls) & 1]);
        h.Server.GameDetector!.Start();
        using var client = h.Connect();
        ushort seq = 0;

        // Warm-up must outlast tiered compilation: Socket.Poll/ReceiveFrom/SendTo each allocate 48 bytes once when
        // their WS2_32 P/Invoke is first bound, and ReceiveFrom/SendTo once more when tiering recompiles them about
        // half a second in (gone with DOTNET_TieredPGO=0). Never per call. So: at least 1.5 s, then until three
        // consecutive chunks allocate nothing.
        var warmUp = Stopwatch.StartNew();
        var chunks = new List<long>();
        int clean = 0;
        while (warmUp.ElapsedMilliseconds < 1500 || clean < 3)
        {
            long start = GC.GetAllocatedBytesForCurrentThread();
            Pump(h.Server, client.Socket, Chunk, ref seq, out _, out _, out _, out _);
            long bytes = GC.GetAllocatedBytesForCurrentThread() - start;
            chunks.Add(bytes);
            clean = bytes == 0 ? clean + 1 : 0;
            Assert.True(warmUp.ElapsedMilliseconds < 20_000, $"allocation never settled: {string.Join(", ", chunks)}");
        }

        var pad = Assert.Single(h.Factory.Pads);
        long receivedBefore = h.Server.DatagramsReceived;
        int submitsBefore = pad.SubmitCount;

        long before = GC.GetAllocatedBytesForCurrentThread();
        Pump(h.Server, client.Socket, Measured, ref seq, out int pings, out var newest, out int games, out int gameChanges);
        long allocated = GC.GetAllocatedBytesForCurrentThread() - before;

        long received = h.Server.DatagramsReceived - receivedBefore;
        int submits = pad.SubmitCount - submitsBefore;
        TestContext.Current.TestOutputHelper?.WriteLine(
            $"warm-up: {chunks.Count} x {Chunk} INPUTs in {warmUp.ElapsedMilliseconds} ms, bytes per chunk: {string.Join(", ", chunks)}");
        TestContext.Current.TestOutputHelper?.WriteLine(
            $"measured: {Measured} INPUT + {pings} PING datagrams ({received} handled by the server), " +
            $"{submits} reports submitted, {games} GAME datagrams ({gameChanges} name changes) received, " +
            $"{allocated} bytes allocated on the receive thread");

        Assert.Equal(0, allocated);
        Assert.True(games > 0 && gameChanges > 0, $"GAME was not exercised: {games} datagrams, {gameChanges} changes");
        Assert.Equal(Measured + pings, received);
        // Each burst of four ends in one submit of its newest state (a duplicate in it is dropped).
        Assert.InRange(submits, Measured / 4, Measured);
        Assert.Equal(newest, pad.Last);
        Assert.False(pad.Disposed);
    }

    /// <summary>
    /// Sends <paramref name="count"/> INPUTs in bursts of four, pumping the server after each burst, and returns once
    /// the server has handled every datagram sent.
    /// </summary>
    private static void Pump(GamepadServer server, Socket client, int count, ref ushort seq, out int pings, out PadState newest,
        out int games, out int gameChanges)
    {
        Span<byte> packet = stackalloc byte[Protocol.InputSize];
        Span<byte> ping = stackalloc byte[Protocol.PingSize];
        Span<byte> sink = stackalloc byte[Protocol.MaxDatagramSize];
        long target = server.DatagramsReceived + count;
        int lastGameLength = 0;
        pings = 0;
        newest = default;
        games = 0;
        gameChanges = 0;
        for (int i = 0; i < count; i++)
        {
            // Every 10th INPUT repeats the previous seq with a different state: a duplicate the server must drop.
            bool duplicate = i % 10 == 9;
            if (!duplicate) seq++;
            // A distinct state per seq (button bits included), so every accepted packet is a real change.
            var state = duplicate
                ? new PadState(0, 1, 2, 3, 4, 5, 6)
                : new PadState((ushort)(seq & Protocol.ButtonMask), (byte)seq, (byte)(seq >> 8),
                    (short)seq, (short)~seq, (short)(seq * 3), (short)(seq * 7));
            if (!duplicate) newest = state;
            Protocol.WriteInput(packet, seq, state);
            client.Send(packet);

            if (i % 64 == 0)
            {
                Protocol.WritePing(ping, (ushort)i, (uint)i);
                client.Send(ping);
                pings++;
                target++;
            }

            if (i % 4 == 3)
            {
                server.PumpOnce(1000);
                DrainClient(client, sink, ref games, ref gameChanges, ref lastGameLength);
            }
        }
        for (int spin = 0; spin < 1000 && server.DatagramsReceived < target; spin++) server.PumpOnce(1000);
        DrainClient(client, sink, ref games, ref gameChanges, ref lastGameLength);
    }

    private static void DrainClient(Socket client, Span<byte> sink, ref int games, ref int gameChanges, ref int lastGameLength)
    {
        while (client.Available > 0)
        {
            int length = client.Receive(sink);
            if (!Protocol.TryParseGame(sink[..length], out _)) continue;
            games++;
            if (length != lastGameLength) gameChanges++;
            lastGameLength = length;
        }
    }
}
