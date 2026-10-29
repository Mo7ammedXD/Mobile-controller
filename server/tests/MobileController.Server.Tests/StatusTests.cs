using System.Diagnostics;
using System.Text;

namespace MobileController.Server.Tests;

/// <summary>Status-aware clients (PROTOCOL.md §4, §12): STATUS on the slot, the STATUS wait and moving endpoints.</summary>
public sealed class StatusTests
{
    private static readonly PadState Sample = new(0x1001, 10, 255, 32767, -32767, -1, 256);

    private static PadSlot SlotOf(ServerHarness h) => Assert.Single(h.Server.Slots, s => s is not null)!;

    [Fact]
    public void Status_lands_on_the_slot_and_feedback_acknowledges_it()
    {
        using var h = new ServerHarness();
        using var client = h.Connect();
        client.SendStatus(0xABCDEF, "Pixel 8", battery: 64, profile: "Shooter");
        client.SendInput(0, Sample);
        h.PumpDatagrams(2);

        var slot = SlotOf(h);
        Assert.True(slot.SupportsStatus);
        Assert.Equal(0xABCDEFUL, slot.DeviceId);
        Assert.Equal(64, slot.Battery);
        Assert.Equal(42, slot.RttTenthsMs);
        Assert.Equal("Pixel 8", Encoding.UTF8.GetString(slot.DeviceName, 0, slot.DeviceNameLength));
        Assert.Equal("Shooter", Encoding.UTF8.GetString(slot.ProfileName, 0, slot.ProfileNameLength));
        Assert.Contains("is \"Pixel 8\"", h.LogText.ToString());

        var feedback = client.WaitForFeedback(f => (f.Flags & FeedbackFlags.StatusAck) != 0);
        Assert.NotNull(feedback);
        Assert.Equal(FeedbackFlags.PadAttached | FeedbackFlags.StatusAck, feedback.Value.Flags);

        // Later STATUS updates replace the values without fuss.
        client.SendStatus(0xABCDEF, "Pixel 8", battery: 63);
        h.PumpDatagrams();
        Assert.Equal(63, slot.Battery);
    }

    [Fact]
    public void Clients_without_status_get_no_status_ack()
    {
        using var h = new ServerHarness();
        using var client = h.Connect();
        client.SendInput(0, Sample);
        h.PumpDatagrams();
        Assert.Equal(FeedbackFlags.PadAttached, client.ReceiveFeedback()!.Value.Flags);
        Assert.False(SlotOf(h).SupportsStatus);
    }

    [Fact]
    public void A_new_endpoint_waits_briefly_for_its_status()
    {
        using var h = new ServerHarness(ServerHarness.Quiet with { StatusWaitMs = 300 });
        using var old = h.Connect();
        old.SendInput(0, Sample);
        h.PumpDatagrams();
        Assert.Empty(h.Factory.Pads);

        // No STATUS: the pad comes once the wait is over.
        Thread.Sleep(350);
        old.SendInput(1, Sample);
        h.PumpDatagrams();
        Assert.Single(h.Factory.Pads);

        // STATUS first: no wait at all.
        using var modern = h.Connect();
        modern.SendStatus(7);
        modern.SendInput(0, Sample);
        h.PumpDatagrams(2);
        Assert.Equal(2, h.Factory.Pads.Count);
    }

    [Fact]
    public void A_phone_that_changes_socket_keeps_its_controller()
    {
        using var h = new ServerHarness();
        using var first = h.Connect();
        first.SendStatus(0x77, "Galaxy");
        first.SendInput(10, Sample);
        h.PumpDatagrams(2);
        var pad = Assert.Single(h.Factory.Pads);
        first.Drain();

        // The old socket went quiet; the phone reopens and introduces itself again.
        Thread.Sleep(300);
        using var second = h.Connect();
        second.SendStatus(0x77, "Galaxy");
        h.PumpDatagrams();
        Assert.Contains("(same phone) -> controller 1 kept", h.LogText.ToString());

        // FEEDBACK follows the phone to its new endpoint at once, and nothing more goes to the old one.
        Assert.NotNull(second.WaitForFeedback(f => (f.Flags & FeedbackFlags.StatusAck) != 0));
        Assert.Empty(first.ReceiveQueuedFeedback());

        // A new socket restarts at seq 0, which the moved slot accepts; the pad is the same one.
        var moved = new PadState(0x2000, 0, 0, 1, 2, 3, 4);
        second.SendInput(0, moved);
        h.PumpDatagrams();
        Assert.Single(h.Factory.Pads);
        Assert.False(pad.Disposed);
        Assert.Equal(moved, pad.Last);
        Assert.Equal(1, h.Server.PadsInUse);
    }

    [Fact]
    public void Two_live_phones_with_one_id_never_share_a_controller()
    {
        using var h = new ServerHarness();
        using var original = h.Connect();
        using var copy = h.Connect();
        original.SendStatus(0x99);
        original.SendInput(0, Sample);
        h.PumpDatagrams(2);

        // The copy shows up while the original keeps playing: it is not moved onto the original's controller...
        copy.SendStatus(0x99);
        ushort seq = 1;
        var watch = Stopwatch.StartNew();
        while (watch.ElapsedMilliseconds < 1300)
        {
            original.SendInput(seq++, Sample);
            copy.SendInput(seq, Sample);
            h.PumpFor(20);
        }

        // ...and after a second of both being active it gets one of its own.
        Assert.Equal(2, h.Factory.Pads.Count);
        Assert.Contains("uses the device id of", h.LogText.ToString());
    }
}
