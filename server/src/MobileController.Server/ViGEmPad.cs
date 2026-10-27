using Nefarius.ViGEm.Client;
using Nefarius.ViGEm.Client.Targets;
using Nefarius.ViGEm.Client.Targets.Xbox360;

namespace MobileController.Server;

/// <summary>Owns the single ViGEmBus connection. The constructor throws the Vigem*Exception family when the bus is unusable.</summary>
internal sealed class ViGEmPadFactory : IVirtualPadFactory
{
    private readonly ViGEmClient _client = new();

    public IVirtualPad Create(PadFeedbackHandler onFeedback) => new ViGEmPad(_client.CreateXbox360Controller(), onFeedback);

    /// <summary>Closing the bus handle also makes the driver unplug any pad this process still owns.</summary>
    public void Dispose() => _client.Dispose();

    /// <summary>
    /// The wrapper turns unmapped VIGEM_ERROR codes (e.g. VIGEM_ERROR_WINAPI while plugging in) into
    /// <c>Win32Exception(GetLastWin32Error())</c>, but its imports do not set SetLastError, so the text would read
    /// "The operation completed successfully".
    /// </summary>
    public static string Describe(Exception e) =>
        e is System.ComponentModel.Win32Exception { NativeErrorCode: 0 }
            ? "ViGEmBus reported an unspecified error (usually transient, e.g. while Windows sets up a new device)"
            : $"{e.Message} ({e.GetType().Name})";
}

internal sealed class ViGEmPad : IVirtualPad
{
    private readonly IXbox360Controller _pad;
    private readonly PadFeedbackHandler _onFeedback;

    public ViGEmPad(IXbox360Controller pad, PadFeedbackHandler onFeedback)
    {
        _pad = pad;
        _onFeedback = onFeedback;
        // One driver call per packet instead of one per Set* call.
        pad.AutoSubmitReport = false;
        // Subscribe before Connect: the driver reports the player LED right after plug-in.
        pad.FeedbackReceived += OnFeedbackReceived;
        try
        {
            pad.Connect();
        }
        catch
        {
            pad.FeedbackReceived -= OnFeedbackReceived;
            (pad as IDisposable)?.Dispose();
            throw;
        }
    }

    public void Submit(in PadState state)
    {
        // Writes go straight into the wrapper's XUSB_REPORT through ref-returning properties; SubmitReport passes that
        // blittable struct by value to vigem_target_x360_update. No managed allocation on this path.
        _pad.SetButtonsFull(state.Buttons);
        _pad.LeftTrigger = state.LeftTrigger;
        _pad.RightTrigger = state.RightTrigger;
        _pad.LeftThumbX = state.ThumbLX;
        _pad.LeftThumbY = state.ThumbLY;
        _pad.RightThumbX = state.ThumbRX;
        _pad.RightThumbY = state.ThumbRY;
        _pad.SubmitReport();
    }

    public void Dispose()
    {
        _pad.FeedbackReceived -= OnFeedbackReceived;
        try
        {
            // The wrapper's Dispose only frees the native target; Disconnect is what unplugs it (and unregisters the
            // notification callback).
            _pad.Disconnect();
        }
        finally
        {
            (_pad as IDisposable)?.Dispose();
        }
    }

    private void OnFeedbackReceived(object sender, Xbox360FeedbackReceivedEventArgs e)
    {
        // Invoked through a reverse P/Invoke from vigemclient's notification thread: an exception escaping here
        // would tear down the process.
        try
        {
            _onFeedback(e.LargeMotor, e.SmallMotor, e.LedNumber);
        }
        catch (Exception)
        {
            // Feedback is best effort; the 100 ms heartbeat resends the current state anyway.
        }
    }
}
