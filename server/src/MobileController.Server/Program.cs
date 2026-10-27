using System.ComponentModel;
using System.Diagnostics;
using System.Diagnostics.CodeAnalysis;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Reflection;
using System.Runtime.InteropServices;
using Nefarius.ViGEm.Client.Exceptions;

namespace MobileController.Server;

internal static class Program
{
    private const int ExitOk = 0;
    private const int ExitFailure = 1;
    private const int ExitBadArguments = 2;
    private const int ExitViGEm = 3;
    private const int ExitNetwork = 4;

    private const string ViGEmDownload = "https://github.com/nefarius/ViGEmBus/releases";

    private static int Main(string[] args)
    {
        if (!Options.TryParse(args, out var options, out string? error))
        {
            Console.Error.WriteLine($"Error: {error}");
            Console.Error.WriteLine();
            Console.Error.WriteLine(Options.Usage);
            return ExitBadArguments;
        }
        if (options.Help)
        {
            Console.WriteLine(Options.Usage);
            return ExitOk;
        }

        using var log = new Logger(Console.Out);
        TuneProcess(options.HighPriority, log);
        return options.TestPatternSeconds is int seconds ? RunTestPattern(seconds, log) : RunServer(options, log);
    }

    private static int RunServer(Options options, Logger log)
    {
        IVirtualPadFactory? factory = null;
        if (!options.DryRun && !TryCreateFactory(out factory)) return ExitViGEm;

        GamepadServer server;
        try
        {
            server = new GamepadServer(options, factory, log);
        }
        catch (SocketException e)
        {
            factory?.Dispose();
            Console.Error.WriteLine(DescribeBindFailure(e, options));
            return ExitNetwork;
        }

        PrintBanner(options, server.LocalEndPoint);

        // Every way out (Ctrl+C, console window closed, logoff/shutdown, normal exit) funnels into the same stop:
        // the receive loop notices within one poll interval and unplugs every pad before the thread ends.
        void StopAndWait()
        {
            server.RequestStop();
            server.WaitForExit(TimeSpan.FromSeconds(3));
        }
        Console.CancelKeyPress += (_, e) =>
        {
            e.Cancel = true;
            server.RequestStop();
        };
        // CTRL_CLOSE_EVENT / CTRL_SHUTDOWN_EVENT: Windows kills the process as soon as these handlers return, so they
        // wait for the pads to be unplugged.
        using var onClose = PosixSignalRegistration.Create(PosixSignal.SIGHUP, _ => StopAndWait());
        using var onShutdown = PosixSignalRegistration.Create(PosixSignal.SIGTERM, _ => StopAndWait());
        AppDomain.CurrentDomain.ProcessExit += (_, _) => StopAndWait();

        server.Start();
        server.WaitForExit();
        server.Dispose();
        factory?.Dispose();
        log.Info(server.Faulted ? "Stopped after an error." : "Stopped.");
        return server.Faulted ? ExitFailure : ExitOk;
    }

    private static int RunTestPattern(int seconds, Logger log)
    {
        if (!TryCreateFactory(out var factory)) return ExitViGEm;
        using (factory)
        using (var cancellation = new CancellationTokenSource())
        {
            Console.CancelKeyPress += (_, e) =>
            {
                e.Cancel = true;
                cancellation.Cancel();
            };
            try
            {
                TestPattern.Run(factory, TimeSpan.FromSeconds(seconds), cancellation.Token, log);
            }
            catch (Exception e)
            {
                log.Error($"Could not drive the virtual controller: {ViGEmPadFactory.Describe(e)}");
                return ExitViGEm;
            }
        }
        return ExitOk;
    }

    private static bool TryCreateFactory([NotNullWhen(true)] out IVirtualPadFactory? factory)
    {
        factory = null;
        string? problem;
        try
        {
            factory = new ViGEmPadFactory();
            return true;
        }
        catch (VigemBusNotFoundException)
        {
            problem = "The ViGEmBus driver is not installed (or not running).\n" +
                      $"Install it from {ViGEmDownload} (the final release is v1.22.0; the project is retired but the\n" +
                      "driver still works), then start this server again. --dry-run tests the network path without it.";
        }
        catch (VigemBusVersionMismatchException)
        {
            problem = $"The installed ViGEmBus driver version does not match this client. Install v1.22.0 from {ViGEmDownload}\n" +
                      "(uninstall the old version first if the installer refuses), then reboot.";
        }
        catch (VigemBusAccessFailedException)
        {
            problem = "ViGEmBus is installed but could not be opened. Another program may hold it exclusively, or the\n" +
                      "driver is in a bad state: reboot, or reinstall it from " + ViGEmDownload + ".";
        }
        catch (Exception e) when (e is DllNotFoundException or BadImageFormatException or EntryPointNotFoundException)
        {
            problem = $"The native ViGEm client (vigemclient.dll) could not be loaded: {e.Message}\n" +
                      "Use the win-x64 build (it also runs on Windows on ARM) and make sure %TEMP% is writable.";
        }
        catch (Exception e) when (e is VigemAllocFailedException or VigemAlreadyConnectedException)
        {
            problem = $"ViGEmBus refused the connection ({e.GetType().Name}). Reboot and try again.";
        }
        Console.Error.WriteLine($"Error: {problem}");
        return false;
    }

