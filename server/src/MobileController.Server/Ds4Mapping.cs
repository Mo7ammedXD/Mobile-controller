namespace MobileController.Server;

/// <summary>One complete DualShock 4 report as ViGEmBus takes it (DS4_REPORT without the touchpad and motion fields).</summary>
internal readonly record struct Ds4Report(
    ushort Buttons, byte Special, byte LeftTrigger, byte RightTrigger, byte ThumbLX, byte ThumbLY, byte ThumbRX, byte ThumbRY)
{
    /// <summary>The D-pad hat in the low nibble of <see cref="Buttons"/>: 0 = north, clockwise to 7 = north-west, 8 = released.</summary>
    public int Hat => Buttons & 0xF;
}

/// <summary>
/// The phone sends the Xbox (XUSB) layout; a DualShock 4 pad gets the same controls in their PlayStation places:
/// A → Cross, B → Circle, X → Square, Y → Triangle, LB/RB → L1/R1, the triggers → L2/R2 (analog, plus the button bit
/// a real DS4 sets while they are pressed), Back → Share, Start → Options, Guide → PS, L3/R3, and the D-pad bits →
/// the hat. Sticks become bytes centred on 128, with Y growing downwards as on a real DS4.
/// Pure and table-driven, so mapping a packet never allocates.
/// </summary>
internal static class Ds4Mapping
{
    // DS4_BUTTONS (ViGEm's ds4 report) and DS4_SPECIAL_BUTTONS.
    public const ushort Square = 0x0010;
    public const ushort Cross = 0x0020;
    public const ushort Circle = 0x0040;
    public const ushort Triangle = 0x0080;
    public const ushort ShoulderLeft = 0x0100;
    public const ushort ShoulderRight = 0x0200;
    public const ushort TriggerLeft = 0x0400;
    public const ushort TriggerRight = 0x0800;
    public const ushort Share = 0x1000;
    public const ushort Options = 0x2000;
    public const ushort ThumbLeft = 0x4000;
    public const ushort ThumbRight = 0x8000;
    public const byte SpecialPs = 0x01;

    public const int HatNone = 8;

    // XINPUT_GAMEPAD_* (PROTOCOL.md §3.1).
    private const ushort XUp = 0x0001, XDown = 0x0002, XLeft = 0x0004, XRight = 0x0008;
    private const ushort XStart = 0x0010, XBack = 0x0020, XL3 = 0x0040, XR3 = 0x0080;
    private const ushort XLB = 0x0100, XRB = 0x0200, XGuide = 0x0400;
    private const ushort XA = 0x1000, XB = 0x2000, XX = 0x4000, XY = 0x8000;

    /// <summary>
    /// Hat value per XUSB D-pad nibble (up = 1, down = 2, left = 4, right = 8). Opposite directions cancel each other,
    /// like the cross of a real D-pad: up + down is no vertical direction at all.
    /// </summary>
    private static readonly byte[] HatOf =
    [
        HatNone, // none
        0,       // up
        4,       // down
        HatNone, // up + down
        6,       // left
        7,       // up + left
        5,       // down + left
        6,       // left (up + down cancel)
        2,       // right
        1,       // up + right
        3,       // down + right
        2,       // right (up + down cancel)
        HatNone, // left + right
        0,       // up (left + right cancel)
        4,       // down (left + right cancel)
        HatNone, // everything cancels
    ];

    public static Ds4Report Map(in PadState s)
    {
        ushort x = s.Buttons;
        int buttons = HatOf[x & 0xF];
        if ((x & XA) != 0) buttons |= Cross;
        if ((x & XB) != 0) buttons |= Circle;
        if ((x & XX) != 0) buttons |= Square;
        if ((x & XY) != 0) buttons |= Triangle;
        if ((x & XLB) != 0) buttons |= ShoulderLeft;
        if ((x & XRB) != 0) buttons |= ShoulderRight;
        if ((x & XBack) != 0) buttons |= Share;
        if ((x & XStart) != 0) buttons |= Options;
        if ((x & XL3) != 0) buttons |= ThumbLeft;
        if ((x & XR3) != 0) buttons |= ThumbRight;
        if (s.LeftTrigger > 0) buttons |= TriggerLeft;
        if (s.RightTrigger > 0) buttons |= TriggerRight;
        return new Ds4Report(
            (ushort)buttons,
            (x & XGuide) != 0 ? SpecialPs : (byte)0,
            s.LeftTrigger,
            s.RightTrigger,
            Axis(s.ThumbLX),
            Axis(Negate(s.ThumbLY)),
            Axis(s.ThumbRX),
            Axis(Negate(s.ThumbRY)));
    }

    /// <summary>-32768..32767 → 0..255 with 0 → 128 exactly (a resting stick is centred, not one step off).</summary>
    public static byte Axis(short v) => (byte)((v + 32768) >> 8);

    /// <summary>XInput's +Y is up, a DS4's is down; -(-32768) does not fit a short, so it saturates.</summary>
    private static short Negate(short v) => v == short.MinValue ? short.MaxValue : (short)-v;
}
