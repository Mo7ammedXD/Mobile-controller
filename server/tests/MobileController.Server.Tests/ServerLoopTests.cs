using System.Diagnostics;
using System.Net;

namespace MobileController.Server.Tests;

/// <summary>
/// The real receive loop over loopback UDP, with a fake pad. Each test pumps the loop on its own thread, so timing is
/// deterministic apart from the (shortened) protocol timers.
/// </summary>
public sealed class ServerLoopTests
{
    private static readonly PadState Sample = new(0x1001, 10, 255, 32767, -32767, -1, 256);
    private static readonly PadState Other = new(0x2000, 0, 0, -100, 100, 0, 0);

    [Fact]
    public void New_client_plugs_a_pad_applies_the_state_and_gets_feedback_at_once()
    {
        using var h = new ServerHarness();
        using var client = h.Connect();

        client.SendInput(7, Sample);
        h.PumpDatagrams();

        var pad = Assert.Single(h.Factory.Pads);
        Assert.Equal(new[] { Sample }, pad.History);
        Assert.Equal(1, h.Server.PadsInUse);
        Assert.Contains("connected -> controller 1 plugged in", h.LogText.ToString());

        // The first heartbeat leaves on the same loop pass, so the phone shows "connected" without waiting 100 ms.
        Assert.Equal(new FeedbackPacket(0, 0, Protocol.UnknownPlayer, FeedbackFlags.PadAttached, 7), client.ReceiveFeedback());
    }

    [Fact]
    public void Reserved_button_bit_never_reaches_the_pad()
    {
        using var h = new ServerHarness();
        using var client = h.Connect();

        client.SendHex("A5 11 01 00 FF FF 00 00 00 00 00 00 00 00 00 00"); // vector I4
        h.PumpDatagrams();

        Assert.Equal(0xF7FF, Assert.Single(h.Factory.Pads).Last.Buttons);
    }

    [Fact]
    public void Duplicate_and_stale_sequence_numbers_are_dropped()
    {
        using var h = new ServerHarness(ServerHarness.Quiet with { Verbose = true, StatsIntervalMs = 1 });
        using var client = h.Connect();

        client.SendInput(100, Sample);
        h.PumpDatagrams();
        client.SendInput(100, Other); // duplicate
        h.PumpDatagrams();
        client.SendInput(36, Other);  // 64 behind: reordered straggler
        h.PumpDatagrams();

        var pad = Assert.Single(h.Factory.Pads);
        Assert.Equal(new[] { Sample }, pad.History);

        client.SendInput(35, Other);  // 65 behind: the phone restarted its counter
        h.PumpDatagrams();
        client.SendInput(36, Sample); // newer than 35 again
        h.PumpDatagrams();

        Assert.Equal(new[] { Sample, Other, Sample }, pad.History);
        h.PumpFor(10); // one more stats line that includes the last packet
        Assert.Contains("accepted 3, dropped duplicate 1 / stale 1, invalid 0, submitted 3, last seq 36", h.LogText.ToString());
    }

    [Fact]
    public void Identical_reports_skip_the_driver_call()
    {
        using var h = new ServerHarness();
        using var client = h.Connect();

        for (ushort seq = 0; seq < 5; seq++)
        {
            client.SendInput(seq, Sample);
            h.PumpDatagrams();
        }

        Assert.Equal(1, Assert.Single(h.Factory.Pads).SubmitCount);
    }

    [Fact]
    public void A_queued_burst_is_submitted_once_with_the_newest_state()
    {
        using var h = new ServerHarness();
        using var client = h.Connect();
        client.SendInput(0, Sample);
        h.PumpDatagrams();

        var newest = new PadState(0x8000, 1, 2, 3, 4, 5, 6);
        client.SendInput(1, Other);
        client.SendInput(2, new PadState(0x4000, 0, 0, 0, 0, 0, 0));
        client.SendInput(3, newest);
        Thread.Sleep(50); // let all three land in the server's queue
        h.Server.PumpOnce(0);

        Assert.Equal(new[] { Sample, newest }, Assert.Single(h.Factory.Pads).History);
        Assert.Equal(4, h.Server.DatagramsReceived);
    }

