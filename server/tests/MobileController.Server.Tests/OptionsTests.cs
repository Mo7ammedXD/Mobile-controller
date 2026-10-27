using System.Net;

namespace MobileController.Server.Tests;

public sealed class OptionsTests
{
    [Fact]
    public void Defaults()
    {
        Assert.True(Options.TryParse([], out var o, out _));
        Assert.Equal(24680, o.Port);
        Assert.Equal(IPAddress.Any, o.BindAddress);
        Assert.Equal(4, o.MaxPads);
        Assert.Equal(500, o.NeutralTimeoutMs);
        Assert.Equal(10_000, o.DisconnectTimeoutMs);
        Assert.Empty(o.Allow);
        Assert.False(o.DryRun || o.Verbose || o.HighPriority || o.Help || o.NoGameDetect);
        Assert.Null(o.TestPatternSeconds);
        Assert.Equal(100, o.FeedbackIntervalMs);
        Assert.Equal(250, o.ServerFullIntervalMs);
        Assert.Equal(2000, o.GameIntervalMs);
        Assert.Equal(500, o.GamePollMs);
    }

    [Fact]
    public void Every_option()
    {
        string[] args =
        [
            "--port", "1234", "--bind", "192.168.1.5", "--max-pads", "8", "--neutral-timeout", "250",
            "--disconnect-timeout=3000", "--allow", "10.0.0.2", "--allow=10.0.0.3", "--dry-run", "--verbose", "--high-priority",
            "--no-game-detect",
        ];
        Assert.True(Options.TryParse(args, out var o, out string? error), error);
        Assert.Equal(1234, o.Port);
        Assert.Equal(IPAddress.Parse("192.168.1.5"), o.BindAddress);
        Assert.Equal(8, o.MaxPads);
        Assert.Equal(250, o.NeutralTimeoutMs);
        Assert.Equal(3000, o.DisconnectTimeoutMs);
        Assert.Equal([IPAddress.Parse("10.0.0.2"), IPAddress.Parse("10.0.0.3")], o.Allow);
        Assert.True(o.DryRun && o.Verbose && o.HighPriority && o.NoGameDetect);
    }

    [Fact]
    public void No_game_detect_combines_with_the_test_pattern()
    {
        Assert.True(Options.TryParse(["--test-pattern", "--no-game-detect"], out var o, out string? error), error);
        Assert.True(o.NoGameDetect);
        Assert.Equal(30, o.TestPatternSeconds);
    }

    [Theory]
    [InlineData(new[] { "--test-pattern" }, 30)]
    [InlineData(new[] { "--test-pattern", "5" }, 5)]
    [InlineData(new[] { "--test-pattern=7" }, 7)]
    [InlineData(new[] { "--test-pattern", "--verbose" }, 30)]
    public void Test_pattern_seconds_are_optional(string[] args, int seconds)
    {
        Assert.True(Options.TryParse(args, out var o, out _));
        Assert.Equal(seconds, o.TestPatternSeconds);
    }

    [Theory]
    [InlineData("-h")]
    [InlineData("--help")]
    [InlineData("/?")]
    public void Help(string arg)
    {
        Assert.True(Options.TryParse([arg], out var o, out _));
        Assert.True(o.Help);
    }

    [Theory]
    [InlineData("--port", "0")]
    [InlineData("--port", "65536")]
    [InlineData("--port", "abc")]
    [InlineData("--port", "+5")]
    [InlineData("--port")]
    [InlineData("--max-pads", "0")]
    [InlineData("--max-pads", "9")]
    [InlineData("--neutral-timeout", "-5")]
    [InlineData("--disconnect-timeout", "0")]
    [InlineData("--bind", "10")]
    [InlineData("--bind", "::1")]
    [InlineData("--bind", "localhost")]
    [InlineData("--allow", "300.1.1.1")]
    [InlineData("--allow")]
    [InlineData("--test-pattern", "0")]
    [InlineData("--verbose=1")]
    [InlineData("--no-game-detect=1")]
    [InlineData("--bogus")]
    [InlineData("8080")]
    [InlineData("--dry-run", "--test-pattern")]
    public void Bad_arguments_are_rejected_with_a_message(params string[] args)
    {
        Assert.False(Options.TryParse(args, out var o, out string? error));
        Assert.Null(o);
        Assert.False(string.IsNullOrWhiteSpace(error));
    }

    [Fact]
    public void Usage_lists_every_option()
    {
        foreach (string option in new[] { "--port", "--bind", "--max-pads", "--neutral-timeout", "--disconnect-timeout",
                     "--allow", "--no-game-detect", "--dry-run", "--test-pattern", "--verbose", "--high-priority", "--help" })
            Assert.Contains(option, Options.Usage, StringComparison.Ordinal);
    }
}
