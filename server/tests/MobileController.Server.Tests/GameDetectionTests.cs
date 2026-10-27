using System.Diagnostics;

namespace MobileController.Server.Tests;

/// <summary>
/// GAME (PROTOCOL.md §10) through the real receive loop over loopback UDP, with a scripted foreground app in place
/// of Win32.
/// </summary>
public sealed class GameDetectionTests
{
    private static readonly PadState Sample = new(0x1001, 10, 255, 32767, -32767, -1, 256);
    private static readonly byte[] P8 = Hex.Bytes("A5 1B 10 72 6F 63 6B 65 74 6C 65 61 67 75 65 2E 65 78 65");
    private static readonly byte[] P9 = Hex.Bytes("A5 1B 00");

    [Theory]
    [InlineData(false)]
    [InlineData(true)] // §10: sent in --dry-run as well
    public void New_client_gets_the_foreground_app_right_after_its_first_feedback(bool dryRun)
    {
        using var h = new ServerHarness(dryRun: dryRun);
        using var client = h.Connect();

        client.SendInput(0, Sample);
        h.PumpDatagrams();

        Assert.True(Protocol.TryParseFeedback(client.Receive(), out _), "FEEDBACK first: the phone is connected before it reads the name");
        Assert.Equal(P8, client.Receive());
        Assert.Contains("Foreground app: rocketleague.exe", h.LogText.ToString());
    }

    [Fact]
    public void A_name_change_is_pushed_at_once_to_every_slot_holder_and_nobody_else()
    {
        using var h = new ServerHarness();
        using var first = h.Connect();
        using var second = h.Connect();
        using var bystander = h.Connect(); // PINGs only, so it never holds a slot
        first.SendInput(0, Sample);
        second.SendInput(0, Sample);
        bystander.SendHex("A5 12 00 00 00 00 00 00");
        h.PumpDatagrams(3);
        Assert.Equal(2, h.Server.PadsInUse);
        Assert.Equal(P8, first.ReceiveQueued()[^1]);
        Assert.Equal(P8, second.ReceiveQueued()[^1]);
        Assert.Equal([Hex.Bytes("A5 1A 00 00 00 00 00 00")], bystander.ReceiveQueued());

        h.ForegroundApp = @"D:\SteamLibrary\steamapps\common\ForzaHorizon5\ForzaHorizon5.exe";
        h.Server.GameDetector!.Poll();
        h.Server.PumpOnce(0);

        var forza = Hex.Game("forzahorizon5.exe");
        Assert.Equal(forza, first.Receive());
        Assert.Equal(forza, second.Receive());
        Assert.Null(bystander.Receive(50));

        // The same app on the next poll is no change: nothing sent, nothing logged.
        h.Server.GameDetector.Poll();
        h.PumpFor(30);
        Assert.Null(first.Receive(50));
        Assert.Single(h.LogText.ToString().Split('\n'), line => line.Contains("Foreground app: forzahorizon5.exe", StringComparison.Ordinal));

        // No foreground window or an unreadable process: N = 0 (P9).
        h.ForegroundApp = null;
        h.Server.GameDetector.Poll();
        h.Server.PumpOnce(0);
        Assert.Equal(P9, first.Receive());
        Assert.Equal(P9, second.Receive());
        Assert.Null(bystander.Receive(50));
        Assert.Contains("Foreground app: unknown", h.LogText.ToString());
    }

    [Fact]
    public void Game_is_repeated_every_interval_to_slot_holders_only()
    {
        using var h = new ServerHarness(ServerHarness.Quiet with { GameIntervalMs = 50 });
        using var client = h.Connect();
        using var bystander = h.Connect();
        client.SendInput(0, Sample);
        bystander.SendHex("A5 12 00 00 00 00 00 00");
        h.PumpDatagrams(2);

        h.PumpFor(520);

        // One on connect, then one per 50 ms: at most 11 in 520 ms; fewer only if the machine is very busy.
        var games = client.ReceiveQueued().Where(d => !Protocol.TryParseFeedback(d, out _)).ToList();
        Assert.InRange(games.Count, 6, 11);
        Assert.All(games, g => Assert.Equal(P8, g));
        Assert.Equal([Hex.Bytes("A5 1A 00 00 00 00 00 00")], bystander.ReceiveQueued());
    }