    [Fact]
    public void Neutral_timeout_submits_zeros_once_and_any_seq_resyncs()
    {
        using var h = new ServerHarness(ServerHarness.Quiet with { NeutralTimeoutMs = 50 });
        using var client = h.Connect();
        client.SendInput(1, Sample);
        h.PumpDatagrams();
        var pad = Assert.Single(h.Factory.Pads);

        Assert.True(h.PumpUntil(() => pad.History.Count == 2, 1000), "inputs were not released");
        h.PumpFor(150);
        Assert.Equal(new[] { Sample, default }, pad.History);
        Assert.Contains("no input for 50 ms, inputs released", h.LogText.ToString());

        // Rule 2 of §3.3: seq 0 would be stale after 1, but a timed-out slot accepts anything.
        client.SendInput(0, Other);
        h.PumpDatagrams();
        Assert.Equal(new[] { Sample, default, Other }, pad.History);
        Assert.Contains("input resumed", h.LogText.ToString());
        Assert.False(pad.Disposed);
    }

    [Fact]
    public void Silence_unplugs_after_the_disconnect_timeout_but_any_datagram_keeps_the_slot()
    {
        using var h = new ServerHarness(ServerHarness.Quiet with { NeutralTimeoutMs = 50, DisconnectTimeoutMs = 200 });
        using var client = h.Connect();
        client.SendInput(0, Sample);
        h.PumpDatagrams();
        var pad = Assert.Single(h.Factory.Pads);

        // PINGs alone (a phone whose controller screen is paused) keep the pad plugged in.
        var watch = Stopwatch.StartNew();
        while (watch.ElapsedMilliseconds < 400)
        {
            client.SendHex("A5 12 00 00 00 00 00 00");
            h.PumpFor(40);
        }
        Assert.False(pad.Disposed);

        Assert.True(h.PumpUntil(() => pad.Disposed, 2000), "pad was not unplugged");
        Assert.Equal(0, h.Server.PadsInUse);
        Assert.Contains("disconnected (timeout) -> controller 1 unplugged", h.LogText.ToString());
    }

    [Fact]
    public void Bye_unplugs_immediately_and_frees_the_slot()
    {
        using var h = new ServerHarness();
        using var client = h.Connect();
        client.SendInput(0, Sample);
        h.PumpDatagrams();

        client.SendBye();
        h.PumpDatagrams();

        var first = Assert.Single(h.Factory.Pads);
        Assert.True(first.Disposed);
        Assert.Equal(0, h.Server.PadsInUse);
        Assert.Contains("disconnected (bye)", h.LogText.ToString());

        // The phone re-enters the controller screen: a fresh pad, and seq may start over.
        client.SendInput(0, Other);
        h.PumpDatagrams();
        Assert.Equal(2, h.Factory.Pads.Count);
        Assert.Equal(new[] { Other }, h.Factory.Pads[1].History);
    }

    [Fact]
    public void Ping_is_answered_with_an_echoing_pong_without_a_slot()
    {
        using var h = new ServerHarness();
        using var client = h.Connect();

        client.SendHex("A5 12 02 01 EF BE AD DE"); // P1
        h.PumpDatagrams();

        Assert.Equal(Hex.Bytes("A5 1A 02 01 EF BE AD DE"), client.Receive()); // P2
        Assert.Empty(h.Factory.Pads);
    }

    [Fact]
    public void Discover_is_answered_with_name_and_pad_counts()
    {
        using var h = new ServerHarness();
        using var player = h.Connect();
        player.SendInput(0, Sample);
        h.PumpDatagrams();

        using var finder = h.Connect();
        finder.SendHex("A5 14 EF BE"); // P4
        h.PumpDatagrams();

        // nonce BEEF, 1 in use, 4 max, N = 6, "TestPC"
        Assert.Equal(Hex.Bytes("A5 1C EF BE 01 04 06 54 65 73 74 50 43"), finder.Receive());
        Assert.Single(h.Factory.Pads);
    }

