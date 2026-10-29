using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using MobileController.Control;

namespace MobileController.Server;

/// <summary>
/// --diagnose: everything that decides whether a phone can drive this PC, checked without starting the server. The
/// text report is for people; --json is what the desktop app's setup wizard and dashboard read.
/// </summary>
internal static class Diagnostics
{
    public static DiagnosticsReport Collect(Options options, string? configPath, IReadOnlyList<string> configWarnings)
    {
        var driver = Program.TryCreateFactory(out var factory, out string? driverProblem)
            ? new DriverCheck(true, null)
            : new DriverCheck(false, driverProblem);
        factory?.Dispose();

        var port = ProbePort(options);
        var adapters = NetworkHints.LocalIPv4Addresses()
            .Select(a => new AdapterInfo(
                a.Address.ToString(), a.Name, a.Description, a.Kind.ToString(),
                options.BindAddress.Equals(IPAddress.Any) || options.BindAddress.Equals(a.Address)))
            .ToList();

        int powerError = OperatingSystem.IsWindowsVersionAtLeast(10, 0, 16299) ? Native.DisablePowerThrottling() : -1;

        return new DiagnosticsReport(
            Program.Version,
            Protocol.Version,
            RuntimeInformation.OSDescription,
            RuntimeInformation.OSArchitecture.ToString(),
            Environment.MachineName,
            options.ServerName,
            driver,
            port,
            adapters,
            new PowerCheck(powerError == 0, powerError == 0 ? null : $"power throttling opt-out failed (error {powerError})"),
            new ConfigCheck(configPath, configWarnings.ToList()));
    }

    /// <summary>Binds the port exactly like the server would, then lets it go again.</summary>
    private static PortCheck ProbePort(Options options)
    {
        string bind = options.BindAddress.ToString();
        try
        {
            using var socket = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp) { ExclusiveAddressUse = true };
            socket.Bind(new IPEndPoint(options.BindAddress, options.Port));
            return new PortCheck(options.Port, bind, true, false, null);
        }
        catch (SocketException e)
        {
            return new PortCheck(options.Port, bind, false, e.SocketErrorCode == SocketError.AddressAlreadyInUse, Program.DescribeBindFailure(e, options));
        }
    }

    public static void WriteJson(DiagnosticsReport report, TextWriter output) =>
        output.WriteLine(DiagnosticsJson.Serialize(report));

    public static void WriteText(DiagnosticsReport report, TextWriter o)
    {
        o.WriteLine($"Mobile Controller server {report.Version} diagnostics (protocol v{report.ProtocolVersion})");
        o.WriteLine($"  Computer: {report.Computer} ({report.Os}, {report.Architecture})");
        o.WriteLine($"  Name phones see: {report.ServerName}");
        o.WriteLine();
        Line(o, report.Driver.Ok ? Ok : Fail, report.Driver.Ok ? "ViGEmBus driver installed and reachable" : report.Driver.Problem!);
        Line(o, report.Port.Ok ? Ok : Fail, report.Port.Ok
            ? $"UDP {report.Port.Bind}:{report.Port.Port} is free"
            : report.Port.InUse
                ? $"UDP {report.Port.Port} is in use: a server is probably already running (that is fine if it is this one). {report.Port.Problem}"
                : report.Port.Problem!);

        var usable = report.Adapters.Where(a => a.Kind is nameof(AdapterKind.Lan) or nameof(AdapterKind.UsbTethering) && a.Reachable).ToList();
        if (usable.Count == 0)
            Line(o, Fail, "No network adapter a phone can reach: join the phone's Wi-Fi or enable USB tethering.");
        foreach (var a in report.Adapters)
        {
            var kind = Enum.Parse<AdapterKind>(a.Kind);
            string mark = !a.Reachable || kind is AdapterKind.Virtual or AdapterKind.LinkLocal ? Info : Ok;
            Line(o, mark, $"{a.Address,-15}  {a.Name} - {a.Description} {NetworkHints.Hint(kind)}{(a.Reachable ? "" : " (not bound)")}");
        }

        Line(o, report.Power.Ok ? Ok : Warn, report.Power.Ok
            ? "Windows power throttling can be switched off for the server (full speed in the background)"
            : $"{report.Power.Problem}; latency may suffer while the server is in the background");
        Line(o, Info, $"Windows Firewall must allow inbound UDP {report.Port.Port} (scripts\\Add-FirewallRule.ps1, or the desktop app).");

        if (report.Config.Path is null)
            Line(o, Info, $"No settings file (built-in defaults; the default location is {ServerSettings.DefaultPath})");
        else
            Line(o, report.Config.Warnings.Count == 0 ? Ok : Warn, $"Settings file: {report.Config.Path}");
        foreach (string warning in report.Config.Warnings) Line(o, Warn, warning);
    }

    private const string Ok = "[ OK ]";
    private const string Fail = "[FAIL]";
    private const string Warn = "[WARN]";
    private const string Info = "[INFO]";

    private static void Line(TextWriter o, string mark, string text) => o.WriteLine($"  {mark} {text}");
}
