using MobileController.Control;

namespace MobileController.Server.Tests;

/// <summary>DualShock 4 output: the XUSB → DS4 mapping and which pad a phone gets (PROTOCOL.md §4).</summary>
public sealed class Ds4Tests
{
    private static readonly PadState Sample = new(0x1001, 10, 255, 32767, -32767, -1, 256);

    // --- Mapping -------------------------------------------------------------------------------------------------

    [Theory]
    [InlineData(0x1000, (int)Ds4Mapping.Cross)]
    [InlineData(0x2000, (int)Ds4Mapping.Circle)]
    [InlineData(0x4000, (int)Ds4Mapping.Square)]
    [InlineData(0x8000, (int)Ds4Mapping.Triangle)]
    [InlineData(0x0100, (int)Ds4Mapping.ShoulderLeft)]
    [InlineData(0x0200, (int)Ds4Mapping.ShoulderRight)]
    [InlineData(0x0020, (int)Ds4Mapping.Share)]
    [InlineData(0x0010, (int)Ds4Mapping.Options)]
    [InlineData(0x0040, (int)Ds4Mapping.ThumbLeft)]
    [InlineData(0x0080, (int)Ds4Mapping.ThumbRight)]
    public void Each_xbox_button_has_its_playstation_place(int xbox, int ds4)
    {
        var r = Ds4Mapping.Map(new PadState((ushort)xbox, 0, 0, 0, 0, 0, 0));
        Assert.Equal(ds4 | Ds4Mapping.HatNone, r.Buttons);
        Assert.Equal(0, r.Special);
    }

    [Fact]
    public void Guide_is_the_ps_button_and_triggers_also_press_l2_r2()
    {
        var r = Ds4Mapping.Map(new PadState(0x0400, 1, 0, 0, 0, 0, 0));
        Assert.Equal(Ds4Mapping.SpecialPs, r.Special);
        Assert.Equal(Ds4Mapping.TriggerLeft | Ds4Mapping.HatNone, r.Buttons);
        Assert.Equal(1, r.LeftTrigger);
        Assert.Equal(0, r.RightTrigger);
    }

    [Theory]
    [InlineData(0x0, 8)]
    [InlineData(0x1, 0)]  // up = north
    [InlineData(0x9, 1)]  // up + right
    [InlineData(0x8, 2)]  // right
    [InlineData(0xA, 3)]  // down + right
    [InlineData(0x2, 4)]  // down
    [InlineData(0x6, 5)]  // down + left
    [InlineData(0x4, 6)]  // left
    [InlineData(0x5, 7)]  // up + left
    [InlineData(0x3, 8)]  // up + down cancel
    [InlineData(0xC, 8)]  // left + right cancel
    [InlineData(0xD, 0)]  // up, left + right cancel
    [InlineData(0xF, 8)]
    public void Dpad_bits_become_the_hat(int dpad, int hat) =>
        Assert.Equal(hat, Ds4Mapping.Map(new PadState((ushort)dpad, 0, 0, 0, 0, 0, 0)).Hat);

    [Fact]
    public void Sticks_are_centred_bytes_with_y_down()
    {
        Assert.Equal(128, Ds4Mapping.Axis(0));
        Assert.Equal(255, Ds4Mapping.Axis(short.MaxValue));
        Assert.Equal(0, Ds4Mapping.Axis(short.MinValue));

        // Full right / full up on the left stick, full left / full down on the right one.
        var r = Ds4Mapping.Map(new PadState(0, 0, 0, 32767, 32767, -32768, -32768));
        Assert.Equal([255, 0, 0, 255], new[] { r.ThumbLX, r.ThumbLY, r.ThumbRX, r.ThumbRY });
        var rest = Ds4Mapping.Map(default);
        Assert.Equal([128, 128, 128, 128], new[] { rest.ThumbLX, rest.ThumbLY, rest.ThumbRX, rest.ThumbRY });
    }

    [Fact]
    public void Rumble_is_read_from_usb_and_bluetooth_output_reports()
    {
        // USB report 0x05: flags at 1 (bit 0 = motors), light motor at 4, heavy at 5, then the light bar.
        Assert.True(ViGEmDs4Pad.TryReadMotors([0x05, 0x07, 0x04, 0x00, 0x40, 0xC0, 0xFF, 0x00, 0x00], out byte large, out byte small));
        Assert.Equal((0xC0, 0x40), (large, small));
        // Bluetooth report 0x11: flags at 3, motors at 6 and 7.
        Assert.True(ViGEmDs4Pad.TryReadMotors([0x11, 0xC0, 0x20, 0xF7, 0x04, 0x00, 0x10, 0x80, 0, 0, 0], out large, out small));
        Assert.Equal((0x80, 0x10), (large, small));
        // A light-bar-only report leaves the rumble alone; a cut-short one is ignored.
        Assert.False(ViGEmDs4Pad.TryReadMotors([0x05, 0x02, 0x04, 0x00, 0x00, 0x00, 0xFF, 0x00, 0x00], out _, out _));
        Assert.False(ViGEmDs4Pad.TryReadMotors([0x05, 0x01, 0x04], out _, out _));
        Assert.False(ViGEmDs4Pad.TryReadMotors([], out _, out _));
    }

