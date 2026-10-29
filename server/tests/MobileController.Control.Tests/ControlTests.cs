using System.Net;
using MobileController.Control;

namespace MobileController.Control.Tests;

public sealed class ControlProtocolTests
{
    [Fact]
    public void Events_parse_into_their_messages()
    {
        var hello = Assert.IsType<HelloMessage>(ControlProtocol.ParseEvent(
            """{"type":"hello","controlVersion":1,"serverVersion":"1.2.0","protocolVersion":1,"pid":42,"serverName":"Den","port":24680,"bind":"0.0.0.0","maxPads":4,"dryRun":false,"defaultOutput":"ds4","logFile":null,"startedAt":"2026-10-30T10:00:00"}"""));
        Assert.Equal("Den", hello.ServerName);
        Assert.Equal("ds4", hello.DefaultOutput);
        Assert.Null(hello.LogFile);

        var reply = Assert.IsType<ReplyMessage>(ControlProtocol.ParseEvent("""{"type":"reply","id":7,"ok":false,"error":"no controller"}"""));
        Assert.Equal(new ReplyMessage(7, false, "no controller"), reply);
    }

    [Fact]
    public void Unknown_types_and_blank_lines_are_skipped_not_fatal()
    {
        // A newer server may send message types this version does not know.
        Assert.Null(ControlProtocol.ParseEvent("""{"type":"approval","index":0}"""));
        Assert.Null(ControlProtocol.ParseEvent("""{"index":0}"""));
        Assert.Null(ControlProtocol.ParseEvent("   "));
        Assert.Null(ControlProtocol.ParseEvent("[1,2]"));
    }

    [Fact]
    public void Unknown_fields_are_ignored()
    {
        var log = Assert.IsType<LogMessage>(ControlProtocol.ParseEvent("""{"type":"log","time":"2026-10-30T10:00:00","level":"info","message":"hi","color":"red"}"""));
        Assert.Equal("hi", log.Message);
    }

    [Fact]
    public void Commands_round_trip()
    {
        var command = new CommandMessage(3, ControlProtocol.OpOutput, 1, ControlProtocol.KindDualShock4);
        string line = ControlProtocol.Serialize(command);
        Assert.DoesNotContain('\n', line);
        Assert.Equal(command, ControlProtocol.ParseCommand(line));
        // Optional fields may be left out.
        Assert.Equal(new CommandMessage(1, "live"), ControlProtocol.ParseCommand("""{"id":1,"op":"live"}"""));
    }

    [Fact]
    public void Snapshots_round_trip_through_the_writer_format()
    {
        var snapshot = new SnapshotMessage(12.5, 100, 1, 0, 1, new LoopHealth(98, 0.25), new GcHealth(1, 0, 0, 0.5),
            [new PadInfo(0, "192.168.1.5:5000", 3, "0123456789ABCDEF", "Pixel", "Shooter", null, 2, 87, true, false, true, 4.2, 120,
                "ds4", "ds4", null, 0, 0, false, 119.5, 0.4, 0.8, 8.3, 9.1, 12.0, 20.5, 400, 1, 0, 0, 380)]);
        var parsed = Assert.IsType<SnapshotMessage>(ControlProtocol.ParseEvent(ControlProtocol.Serialize(snapshot)));
        Assert.Equal(snapshot.Pads[0], parsed.Pads[0]);
        Assert.Equal(snapshot.Loop, parsed.Loop);
    }
}

public sealed class ServerSettingsTests : IDisposable
{
    private readonly string _path = Path.Combine(Path.GetTempPath(), $"mc-settings-{Guid.NewGuid():N}", "server.json");

    public void Dispose()
    {
        string dir = Path.GetDirectoryName(_path)!;
        if (Directory.Exists(dir)) Directory.Delete(dir, recursive: true);
    }

    [Fact]
    public void Keys_written_by_a_newer_version_survive_a_save()
    {
        Directory.CreateDirectory(Path.GetDirectoryName(_path)!);
        File.WriteAllText(_path, """
            { "schema": 2, "port": 1234, "futureFeature": { "on": true },
              "devices": [ { "id": "0123456789ABCDEF", "alias": "Sam", "glow": "red" } ] }
            """);
        ServerSettings settings;
        using (var stream = File.OpenRead(_path)) settings = ServerSettings.Read(stream)!;
        settings.Name = "Den";
        ServerSettings.Save(_path, settings);

        string json = File.ReadAllText(_path);
        Assert.Contains("futureFeature", json);
        Assert.Contains("\"glow\"", json);
        Assert.Contains("\"Den\"", json);
        Assert.False(File.Exists(_path + ".tmp"));
    }

    [Fact]
    public void Clone_is_deep()
    {
        var a = new ServerSettings { Devices = [new DeviceSettings { Id = "1", Alias = "A" }] };
        var b = a.Clone();
        b.Devices![0].Alias = "B";
        Assert.Equal("A", a.Devices![0].Alias);
    }

    [Fact]
    public void Defaults_are_left_out_of_the_file()
    {
        string json = ServerSettings.ToJson(new ServerSettings { Port = 24681 });
        Assert.Contains("\"port\": 24681", json);
        Assert.DoesNotContain("maxPads", json);
    }
}

public sealed class NetworkHintsTests
{
    [Theory]
    [InlineData("192.168.42.10", "Remote NDIS based Internet Sharing Device", AdapterKind.UsbTethering)]
    [InlineData("192.168.42.10", "UsbNcm Host Device", AdapterKind.UsbTethering)]
    [InlineData("172.20.0.1", "Hyper-V Virtual Ethernet Adapter", AdapterKind.Virtual)]
    [InlineData("169.254.3.4", "Intel(R) Wi-Fi 6 AX201", AdapterKind.LinkLocal)]
    [InlineData("192.168.1.20", "Intel(R) Wi-Fi 6 AX201", AdapterKind.Lan)]
    public void Adapter_kinds(string address, string description, AdapterKind kind) =>
        Assert.Equal(kind, NetworkHints.KindOf(IPAddress.Parse(address), description));

    [Fact]
    public void Diagnostics_report_round_trips()
    {
        var report = new DiagnosticsReport("1.2.0", 1, "Windows", "X64", "PC", "PC", new DriverCheck(true, null),
            new PortCheck(24680, "0.0.0.0", false, true, "in use"), [new AdapterInfo("192.168.1.2", "Wi-Fi", "Intel", "Lan", true)],
            new PowerCheck(true, null), new ConfigCheck(null, []));
        var parsed = DiagnosticsJson.Parse(DiagnosticsJson.Serialize(report))!;
        Assert.Equal(report.Port, parsed.Port);
        Assert.Equal(report.Adapters[0], parsed.Adapters[0]);
        Assert.Null(DiagnosticsJson.Parse("Mobile Controller server 1.0 diagnostics"));
    }
}
