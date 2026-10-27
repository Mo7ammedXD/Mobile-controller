namespace MobileController.Server.Tests;

public sealed class NativeTests
{
    [Fact]
    public void Process_opts_out_of_EcoQoS_execution_speed_throttling()
    {
        Assert.Equal(0, Native.DisablePowerThrottling());

        // Reading the state back proves the struct layout and information class are right, not just that the call
        // returned TRUE.
        Assert.True(Native.TryGetPowerThrottling(out var state));
        Assert.Equal(Native.PowerThrottlingExecutionSpeed, state.ControlMask & Native.PowerThrottlingExecutionSpeed);
        Assert.Equal(0u, state.StateMask & Native.PowerThrottlingExecutionSpeed);
    }

    [Fact]
    public void Foreground_process_is_unknown_or_a_full_image_path()
    {
        // A session without a desktop (CI service, locked screen) has no foreground window: null is a valid answer.
        string? path = Native.GetForegroundProcessImagePath();
        if (path is not null) Assert.True(Path.IsPathFullyQualified(path), path);
    }
}