    private static string DescribeBindFailure(SocketException e, Options options)
    {
        string where = $"{options.BindAddress}:{options.Port}";
        return "Error: " + e.SocketErrorCode switch
        {
            SocketError.AddressAlreadyInUse =>
                $"UDP port {options.Port} is already in use (is another server instance running?). " +
                $"Close it or choose another port with --port <n> (and enter the same port on the phone).",
            SocketError.AccessDenied =>
                $"Access denied while binding UDP {where}. The port may be reserved by Windows " +
                "(see: netsh int ipv4 show excludedportrange protocol=udp) or blocked by policy; try another --port.",
            SocketError.AddressNotAvailable =>
                $"{options.BindAddress} is not an address of this PC. Run without --bind to list the valid ones.",
            _ => $"could not open UDP {where}: {e.Message} ({e.SocketErrorCode}).",
        };
    }

    private static void TuneProcess(bool highPriority, Logger log)
    {
        try
        {
            using var process = Process.GetCurrentProcess();
            process.PriorityClass = highPriority ? ProcessPriorityClass.High : ProcessPriorityClass.AboveNormal;
        }
        catch (Exception e) when (e is Win32Exception or InvalidOperationException)
        {
            log.Warn($"Could not raise the process priority: {e.Message}");
        }

        int error = Native.DisablePowerThrottling();
        if (error != 0) log.Warn($"Could not opt out of Windows power throttling (error {error}); latency may suffer in the background.");
    }

    private static void PrintBanner(Options options, IPEndPoint local)
    {
        string version = typeof(Program).Assembly.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion
            .Split('+')[0] ?? "?";
        var o = Console.Out;
        o.WriteLine($"Mobile Controller server {version} (protocol v{Protocol.Version})");
        o.WriteLine($"Listening on UDP {local}, up to {options.MaxPads} controller(s), " +
                    (options.DryRun ? "DRY RUN (no virtual controllers)" : "ViGEmBus connected"));
        if (options.Allow.Count != 0) o.WriteLine($"Accepting packets only from: {string.Join(", ", options.Allow)}");

        var addresses = LocalIPv4Addresses().ToList();
        if (addresses.Count == 0)
        {
            o.WriteLine("No active network adapter has an IPv4 address. Join the phone's Wi-Fi or enable USB tethering.");
        }
        else
        {
            o.WriteLine("Enter one of these addresses in the phone app (or use auto-discovery):");
            foreach (var (address, name, description) in addresses)
            {
                string hint = IsUsbTethering(description) ? "   <- USB tethering"
                    : IsVirtual(description) ? "   (virtual adapter, not reachable from a phone)"
                    : address.ToString().StartsWith("169.254.", StringComparison.Ordinal) ? "   (no DHCP lease: link-local only)"
                    : "";
                bool reachable = options.BindAddress.Equals(IPAddress.Any) || options.BindAddress.Equals(address);
                o.WriteLine($"  {address,-15}  {name} - {description}{hint}{(reachable ? "" : "   (not bound)")}");
            }
        }
        o.WriteLine($"If the phone cannot connect, allow UDP {local.Port} in Windows Firewall (scripts\\Add-FirewallRule.ps1). Ctrl+C stops.");
    }

    private static IEnumerable<(IPAddress Address, string Name, string Description)> LocalIPv4Addresses()
    {
        foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (nic.OperationalStatus != OperationalStatus.Up || nic.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
            foreach (var unicast in nic.GetIPProperties().UnicastAddresses)
            {
                var address = unicast.Address;
                if (address.AddressFamily == AddressFamily.InterNetwork && !IPAddress.IsLoopback(address))
                    yield return (address, nic.Name, nic.Description);
            }
        }
    }

    // Android tethering shows up as "Remote NDIS based Internet Sharing Device" (RNDIS) or, on newer phones, as a
    // UsbNcm adapter.
    private static bool IsUsbTethering(string description) =>
        description.Contains("Remote NDIS", StringComparison.OrdinalIgnoreCase) ||
        description.Contains("RNDIS", StringComparison.OrdinalIgnoreCase) ||
        description.Contains("UsbNcm", StringComparison.OrdinalIgnoreCase);

    private static bool IsVirtual(string description) =>
        description.Contains("Hyper-V", StringComparison.OrdinalIgnoreCase) ||
        description.Contains("VirtualBox", StringComparison.OrdinalIgnoreCase) ||
        description.Contains("VMware", StringComparison.OrdinalIgnoreCase);
}
