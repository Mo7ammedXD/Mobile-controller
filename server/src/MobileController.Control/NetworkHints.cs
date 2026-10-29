using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace MobileController.Control;

/// <summary>What kind of link an adapter is, from the phone's point of view.</summary>
public enum AdapterKind
{
    /// <summary>An ordinary LAN or Wi-Fi adapter: phones on the same network can reach it.</summary>
    Lan,

    /// <summary>Android USB tethering (RNDIS / NCM): the most reliable, lowest-latency link.</summary>
    UsbTethering,

    /// <summary>A virtual machine switch: not reachable from a phone.</summary>
    Virtual,

    /// <summary>169.254.x.x: the adapter got no DHCP lease, so it only reaches peers without one either.</summary>
    LinkLocal,
}

public readonly record struct LocalAdapter(IPAddress Address, string Name, string Description, AdapterKind Kind);

/// <summary>The PC's IPv4 addresses a phone could use, with a hint about each adapter.</summary>
public static class NetworkHints
{
    public static List<LocalAdapter> LocalIPv4Addresses()
    {
        var result = new List<LocalAdapter>();
        foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (nic.OperationalStatus != OperationalStatus.Up || nic.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
            foreach (var unicast in nic.GetIPProperties().UnicastAddresses)
            {
                var address = unicast.Address;
                if (address.AddressFamily == AddressFamily.InterNetwork && !IPAddress.IsLoopback(address))
                    result.Add(new LocalAdapter(address, nic.Name, nic.Description, KindOf(address, nic.Description)));
            }
        }
        return result;
    }

    public static AdapterKind KindOf(IPAddress address, string description) =>
        IsUsbTethering(description) ? AdapterKind.UsbTethering
        : IsVirtual(description) ? AdapterKind.Virtual
        : address.ToString().StartsWith("169.254.", StringComparison.Ordinal) ? AdapterKind.LinkLocal
        : AdapterKind.Lan;

    /// <summary>Text for the console banner and the diagnostics report ("" for an ordinary adapter).</summary>
    public static string Hint(AdapterKind kind) => kind switch
    {
        AdapterKind.UsbTethering => "<- USB tethering",
        AdapterKind.Virtual => "(virtual adapter, not reachable from a phone)",
        AdapterKind.LinkLocal => "(no DHCP lease: link-local only)",
        _ => "",
    };

    // Android tethering shows up as "Remote NDIS based Internet Sharing Device" (RNDIS) or, on newer phones, as a
    // UsbNcm adapter.
    public static bool IsUsbTethering(string description) =>
        description.Contains("Remote NDIS", StringComparison.OrdinalIgnoreCase) ||
        description.Contains("RNDIS", StringComparison.OrdinalIgnoreCase) ||
        description.Contains("UsbNcm", StringComparison.OrdinalIgnoreCase);

    public static bool IsVirtual(string description) =>
        description.Contains("Hyper-V", StringComparison.OrdinalIgnoreCase) ||
        description.Contains("VirtualBox", StringComparison.OrdinalIgnoreCase) ||
        description.Contains("VMware", StringComparison.OrdinalIgnoreCase);
}