    [Fact]
    public void Server_full_is_reported_and_rate_limited()
    {
        using var h = new ServerHarness(ServerHarness.Quiet with { MaxPads = 1 });
        using var first = h.Connect();
        using var second = h.Connect();
        first.SendInput(0, Sample);
        h.PumpDatagrams();

        second.SendInput(0, Sample);
        h.PumpDatagrams();
        Assert.Equal(new FeedbackPacket(0, 0, Protocol.UnknownPlayer, FeedbackFlags.ServerFull, 0), second.ReceiveFeedback());

        // More packets inside the 250 ms window get no further reply.
        for (ushort seq = 1; seq <= 5; seq++) second.SendInput(seq, Sample);
        h.PumpDatagrams(5);
        Assert.Null(second.Receive(50));

        Thread.Sleep(Protocol.ServerFullIntervalMs + 50);
        second.SendInput(6, Sample);
        h.PumpDatagrams();
        Assert.Equal(FeedbackFlags.ServerFull, second.ReceiveFeedback()?.Flags);

        Assert.Single(h.Factory.Pads);
        Assert.Contains("rejected: all 1 controller slots are in use", h.LogText.ToString());
    }

    [Fact]
    public void Allow_list_drops_other_senders_before_parsing()
    {
        using var h = new ServerHarness(ServerHarness.Quiet with { Allow = [IPAddress.Parse("127.0.0.2")] });
        using var stranger = h.Connect(IPAddress.Loopback);
        using var phone = h.Connect(IPAddress.Parse("127.0.0.2"));

        stranger.SendInput(0, Sample);
        stranger.SendHex("A5 12 02 01 EF BE AD DE");
        h.PumpFor(100);
        Assert.Empty(h.Factory.Pads);
        Assert.Null(stranger.Receive(50));
        Assert.Equal(0, h.Server.DatagramsReceived);

        phone.SendInput(0, Sample);
        h.PumpDatagrams();
        Assert.Single(h.Factory.Pads);
    }

    [Fact]
    public void Feedback_heartbeat_reaches_every_client_with_a_slot()
    {
        using var h = new ServerHarness(ServerHarness.Quiet with { FeedbackIntervalMs = 20 });
        using var client = h.Connect();
        client.SendInput(42, Sample);
        h.PumpDatagrams();

        h.PumpFor(150);

        var beats = client.ReceiveQueuedFeedback();
        Assert.InRange(beats.Count, 4, 12);
        Assert.All(beats, f => Assert.Equal(new FeedbackPacket(0, 0, Protocol.UnknownPlayer, FeedbackFlags.PadAttached, 42), f));

        // Once Windows assigns the player LED, heartbeats carry it and the log names it once.
        Assert.Single(h.Factory.Pads).RaiseFeedback(0, 0, 2);
        h.PumpFor(100);
        var withPlayer = client.ReceiveQueuedFeedback();
        Assert.NotEmpty(withPlayer);
        Assert.All(withPlayer, f => Assert.Equal(2, f.PlayerIndex));
        Assert.Single(h.LogText.ToString().Split('\n'), line => line.Contains("(controller 1) is XInput player 3", StringComparison.Ordinal));
    }

    [Fact]
    public void Rumble_changes_are_sent_at_once_and_repeats_are_not()
    {
        using var h = new ServerHarness();
        using var client = h.Connect();
        client.SendInput(9, Sample);
        h.PumpDatagrams();
        client.Drain();
        var pad = Assert.Single(h.Factory.Pads);

        // The driver thread calls back; the datagram leaves from that thread without waiting for the loop.
        pad.RaiseFeedback(200, 100, 1);
        Assert.Equal(new FeedbackPacket(200, 100, 1, FeedbackFlags.PadAttached, 9), client.ReceiveFeedback(500));

        // Games call XInputSetState every frame; unchanged values must not turn into a datagram each time.
        pad.RaiseFeedback(200, 100, 1);
        Assert.Null(client.Receive(100));

        pad.RaiseFeedback(0, 0, 0xFF); // LED unknown → player 0xFF
        Assert.Equal(new FeedbackPacket(0, 0, Protocol.UnknownPlayer, FeedbackFlags.PadAttached, 9), client.ReceiveFeedback(500));
    }

