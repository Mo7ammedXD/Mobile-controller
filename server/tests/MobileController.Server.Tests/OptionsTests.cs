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
    [InlineData("--name", "")]
    [InlineData("--name", "   ")]
    [InlineData("--name", "tab\there")]
    [InlineData("--config", "a.json", "--no-config")]
    [InlineData("--json")]
    [InlineData("--exit-with-pipe")]
    [InlineData("--control-pipe", "no spaces allowed")]
    [InlineData("--control-pipe", "../escape")]
    [InlineData("--default-output", "ps5")]
    public void Bad_arguments_are_rejected_with_a_message(params string[] args)
    {
        Assert.False(Options.TryParse(args, out var o, out string? error));
        Assert.Null(o);
        Assert.False(string.IsNullOrWhiteSpace(error));
    }

    [Fact]
    public void Name_log_file_and_diagnose()
    {
        Assert.True(Options.TryParse(["--name", "  Living room PC ", "--log-file", "x.log", "--diagnose", "--json"], out var o, out string? error), error);
        Assert.Equal("Living room PC", o.ServerName);
        Assert.Equal("x.log", o.LogFile);
        Assert.True(o.Diagnose && o.Json);

        Assert.True(Options.TryParse(["--log-file", "--verbose"], out o, out error), error);
        Assert.Equal(Options.DefaultLogFile, o.LogFile);
        Assert.True(o.Verbose);
        Assert.False(Options.TryParse(["--name", new string('x', Options.MaxServerNameChars + 1)], out _, out _));
    }

    [Fact]
    public void Control_pipe_name_is_optional()
    {
        Assert.True(Options.TryParse(["--control-pipe", "--exit-with-pipe"], out var o, out string? error), error);
        Assert.Equal(MobileController.Control.ControlProtocol.DefaultPipeName, o.ControlPipe);
        Assert.True(o.ExitWithPipe);
        Assert.True(Options.TryParse(["--control-pipe", "MobileController-1234"], out o, out error), error);
        Assert.Equal("MobileController-1234", o.ControlPipe);
        Assert.False(o.ExitWithPipe);
    }

    [Fact]
    public void Usage_lists_every_option()
    {
        foreach (string option in new[] { "--port", "--bind", "--max-pads", "--neutral-timeout", "--disconnect-timeout",
                     "--allow", "--name", "--no-game-detect", "--dry-run", "--test-pattern", "--verbose", "--high-priority",
                     "--log-file", "--config", "--no-config", "--diagnose", "--default-output", "--control-pipe", "--exit-with-pipe", "--help" })
            Assert.Contains(option, Options.Usage, StringComparison.Ordinal);
    }

    // --- Settings file -------------------------------------------------------------------------------------------

    private static (Options? Options, List<string> Warnings, string? Error) Load(string json)
    {
        string path = Path.Combine(Path.GetTempPath(), $"mc-config-{Guid.NewGuid():N}.json");
        File.WriteAllText(path, json);
        try
        {
            var warnings = new List<string>();
            bool ok = ServerConfig.TryLoad(path, new Options(), out var options, warnings, out string? error);
            Assert.Equal(ok, options is not null);
            return (options, warnings, error);
        }
        finally
        {
            File.Delete(path);
        }
    }

    [Fact]
    public void Settings_file_values_apply_and_the_command_line_wins()
    {
        var (file, warnings, error) = Load("""
            {
              // Comments and trailing commas are fine: people edit this file by hand.
              "schema": 1,
              "port": 1111,
              "maxPads": 2,
              "allow": ["10.0.0.9"],
              "name": "Den",
              "gameDetect": false,
              "colour": "blue",
            }
            """);
        Assert.Null(error);
        Assert.Equal(1111, file!.Port);
        Assert.Equal(2, file.MaxPads);
        Assert.Equal([IPAddress.Parse("10.0.0.9")], file.Allow);
        Assert.Equal("Den", file.ServerName);
        Assert.True(file.NoGameDetect);
        Assert.Contains(warnings, w => w.Contains("colour", StringComparison.Ordinal));

        Assert.True(Options.TryParse(["--port", "2222"], file, out var merged, out error), error);
        Assert.Equal(2222, merged.Port);
        Assert.Equal(2, merged.MaxPads);
        Assert.Equal([IPAddress.Parse("10.0.0.9")], merged.Allow);

        // Any --allow replaces the file's list instead of adding to it.
        Assert.True(Options.TryParse(["--allow", "10.0.0.1"], file, out merged, out error), error);
        Assert.Equal([IPAddress.Parse("10.0.0.1")], merged.Allow);
    }

    [Theory]
    [InlineData("""{ "port": 0 }""", "port")]
    [InlineData("""{ "maxPads": 12 }""", "maxPads")]
    [InlineData("""{ "bind": "nowhere" }""", "bind")]
    [InlineData("""{ "allow": ["10"] }""", "allow")]
    [InlineData("""{ "name": "" }""", "name")]
    [InlineData("""{ "port": "fast" }""", "JSON")]
    [InlineData("""{ "port": 1 """, "JSON")]
    [InlineData("null", "object")]
    public void Unusable_settings_files_are_errors(string json, string mentions)
    {
        var (options, _, error) = Load(json);
        Assert.Null(options);
        Assert.Contains(mentions, error, StringComparison.Ordinal);
    }

    [Fact]
    public void A_newer_schema_is_a_warning_not_an_error()
    {
        var (options, warnings, _) = Load("""{ "schema": 7, "port": 3333 }""");
        Assert.Equal(3333, options!.Port);
        Assert.Contains(warnings, w => w.Contains("newer version", StringComparison.Ordinal));
    }

    [Fact]
    public void Log_to_file_uses_the_default_path_unless_one_is_given()
    {
        Assert.Equal(Options.DefaultLogFile, Load("""{ "logToFile": true }""").Options!.LogFile);
        Assert.Equal("D:\\mc.log", Load("""{ "logToFile": true, "logFilePath": "D:\\mc.log" }""").Options!.LogFile);
        Assert.Null(Load("""{ "logToFile": false, "logFilePath": "D:\\mc.log" }""").Options!.LogFile);
    }
}
