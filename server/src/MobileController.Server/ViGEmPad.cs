using Nefarius.ViGEm.Client;
using Nefarius.ViGEm.Client.Targets;
using Nefarius.ViGEm.Client.Targets.Xbox360;

namespace MobileController.Server;

/// <summary>Owns the single ViGEmBus connection. The constructor throws the Vigem*Exception family when the bus is unusable.</summary>
internal sealed class ViGEmPadFactory : IVirtualPadFactory
{
    private readonly ViGEmClient _client = new();
    private readonly Lock _gate = new();
    private readonly List<ViGEmDs4Pad> _ds4 = [];

    public IVirtualPad Create(PadKind kind, PadFeedbackHandler onFeedback)
    {
        if (kind != PadKind.DualShock4) return new ViGEmXbox360Pad(_client.CreateXbox360Controller(), onFeedback);
        var pad = new ViGEmDs4Pad(_client.CreateDualShock4Controller(), onFeedback);
        lock (_gate)
        {
            _ds4.RemoveAll(p => p.Finished);
            _ds4.Add(pad);
        }
        return pad;
    }

    /// <summary>Closing the bus handle also makes the driver unplug any pad this process still owns.</summary>
    public void Dispose()
    {
        // A DS4's output thread may still be waiting on the bus (at most one poll interval): let it finish first.
        ViGEmDs4Pad[] pads;
        lock (_gate) pads = [.. _ds4];
        foreach (var pad in pads)
        {
            pad.Dispose();
            pad.WaitFinished(TimeSpan.FromSeconds(1));
        }
        _client.Dispose();
    }

    /// <summary>
    /// The wrapper turns unmapped VIGEM_ERROR codes (e.g. VIGEM_ERROR_WINAPI while plugging in) into
    /// <c>Win32Exception(GetLastWin32Error())</c>, but its imports do not set SetLastError, so the text would read
    /// "The operation completed successfully".
    /// </summary>
    public static string Describe(Exception e) =>
        e is System.ComponentModel.Win32Exception { NativeErrorCode: 0 }
            ? "ViGEmBus reported an unspecified error (usually transient, e.g. while Windows sets up a new device)"
            : $"{e.Message} ({e.GetType().Name})";

    /// <summary>Connects <paramref name="pad"/>, disposing it if that fails.</summary>
    internal static void Connect(IVirtualGamepad pad, Action unsubscribe)
    {
        try
        {
            pad.Connect();
        }
        catch
        {
            unsubscribe();
            (pad as IDisposable)?.Dispose();
            throw;
        }
    }

    internal static void Disconnect(IVirtualGamepad pad)
    {
        try
        {
            // The wrapper's Dispose only frees the native target; Disconnect is what unplugs it (and unregisters the
            // notification callback).
            pad.Disconnect();
        }
        finally
        {
            (pad as IDisposable)?.Dispose();
        }
    }
}

internal sealed class ViGEmXbox360Pad : IVirtualPad
{
    private readonly IXbox360Controller _pad;
    private readonly PadFeedbackHandler _onFeedback;
    // The wrapper's report is written by Submit (receive thread) and Prime (also a pool thread, once).
    private readonly Lock _report = new();
    private int _attached;

    public ViGEmXbox360Pad(IXbox360Controller pad, PadFeedbackHandler onFeedback)
    {
        _pad = pad;
        _onFeedback = onFeedback;
        // One driver call per packet instead of one per Set* call.
        pad.AutoSubmitReport = false;
        // Subscribe before Connect: the driver reports the player LED right after plug-in.
        pad.FeedbackReceived += OnFeedbackReceived;
        ViGEmPadFactory.Connect(pad, () => pad.FeedbackReceived -= OnFeedbackReceived);
        Prime();
    }

    public PadKind Kind => PadKind.Xbox360;

    public void Submit(in PadState state)
    {
        // Writes go straight into the wrapper's XUSB_REPORT through ref-returning properties; SubmitReport passes that
        // blittable struct by value to vigem_target_x360_update. No managed allocation on this path (an uncontended
        // Lock does not allocate either).
        lock (_report)
        {
            _pad.SetButtonsFull(state.Buttons);
            _pad.LeftTrigger = state.LeftTrigger;
            _pad.RightTrigger = state.RightTrigger;
            _pad.LeftThumbX = state.ThumbLX;
            _pad.LeftThumbY = state.ThumbLY;
            _pad.RightThumbX = state.ThumbRX;
            _pad.RightThumbY = state.ThumbRY;
            _pad.SubmitReport();
        }
    }

    /// <summary>
    /// Until a fresh pad gets a report, XInput reads three of its stick axes off centre (about -3300), and a report equal
    /// to the previous one (all zero at first) does not clear that: a different one does. So the current report goes out
    /// again after a one-off nudge, at plug-in and once more when Windows assigns the player, because a nudge sent while
    /// the device is still starting can be lost. Without this, a phone whose first state is neutral would drift.
    /// </summary>
    private void Prime()
    {
        lock (_report)
        {
            short x = _pad.LeftThumbX;
            _pad.LeftThumbX = (short)(x ^ 1);
            _pad.SubmitReport();
            _pad.LeftThumbX = x;
            _pad.SubmitReport();
        }
    }

    public void Dispose()
    {
        _pad.FeedbackReceived -= OnFeedbackReceived;
        ViGEmPadFactory.Disconnect(_pad);
    }

