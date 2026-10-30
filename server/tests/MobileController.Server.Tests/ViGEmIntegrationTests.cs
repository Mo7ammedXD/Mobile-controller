using System.Diagnostics;
using System.Net;
using Nefarius.ViGEm.Client.Exceptions;

namespace MobileController.Server.Tests;

/// <summary>
/// End to end against the installed ViGEmBus driver: UDP INPUT → virtual Xbox 360 pad → XInputGetState, and
/// XInputSetState → driver feedback → FEEDBACK datagram. Skipped when the driver is absent.
/// Run alone with: dotnet test --filter Category=Integration
/// </summary>
[Trait("Category", "Integration")]
public sealed class ViGEmIntegrationTests
{
    // A, LB; LT 255; full right / full down on the left stick; RX and RY are unlikely values that identify our pad
    // among any real controllers the user has plugged in.
    private static readonly PadState Probe = new(0x1000 | 0x0100, 255, 0, 32767, -32767, 12321, -4242);

    [Fact]
    public void Input_reaches_XInput_and_rumble_comes_back_as_feedback()
    {
        ViGEmPadFactory factory;
        try
        {
            factory = new ViGEmPadFactory();
        }
        catch (VigemBusNotFoundException)
        {
            Assert.Skip("ViGEmBus is not installed.");
            return;
        }
        if (Enumerable.Range(0, XInput.MaxUsers).All(i => XInput.GetState((uint)i, out _) == XInput.ErrorSuccess))
        {
            factory.Dispose();
            Assert.Skip("All four XInput slots are taken by other controllers.");
        }

        var log = new StringWriter();
        int index = -1;
        var options = new Options { Port = 0, BindAddress = IPAddress.Loopback, ServerName = "IntegrationTest" };
        using (factory)
        using (var server = new GamepadServer(options, factory, new Logger(TextWriter.Synchronized(log), synchronous: true)))
        using (var client = new TestClient(server.LocalEndPoint))
        {
            server.Start();

            // Stream like the phone does until XInput shows the pad (plug-in takes a moment, and a refused first
            // plug-in is retried by the server).
            ushort seq = 0;
            var watch = Stopwatch.StartNew();
            while (index < 0 && watch.ElapsedMilliseconds < 10_000)
            {
                client.SendInput(seq++, Probe);
                Thread.Sleep(8);
                index = FindProbe();
            }
            Assert.True(index >= 0, "the virtual pad never showed up in XInput with the probe state");
            Assert.Equal(XInput.ErrorSuccess, XInput.GetState((uint)index, out var seen));
            Assert.Equal(Probe, seen.Gamepad); // buttons, triggers and all four axes, bit for bit
            Assert.Equal(1, server.PadsInUse);

            // Windows assigns the player LED shortly after plug-in; the driver reports it and FEEDBACK carries it.
            var attached = client.WaitForFeedback(f => f.PlayerIndex != Protocol.UnknownPlayer, 3000);
            Assert.NotNull(attached);
            Assert.Equal(index, attached.Value.PlayerIndex);
            Assert.Equal(FeedbackFlags.PadAttached, attached.Value.Flags);
            Thread.Sleep(50); // a few loop passes, so the log announces the player before BYE

            try
            {
                // XInput motor speeds are 16-bit; the XUSB report carries the high byte.
                var rumble = new XInput.Vibration { LeftMotorSpeed = 0xC000, RightMotorSpeed = 0x4000 };
                Assert.Equal(XInput.ErrorSuccess, XInput.SetState((uint)index, rumble));
                var shaking = client.WaitForFeedback(f => f.LargeMotor != 0 || f.SmallMotor != 0, 2000);
                Assert.NotNull(shaking);
                Assert.Equal(0xC0, shaking.Value.LargeMotor);
                Assert.Equal(0x40, shaking.Value.SmallMotor);
            }
            finally
            {
                var stop = new XInput.Vibration();
                XInput.SetState((uint)index, stop);
            }
            Assert.NotNull(client.WaitForFeedback(f => f is { LargeMotor: 0, SmallMotor: 0 }, 2000));

            for (int i = 0; i < 3; i++) client.SendBye();
            watch.Restart();
            uint result;
            while ((result = XInput.GetState((uint)index, out _)) != XInput.ErrorDeviceNotConnected && watch.ElapsedMilliseconds < 3000)
                Thread.Sleep(10);
            Assert.Equal(XInput.ErrorDeviceNotConnected, result);
            Assert.Equal(0, server.PadsInUse);

            server.RequestStop();
            Assert.True(server.WaitForExit(TimeSpan.FromSeconds(2)));
            Assert.False(server.Faulted);
        }
        string text = log.ToString();
        Assert.Contains("plugged in", text);
        Assert.Contains($"is XInput player {index + 1}", text);
        Assert.Contains("disconnected (bye) -> controller 1 unplugged", text);
    }

