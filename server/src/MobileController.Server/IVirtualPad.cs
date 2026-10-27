namespace MobileController.Server;

/// <summary>
/// Rumble / player-LED change reported by the driver. Raised on a driver-owned thread, possibly after the pad was
/// disposed, so handlers must be non-blocking and must not throw.
/// </summary>
internal delegate void PadFeedbackHandler(byte largeMotor, byte smallMotor, byte ledNumber);

/// <summary>A plugged-in virtual Xbox 360 controller.</summary>
internal interface IVirtualPad : IDisposable
{
    /// <summary>Pushes a complete report to the device. Hot path: called only from the receive thread, must not allocate.</summary>
    void Submit(in PadState state);
}

internal interface IVirtualPadFactory : IDisposable
{
    /// <summary>Creates and plugs in a pad. Slow (driver round trip) and allocating, so only used on connect.</summary>
    IVirtualPad Create(PadFeedbackHandler onFeedback);
}