    // --- Which pad a phone gets ----------------------------------------------------------------------------------

    [Fact]
    public void A_phone_asking_for_ds4_gets_one_and_is_told_so()
    {
        using var h = new ServerHarness();
        using var client = h.Connect();
        client.SendStatus(5, output: OutputPreference.DualShock4);
        client.SendInput(0, Sample);
        h.PumpDatagrams(2);

        Assert.Equal(PadKind.DualShock4, Assert.Single(h.Factory.Pads).Kind);
        var feedback = client.WaitForFeedback(f => (f.Flags & FeedbackFlags.TargetDs4) != 0);
        Assert.NotNull(feedback);
        Assert.Equal(FeedbackFlags.PadAttached | FeedbackFlags.TargetDs4 | FeedbackFlags.StatusAck, feedback.Value.Flags);
        Assert.Contains("plugged in as a DualShock 4", h.LogText.ToString());
    }

    [Fact]
    public void Old_phones_get_the_default_and_the_default_is_configurable()
    {
        using (var h = new ServerHarness())
        using (var client = h.Connect())
        {
            client.SendInput(0, Sample);
            h.PumpDatagrams();
            Assert.Equal(PadKind.Xbox360, Assert.Single(h.Factory.Pads).Kind);
        }
        using (var h = new ServerHarness(ServerHarness.Quiet with { DefaultOutput = PadKind.DualShock4 }))
        using (var client = h.Connect())
        {
            client.SendInput(0, Sample);
            h.PumpDatagrams();
            Assert.Equal(PadKind.DualShock4, Assert.Single(h.Factory.Pads).Kind);
        }
    }

    [Fact]
    public void A_device_rule_beats_the_phones_preference()
    {
        var rules = new Dictionary<ulong, DeviceRule> { [0x42] = new(PadKind.Xbox360, "Sam") };
        using var h = new ServerHarness(ServerHarness.Quiet with { Devices = rules });
        using var client = h.Connect();
        client.SendStatus(0x42, output: OutputPreference.DualShock4);
        client.SendInput(0, Sample);
        h.PumpDatagrams(2);
        Assert.Equal(PadKind.Xbox360, Assert.Single(h.Factory.Pads).Kind);
    }

    [Fact]
    public void Asking_for_the_other_kind_replugs_at_the_next_input()
    {
        using var h = new ServerHarness();
        using var client = h.Connect();
        client.SendStatus(9);
        client.SendInput(0, Sample);
        h.PumpDatagrams(2);
        var xbox = Assert.Single(h.Factory.Pads);
        Assert.Equal(PadKind.Xbox360, xbox.Kind);

        client.SendStatus(9, output: OutputPreference.DualShock4);
        h.PumpDatagrams();
        Assert.True(xbox.Disposed);
        Assert.Contains("disconnected (switching to DualShock 4)", h.LogText.ToString());

        // No STATUS wait the second time: the kept STATUS already says what to plug.
        client.SendInput(1, Sample);
        h.PumpDatagrams();
        Assert.Equal(2, h.Factory.Pads.Count);
        Assert.Equal(PadKind.DualShock4, h.Factory.Pads[1].Kind);
        Assert.Equal(Sample, h.Factory.Pads[1].Last);
        Assert.Equal(1, h.Server.PadsInUse);
    }

    [Fact]
    public void Settings_file_devices_and_default_output()
    {
        var settings = new ServerSettings
        {
            DefaultOutput = "ds4",
            Devices = [new DeviceSettings { Id = "0123456789ABCDEF", Alias = " Den TV ", Output = "xbox360" }],
        };
        var o = ServerConfig.ApplyTo(settings, new Options(), []);
        Assert.Equal(PadKind.DualShock4, o.DefaultOutput);
        Assert.Equal(new DeviceRule(PadKind.Xbox360, "Den TV"), o.Devices[0x0123456789ABCDEF]);

        Assert.Throws<FormatException>(() => ServerConfig.ApplyTo(new ServerSettings { Devices = [new() { Id = "nope" }] }, new Options(), []));
        Assert.Throws<FormatException>(() => ServerConfig.ApplyTo(new ServerSettings { DefaultOutput = "ps5" }, new Options(), []));
        Assert.True(Options.TryParse(["--default-output", "DS4"], out var cli, out _));
        Assert.Equal(PadKind.DualShock4, cli.DefaultOutput);
        Assert.False(Options.TryParse(["--default-output", "switch"], out _, out _));
    }

    [Fact]
    public void Dry_run_tracks_the_kind_without_a_pad()
    {
        using var h = new ServerHarness(dryRun: true);
        using var client = h.Connect();
        client.SendStatus(3, output: OutputPreference.DualShock4);
        client.SendInput(0, Sample);
        h.PumpDatagrams(2);
        Assert.Equal(PadKind.DualShock4, Assert.Single(h.Server.Slots, s => s is not null)!.Kind);
        Assert.Empty(h.Factory.Pads);
    }
}
