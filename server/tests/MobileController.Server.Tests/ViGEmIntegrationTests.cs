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