    [Fact]
    public void A_reconnecting_client_gets_the_name_again_at_once()
    {
        using var h = new ServerHarness();
        using var client = h.Connect();
        client.SendInput(0, Sample);
        h.PumpDatagrams();
        client.SendBye();
        h.PumpDatagrams();
        client.Drain();

        client.SendInput(0, Sample);
        h.PumpDatagrams();
        Assert.Equal(P8, client.ReceiveQueued()[^1]);
    }

    [Fact]
    public void No_game_detect_never_looks_at_the_foreground_window_and_sends_nothing()
    {
        using var h = new ServerHarness(ServerHarness.Quiet with { NoGameDetect = true, GameIntervalMs = 10, GamePollMs = 10 });
        using var client = h.Connect();
        h.Server.Start(); // the production path, receive thread and all

        client.SendInput(0, Sample);
        Assert.NotNull(client.ReceiveFeedback(2000));
        Thread.Sleep(200);

        Assert.Null(h.Server.GameDetector);
        Assert.DoesNotContain(client.ReceiveQueued(), d => Protocol.TryParseGame(d, out _));
        Assert.Equal(0, h.ForegroundQueries);
        Assert.DoesNotContain("Foreground app", h.LogText.ToString(), StringComparison.Ordinal);
    }

    [Fact]
    public void Start_polls_at_once_then_the_detector_thread_follows_the_foreground_until_disposed()
    {
        string? current = "Explorer.EXE";
        int polls = 0;
        string? Source()
        {
            Interlocked.Increment(ref polls);
            return Volatile.Read(ref current) is "throw" ? throw new InvalidOperationException("simulated Win32 failure") : current;
        }

        var log = new StringWriter();
        var detector = new GameDetector(Source, pollMs: 5, new Logger(TextWriter.Synchronized(log), synchronous: true));
        Assert.Null(detector.Packet);

        detector.Start();
        Assert.Equal(Hex.Game("explorer.exe"), detector.Packet); // synchronous first poll: no wait for the first client
        var published = detector.Packet;

        Volatile.Write(ref current, ServerHarness.RocketLeague);
        Assert.True(SpinWait.SpinUntil(() => detector.Packet != published, 2000), "the detector thread did not pick up the change");
        Assert.Equal(P8, detector.Packet);
        Assert.Equal(Hex.Game("explorer.exe"), published); // a published array is never written again

        Volatile.Write(ref current, "throw"); // a failing source reads as unknown instead of killing the thread
        Assert.True(SpinWait.SpinUntil(() => detector.Packet?.Length == 3, 2000));
        Assert.Equal(P9, detector.Packet);

        detector.Dispose();
        int after = Volatile.Read(ref polls);
        Thread.Sleep(50);
        Assert.Equal(after, Volatile.Read(ref polls));
        detector.Dispose(); // idempotent

        Assert.Equal(["Foreground app: explorer.exe", "Foreground app: rocketleague.exe", "Foreground app: unknown"],
            log.ToString().Split('\n', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
                .Select(line => line[(line.IndexOf(']', StringComparison.Ordinal) + 2)..]));
    }

    [Fact]
    public void Real_foreground_window_reaches_the_client_through_the_production_detector()
    {
        // No injected source: the server asks Windows, exactly as in production.
        var log = new StringWriter();
        var options = ServerHarness.Quiet with { Port = 0, BindAddress = System.Net.IPAddress.Loopback };
        using var server = new GamepadServer(options, null, new Logger(TextWriter.Synchronized(log), synchronous: true));
        using var client = new TestClient(server.LocalEndPoint);
        server.Start();

        client.SendInput(0, Sample);
        var watch = Stopwatch.StartNew();
        byte[]? game = null;
        while (game is null && watch.ElapsedMilliseconds < 2000)
        {
            var datagram = client.Receive(100);
            if (datagram is not null && Protocol.TryParseGame(datagram, out _)) game = datagram;
        }

        Assert.NotNull(game);
        Assert.True(Protocol.TryParseGame(game, out var name));
        // Unknown is legitimate on a desktop-less session; otherwise it is a lower-case printable ASCII file name.
        Assert.All(name.ToArray(), b => Assert.True(b is >= 0x20 and <= 0x7E && b is not (>= (byte)'A' and <= (byte)'Z'), $"byte 0x{b:X2}"));
        Assert.DoesNotContain((byte)'\\', name.ToArray());
        Assert.Contains("Foreground app: ", log.ToString(), StringComparison.Ordinal);
    }
}