    [Fact]
    public void Late_callback_from_an_unplugged_pad_never_reaches_the_slot_reuser()
    {
        using var h = new ServerHarness();
        using var gone = h.Connect();
        gone.SendInput(0, Sample);
        h.PumpDatagrams();
        gone.SendBye();
        h.PumpDatagrams();

        using var next = h.Connect();
        next.SendInput(0, Sample);
        h.PumpDatagrams();
        Assert.Equal(new FeedbackPacket(0, 0, Protocol.UnknownPlayer, FeedbackFlags.PadAttached, 0), next.ReceiveFeedback());
        next.Drain(); // the GAME that follows the first FEEDBACK
        gone.Drain();

        var (oldPad, newPad) = (h.Factory.Pads[0], h.Factory.Pads[1]);
        oldPad.RaiseFeedback(255, 255, 0);
        Assert.Null(next.Receive(100));
        Assert.Null(gone.Receive(50));

        newPad.RaiseFeedback(1, 2, 0);
        Assert.Equal(new FeedbackPacket(1, 2, 0, FeedbackFlags.PadAttached, 0), next.ReceiveFeedback());
    }

    [Fact]
    public void Dry_run_tracks_clients_without_pads_and_flags_feedback()
    {
        using var h = new ServerHarness(dryRun: true);
        using var client = h.Connect();
        client.SendInput(3, Sample);
        h.PumpDatagrams();

        Assert.Empty(h.Factory.Pads);
        Assert.Equal(1, h.Server.PadsInUse);
        Assert.Equal(new FeedbackPacket(0, 0, Protocol.UnknownPlayer, FeedbackFlags.DryRun, 3), client.ReceiveFeedback());
        string log = h.LogText.ToString();
        Assert.Contains("dry run, no virtual pad", log);
        Assert.Contains("INPUT seq=3 btn=0x1001 LT=10 RT=255 L=(32767,-32767) R=(-1,256) -> Accept", log);

        client.SendBye();
        h.PumpDatagrams();
        Assert.Equal(0, h.Server.PadsInUse);
        Assert.Contains("disconnected (bye) -> controller 1 slot freed", h.LogText.ToString());
    }

    [Fact]
    public void Invalid_datagrams_are_dropped_and_a_version_mismatch_is_logged_once()
    {
        using var h = new ServerHarness();
        using var client = h.Connect();

        foreach (string hex in new[]
                 {
                     "A5 11 00 00 00 00 00 00 00 00 00 00 00 00 00",     // R1
                     "A5 21 00 00 00 00 00 00 00 00 00 00 00 00 00 00",  // R2
                     "A4 11 00 00 00 00 00 00 00 00 00 00 00 00 00 00",  // R3
                     "A5 16 00 00",                                      // R4
                     "A5 15 00 00",                                      // RS1: STATUS too short
                     "A5 19 FF 40 00 01 34 12",                          // R5
                     "A5 13 00",                                         // R6
                     "A5 1B 10 72 6F 63 6B 65 74 6C 65 61 67 75 65 2E 65 78 65", // P8: GAME is server → client only
                     "A5 1B 00",                                         // P9
                     "A5 21 00 00 00 00 00 00 00 00 00 00 00 00 00 00",  // R2 again
                 })
            client.SendHex(hex);
        h.PumpFor(100);

        Assert.Empty(h.Factory.Pads);
        Assert.Equal(0, h.Server.DatagramsReceived);
        Assert.Null(client.Receive(50));
        string log = h.LogText.ToString();
        int warnings = log.Split("speaks protocol version 2").Length - 1;
        Assert.Equal(1, warnings);
    }

    [Fact]
    public void Refused_plug_in_is_retried_after_a_back_off_without_claiming_server_full()
    {
        using var h = new ServerHarness(ServerHarness.Quiet with { ConnectRetryMs = 300 });
        using var client = h.Connect();
        h.Factory.Fail = true;

        client.SendInput(0, Sample);
        h.PumpDatagrams();
        Assert.Empty(h.Factory.Pads);
        Assert.Null(client.Receive(50));
        Assert.Contains("could not plug in a virtual controller, retrying in 300 ms", h.LogText.ToString());

        h.Factory.Fail = false;
        client.SendInput(1, Sample);
        h.PumpDatagrams();
        Assert.Empty(h.Factory.Pads); // still backing off

        Thread.Sleep(350);
        client.SendInput(2, Sample);
        h.PumpDatagrams();
        Assert.Single(h.Factory.Pads);
    }