    private void OnFeedbackReceived(object sender, Xbox360FeedbackReceivedEventArgs e)
    {
        // Invoked through a reverse P/Invoke from vigemclient's notification thread: an exception escaping here
        // would tear down the process.
        try
        {
            // Not from this thread: the driver is still waiting for the callback to return.
            if (e.LedNumber <= 3 && Interlocked.Exchange(ref _attached, 1) == 0)
                ThreadPool.UnsafeQueueUserWorkItem(static pad => pad.PrimeAttached(), this, preferLocal: false);
            _onFeedback(e.LargeMotor, e.SmallMotor, e.LedNumber);
        }
        catch (Exception)
        {
            // Feedback is best effort; the 100 ms heartbeat resends the current state anyway.
        }
    }

    private void PrimeAttached()
    {
        try
        {
            Prime();
        }
        catch (Exception)
        {
            // Unplugged in the meantime.
        }
    }
}

/// <summary>
/// A virtual DualShock 4: the phone's Xbox-layout report mapped by <see cref="Ds4Mapping"/>.
///
/// Rumble comes back as the game's raw HID output reports. The wrapper's DS4 FeedbackReceived event is deprecated
/// ("might not behave as expected"), so a small thread per pad waits for those reports instead and reads the motor
/// bytes. That thread (not the receive thread) also frees the native target once it stopped waiting, so unplugging
/// never blocks the input loop and nothing calls into a freed handle.
/// </summary>
internal sealed class ViGEmDs4Pad : IVirtualPad
{
    private const int OutputPollMs = 100;

    private readonly IDualShock4Controller _pad;
    private readonly PadFeedbackHandler _onFeedback;
    private readonly ManualResetEventSlim _finished = new();
    private volatile bool _disposed;

    public ViGEmDs4Pad(IDualShock4Controller pad, PadFeedbackHandler onFeedback)
    {
        _pad = pad;
        _onFeedback = onFeedback;
        pad.AutoSubmitReport = false;
        ViGEmPadFactory.Connect(pad, () => { });
        new Thread(ReadOutputReports) { IsBackground = true, Name = "DS4 output reports", Priority = ThreadPriority.AboveNormal }.Start();
    }

    /// <summary>The output thread has freed the native target.</summary>
    public bool Finished => _finished.IsSet;

    public void WaitFinished(TimeSpan timeout) => _finished.Wait(timeout);

    public PadKind Kind => PadKind.DualShock4;

    public void Submit(in PadState state)
    {
        var r = Ds4Mapping.Map(state);
        // SetButtonsFull and SetSpecialButtonsFull are plain stores into the wrapper's DS4_REPORT (the word includes
        // the D-pad hat in its low nibble), the rest are ref-returning fields: one driver call, no allocation.
        _pad.SetButtonsFull(r.Buttons);
        _pad.SetSpecialButtonsFull(r.Special);
        _pad.LeftTrigger = r.LeftTrigger;
        _pad.RightTrigger = r.RightTrigger;
        _pad.LeftThumbX = r.ThumbLX;
        _pad.LeftThumbY = r.ThumbLY;
        _pad.RightThumbX = r.ThumbRX;
        _pad.RightThumbY = r.ThumbRY;
        _pad.SubmitReport();
    }

    /// <summary>Unplugs at once; the output thread frees the native target when it notices. Safe to call twice.</summary>
    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        try
        {
            _pad.Disconnect();
        }
        catch (Exception)
        {
            // Already gone (the bus closed, or the device was removed): nothing left to unplug.
        }
    }

    private void ReadOutputReports()
    {
        try
        {
            while (!_disposed)
            {
                byte[] report;
                try
                {
                    var raw = _pad.AwaitRawOutputReport(OutputPollMs, out bool timedOut);
                    if (timedOut || _disposed) continue;
                    report = raw as byte[] ?? raw.ToArray();
                }
                catch (Exception) when (!_disposed)
                {
                    // The driver refused this wait (e.g. while Windows sets the device up); rumble is best effort.
                    Thread.Sleep(OutputPollMs);
                    continue;
                }
                if (TryReadMotors(report, out byte large, out byte small))
                {
                    // No XInput user index exists for a DS4: the phone shows no player number for it.
                    _onFeedback(large, small, Protocol.UnknownPlayer);
                }
            }
        }
        catch (Exception)
        {
            // Unplugged while waiting, or a feedback handler bug: either way this pad's rumble is over.
        }
        finally
        {
            (_pad as IDisposable)?.Dispose();
            _finished.Set();
        }
    }

    /// <summary>
    /// The motor bytes of a DS4 output report, if it sets them: USB report 0x05 has its flags at 1 and the light (right)
    /// and heavy (left) motor at 4 and 5; the Bluetooth layout 0x11 has them at 3, 6 and 7. A report that only changes
    /// the light bar (flag bit 0 clear) leaves the rumble alone.
    /// </summary>
    internal static bool TryReadMotors(ReadOnlySpan<byte> report, out byte large, out byte small)
    {
        (int flags, int light, int heavy) = report.Length > 0 && report[0] == 0x11 ? (3, 6, 7) : (1, 4, 5);
        if (report.Length <= heavy || (report[flags] & 0x01) == 0)
        {
            large = small = 0;
            return false;
        }
        large = report[heavy];
        small = report[light];
        return true;
    }
}
