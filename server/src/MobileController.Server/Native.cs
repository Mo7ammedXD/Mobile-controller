using System.Runtime.InteropServices;

namespace MobileController.Server;

internal static partial class Native
{
    // processthreadsapi.h
    private const int ProcessPowerThrottling = 4; // PROCESS_INFORMATION_CLASS
    private const uint PowerThrottlingCurrentVersion = 1;
    public const uint PowerThrottlingExecutionSpeed = 0x1;

    [StructLayout(LayoutKind.Sequential)]
    public struct PowerThrottlingState
    {
        public uint Version;
        public uint ControlMask;
        public uint StateMask;
    }

    /// <summary>
    /// Opts the process out of EcoQoS: with ControlMask = EXECUTION_SPEED and StateMask = 0, Windows 11 must not move
    /// the receive thread to efficiency cores or lower its clock when the console window is in the background.
    /// Returns 0 or the Win32 error code.
    /// </summary>
    public static int DisablePowerThrottling()
    {
        var state = new PowerThrottlingState
        {
            Version = PowerThrottlingCurrentVersion,
            ControlMask = PowerThrottlingExecutionSpeed,
            StateMask = 0,
        };
        return SetProcessInformation(GetCurrentProcess(), ProcessPowerThrottling, ref state, (uint)Marshal.SizeOf<PowerThrottlingState>())
            ? 0
            : Marshal.GetLastPInvokeError();
    }

    public static bool TryGetPowerThrottling(out PowerThrottlingState state)
    {
        state = new PowerThrottlingState { Version = PowerThrottlingCurrentVersion };
        return GetProcessInformation(GetCurrentProcess(), ProcessPowerThrottling, ref state, (uint)Marshal.SizeOf<PowerThrottlingState>());
    }

    // winnt.h / winerror.h
    private const uint ProcessQueryLimitedInformation = 0x1000;
    private const int ErrorInsufficientBuffer = 122;

    /// <summary>
    /// Full image path of the process that owns the foreground window, or null when there is none (secure desktop,
    /// lock screen) or it cannot be read (protected process, or it exited meanwhile).
    /// </summary>
    public static string? GetForegroundProcessImagePath()
    {
        nint window = GetForegroundWindow();
        if (window == 0 || GetWindowThreadProcessId(window, out uint processId) == 0) return null;

        // The limited right is granted even for elevated processes, which PROCESS_QUERY_INFORMATION is not.
        nint process = OpenProcess(ProcessQueryLimitedInformation, false, processId);
        if (process == 0) return null;
        try
        {
            string? path = QueryImageName(process, stackalloc char[1024]);
            // Only a long-path-aware install exceeds 1024 chars; retry once at the 32767 maximum instead of always
            // paying for that buffer.
            return path is null && Marshal.GetLastPInvokeError() == ErrorInsufficientBuffer
                ? QueryImageName(process, new char[32_768])
                : path;
        }
        finally
        {
            CloseHandle(process);
        }
    }

    private static unsafe string? QueryImageName(nint process, Span<char> buffer)
    {
        uint length = (uint)buffer.Length;
        fixed (char* chars = buffer)
        {
            if (!QueryFullProcessImageNameW(process, 0, chars, ref length)) return null;
        }
        return new string(buffer[..(int)length]);
    }

    [LibraryImport("user32.dll")]
    private static partial nint GetForegroundWindow();

    [LibraryImport("user32.dll")]
    private static partial uint GetWindowThreadProcessId(nint window, out uint processId);

    [LibraryImport("kernel32.dll", SetLastError = true)]
    private static partial nint OpenProcess(uint desiredAccess, [MarshalAs(UnmanagedType.Bool)] bool inheritHandle, uint processId);

    [LibraryImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static unsafe partial bool QueryFullProcessImageNameW(nint process, uint flags, char* exeName, ref uint size);

    [LibraryImport("kernel32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool CloseHandle(nint handle);

    [LibraryImport("kernel32.dll")]
    private static partial nint GetCurrentProcess();

    [LibraryImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool SetProcessInformation(nint process, int informationClass, ref PowerThrottlingState information, uint size);

    [LibraryImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool GetProcessInformation(nint process, int informationClass, ref PowerThrottlingState information, uint size);
}
