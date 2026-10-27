using System.Runtime.InteropServices;

namespace MobileController.Server.Tests;

/// <summary>Reads back what games see. <see cref="PadState"/> has exactly the XINPUT_GAMEPAD layout.</summary>
internal static unsafe partial class XInput
{
    public const uint ErrorSuccess = 0;
    public const uint ErrorDeviceNotConnected = 1167;
    public const int MaxUsers = 4;

    [StructLayout(LayoutKind.Sequential)]
    public struct State
    {
        public uint PacketNumber;
        public PadState Gamepad;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct Vibration
    {
        public ushort LeftMotorSpeed;
        public ushort RightMotorSpeed;
    }

    public static uint GetState(uint userIndex, out State state)
    {
        State local;
        uint result = XInputGetState(userIndex, &local);
        state = local;
        return result;
    }

    public static uint SetState(uint userIndex, Vibration vibration) => XInputSetState(userIndex, &vibration);

    // Pointers instead of out/ref: PadState lives in another assembly, so the source generator would otherwise
    // demand DisableRuntimeMarshalling for it.
    [LibraryImport("xinput1_4.dll")]
    private static partial uint XInputGetState(uint userIndex, State* state);

    [LibraryImport("xinput1_4.dll")]
    private static partial uint XInputSetState(uint userIndex, Vibration* vibration);
}
