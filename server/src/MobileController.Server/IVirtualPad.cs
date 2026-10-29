namespace MobileController.Server;

/// <summary>
/// Rumble / player-LED change reported by the driver. Raised on a driver-owned thread, possibly after the pad was
/// disposed, so handlers must be non-blocking and must not throw. A DualShock 4 has no XInput player, so its
/// <c>ledNumber</c> is always <see cref="Protocol.UnknownPlayer"/>.
/// </summary>
internal delegate void PadFeedbackHandler(byte largeMotor, byte smallMotor, byte ledNumber);

/// <summary>Which controller a phone appears as.</summary>
internal enum PadKind
{
    /// <summary>Xbox 360 (XInput): every PC game with controller support sees it.</summary>
    Xbox360,

    /// <summary>DualShock 4 (DirectInput / Steam / PlayStation-aware games): PlayStation button prompts, but games that only
    /// speak XInput do not see it.</summary>
    DualShock4,
}

/// <summary>A plugged-in virtual controller.</summary>
internal interface IVirtualPad : IDisposable
{
    PadKind Kind { get; }

    /// <summary>Pushes a complete report (in XUSB layout) to the device. Hot path: called only from the receive thread, must not allocate.</summary>
    void Submit(in PadState state);
}

internal interface IVirtualPadFactory : IDisposable
{
    /// <summary>Creates and plugs in a pad. Slow (driver round trip) and allocating, so only used on connect.</summary>
    IVirtualPad Create(PadKind kind, PadFeedbackHandler onFeedback);
}