    [Fact]
    public void A_new_Xbox_pad_reads_centred_before_any_input()
    {
        ViGEmPadFactory factory;
        try
        {
            factory = new ViGEmPadFactory();
        }
        catch (VigemBusNotFoundException)
        {
            Assert.Skip("ViGEmBus is not installed.");
            return;
        }
        var taken = Enumerable.Range(0, XInput.MaxUsers).Where(i => XInput.GetState((uint)i, out _) == XInput.ErrorSuccess).ToHashSet();
        if (taken.Count == XInput.MaxUsers)
        {
            factory.Dispose();
            Assert.Skip("All four XInput slots are taken by other controllers.");
        }
        using (factory)
        using (factory.Create(PadKind.Xbox360, (_, _, _) => { }))
        {
            // Nothing is submitted here: a phone whose first state is neutral never changes the pad's report either.
            int index = -1;
            var watch = Stopwatch.StartNew();
            while (index < 0 && watch.ElapsedMilliseconds < 3000)
                index = Enumerable.Range(0, XInput.MaxUsers).FirstOrDefault(i => !taken.Contains(i) && XInput.GetState((uint)i, out _) == XInput.ErrorSuccess, -1);
            Assert.True(index >= 0, "the virtual pad never showed up in XInput");
            // Primed at plug-in and again once Windows assigns the player: centred within moments (unprimed, never).
            XInput.State state = default;
            watch.Restart();
            while (watch.ElapsedMilliseconds < 1000 && (XInput.GetState((uint)index, out state) != XInput.ErrorSuccess || state.Gamepad != default))
                Thread.Sleep(10);
            Assert.Equal(default(PadState), state.Gamepad);
        }
    }

    /// <summary>
    /// The fake pads of the allocation test cannot show what the real wrapper does; this proves that submitting to a real
    /// virtual DualShock 4 allocates nothing either. (XInput cannot see a DS4, so its input is not read back here.)
    /// </summary>
    [Fact]
    public void Dualshock4_plugs_in_and_submitting_allocates_nothing()
    {
        ViGEmPadFactory factory;
        try
        {
            factory = new ViGEmPadFactory();
        }
        catch (VigemBusNotFoundException)
        {
            Assert.Skip("ViGEmBus is not installed.");
            return;
        }
        using (factory)
        using (var pad = factory.Create(PadKind.DualShock4, (_, _, _) => { }))
        {
            Assert.Equal(PadKind.DualShock4, pad.Kind);
            // Like AllocationTests: binding the P/Invoke and tiered recompilation allocate once, early; wait them out
            // (at least 1.5 s, then three clean chunks in a row), then measure.
            var warmUp = Stopwatch.StartNew();
            int clean = 0;
            while (warmUp.ElapsedMilliseconds < 1500 || clean < 3)
            {
                long start = GC.GetAllocatedBytesForCurrentThread();
                SubmitMany(pad, 1000);
                clean = GC.GetAllocatedBytesForCurrentThread() == start ? clean + 1 : 0;
                Assert.True(warmUp.ElapsedMilliseconds < 20_000, "allocation never settled");
            }
            long before = GC.GetAllocatedBytesForCurrentThread();
            SubmitMany(pad, 10_000);
            Assert.Equal(0, GC.GetAllocatedBytesForCurrentThread() - before);
            pad.Submit(default);
        }

        static void SubmitMany(IVirtualPad pad, int count)
        {
            for (int i = 0; i < count; i++)
                pad.Submit(new PadState((ushort)(i & 0xF7FF), (byte)i, (byte)~i, (short)(i * 7), (short)-i, 0, 0));
        }
    }

    private static int FindProbe()
    {
        for (int i = 0; i < XInput.MaxUsers; i++)
        {
            if (XInput.GetState((uint)i, out var state) == XInput.ErrorSuccess &&
                state.Gamepad.ThumbRX == Probe.ThumbRX && state.Gamepad.ThumbRY == Probe.ThumbRY)
                return i;
        }
        return -1;
    }
}
