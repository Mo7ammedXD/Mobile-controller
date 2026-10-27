using System.Diagnostics;

namespace MobileController.Server;

/// <summary>
/// --test-pattern: plugs in one pad and animates it without any network, so ViGEmBus can be verified in joy.cpl or
/// a browser gamepad tester before a phone is involved.
/// </summary>
internal static class TestPattern
{
    // A, B, X, Y, LB, RB, Back, Start, L3, R3, then the D-pad clockwise. Guide (0x0400) is left out on purpose:
    // pressing it opens the Xbox Game Bar.
    private static readonly ushort[] ButtonCycle =
        [0x1000, 0x2000, 0x4000, 0x8000, 0x0100, 0x0200, 0x0020, 0x0010, 0x0040, 0x0080, 0x0001, 0x0008, 0x0002, 0x0004];

    public static void Run(IVirtualPadFactory factory, TimeSpan duration, CancellationToken cancellation, Logger log)
    {
        int lastFeedback = -1;
        void OnFeedback(byte large, byte small, byte led)
        {
            int packed = large | small << 8 | led << 16;
            if (Interlocked.Exchange(ref lastFeedback, packed) == packed) return;
            string player = led <= 3 ? $"XInput player {led + 1}" : "no XInput player assigned yet";
            log.Info($"Feedback from Windows: {player}, rumble large={large} small={small}");
        }

        using var pad = CreateWithRetry(factory, OnFeedback, log);

        log.Info($"Test pattern running for {duration.TotalSeconds:F0} s: sticks circle, triggers ramp, buttons cycle " +
                 "(Guide excluded). Watch it in joy.cpl (Win+R, joy.cpl) or a browser gamepad tester. Ctrl+C stops.");

        long start = Stopwatch.GetTimestamp();
        while (!cancellation.IsCancellationRequested)
        {
            double t = Stopwatch.GetElapsedTime(start).TotalSeconds;
            if (t >= duration.TotalSeconds) break;

            double angle = t * Math.PI; // one revolution every 2 s
            short x = (short)Math.Round(Math.Cos(angle) * short.MaxValue);
            short y = (short)Math.Round(Math.Sin(angle) * short.MaxValue);
            var state = new PadState(
                ButtonCycle[(int)(t * 4) % ButtonCycle.Length],
                Triangle(t), Triangle(t + 1),
                x, y,       // left stick counter-clockwise
                x, (short)-y); // right stick clockwise
            pad.Submit(in state);

            // ~100 Hz is plenty for a visual check; waiting on the token keeps Ctrl+C instant.
            cancellation.WaitHandle.WaitOne(10);
        }

        pad.Submit(default);
        log.Info("Test pattern finished, unplugging the controller.");
    }

    // Plug-in occasionally fails transiently (seen once on the first plug-in of a session); the server retries on the
    // phone's next packet, the one-shot test pattern retries here.
    private static IVirtualPad CreateWithRetry(IVirtualPadFactory factory, PadFeedbackHandler onFeedback, Logger log)
    {
        for (int attempt = 1; ; attempt++)
        {
            try
            {
                return factory.Create(onFeedback);
            }
            catch (Exception e) when (attempt < 3)
            {
                log.Warn($"Plugging in the controller failed, retrying: {ViGEmPadFactory.Describe(e)}");
                Thread.Sleep(500);
            }
        }
    }

    /// <summary>0 → 255 → 0 over two seconds.</summary>
    private static byte Triangle(double t)
    {
        double phase = t % 2.0;
        return (byte)Math.Round((phase < 1.0 ? phase : 2.0 - phase) * 255);
    }
}