    [Fact]
    public void Refused_plug_in_for_one_phone_does_not_block_another()
    {
        using var h = new ServerHarness(ServerHarness.Quiet with { ConnectRetryMs = 60_000 });
        using var first = h.Connect();
        using var second = h.Connect();

        h.Factory.Fail = true;
        first.SendInput(0, Sample);
        h.PumpDatagrams();
        Assert.Empty(h.Factory.Pads);

        // The driver recovered: the other phone connects at once, the first one keeps backing off.
        h.Factory.Fail = false;
        second.SendInput(0, Other);
        h.PumpDatagrams();
        Assert.Single(h.Factory.Pads);
        first.SendInput(1, Sample);
        h.PumpDatagrams();
        Assert.Single(h.Factory.Pads);
    }

    [Fact]
    public void Repeated_plug_in_failures_pause_every_plug_in()
    {
        using var h = new ServerHarness(ServerHarness.Quiet with { ConnectRetryMs = 60_000 });
        var clients = Enumerable.Range(0, 4).Select(_ => h.Connect()).ToList();
        try
        {
            h.Factory.Fail = true;
            for (int i = 0; i < 3; i++)
            {
                clients[i].SendInput(0, Sample);
                h.PumpDatagrams();
            }
            Assert.Contains("3 failures in a row: pausing every plug-in", h.LogText.ToString());

            // Even a new phone waits while the breaker is open, so a broken driver is not hammered.
            h.Factory.Fail = false;
            clients[3].SendInput(0, Sample);
            h.PumpDatagrams();
            Assert.Empty(h.Factory.Pads);
        }
        finally
        {
            clients.ForEach(c => c.Dispose());
        }
    }

    [Fact]
    public void Dry_run_logs_every_phone_even_when_one_is_busy()
    {
        using var h = new ServerHarness(dryRun: true);
        using var busy = h.Connect();
        using var quiet = h.Connect();

        busy.SendInput(1, Sample);
        quiet.SendInput(1, Other);
        h.PumpDatagrams(2);

        string log = h.LogText.ToString();
        Assert.Contains("INPUT seq=1 btn=0x1001", log);
        Assert.Contains("INPUT seq=1 btn=0x2000", log);
    }

    [Fact]
    public void Driver_error_on_submit_unplugs_that_pad()
    {
        using var h = new ServerHarness();
        using var client = h.Connect();
        client.SendInput(0, Sample);
        h.PumpDatagrams();
        var pad = Assert.Single(h.Factory.Pads);

        pad.ThrowOnSubmit = true;
        client.SendInput(1, Other);
        h.PumpDatagrams();

        Assert.True(pad.Disposed);
        Assert.Equal(0, h.Server.PadsInUse);
        Assert.Contains("disconnected (driver error)", h.LogText.ToString());
    }

    [Fact]
    public void Receive_thread_stops_within_a_poll_interval_and_unplugs_everything()
    {
        using var h = new ServerHarness(ServerHarness.Quiet with { FeedbackIntervalMs = 100 });
        using var client = h.Connect();
        h.Server.Start();

        client.SendInput(0, Sample);
        Assert.NotNull(client.ReceiveFeedback(2000));
        var pad = Assert.Single(h.Factory.Pads);

        var watch = Stopwatch.StartNew();
        h.Server.RequestStop();
        Assert.True(h.Server.WaitForExit(TimeSpan.FromSeconds(2)));
        watch.Stop();

        Assert.True(watch.ElapsedMilliseconds < 200, $"stop took {watch.ElapsedMilliseconds} ms");
        Assert.True(pad.Disposed);
        Assert.False(h.Server.Faulted);
        Assert.Contains("disconnected (shutdown)", h.LogText.ToString());
    }
}
