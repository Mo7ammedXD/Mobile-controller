using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using MobileController.Control;

namespace MobileController.Desktop.Services;

/// <summary>
/// The checks behind the setup checklist: the server's own diagnosis (driver, port, adapters, power), and the Windows
/// Firewall rule the bundled script creates. All of them run processes, so callers await them off the UI thread.
/// </summary>
public static class SystemChecks
{
    public const string ViGEmDownload = "https://github.com/nefarius/ViGEmBus/releases";

    /// <summary>The rule name Add-FirewallRule.ps1 uses.</summary>
    public const string FirewallRuleName = "Mobile Controller (UDP-In)";

    /// <summary>Runs <c>MobileController.Server --diagnose --json</c>; null if it could not run or printed no report.</summary>
    public static async Task<DiagnosticsReport?> DiagnoseAsync(string serverPath, CancellationToken token = default)
    {
        if (!File.Exists(serverPath)) return null;
        var info = new ProcessStartInfo(serverPath, "--diagnose --json")
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
        };
        try
        {
            using var process = Process.Start(info)!;
            string output = await process.StandardOutput.ReadToEndAsync(token);
            await process.WaitForExitAsync(token);
            return DiagnosticsJson.Parse(output);
        }
        catch (Exception e) when (e is Win32Exception or InvalidOperationException or IOException)
        {
            return null;
        }
    }

    /// <summary>
    /// Whether the firewall rule exists and lets this copy of the server receive on <paramref name="port"/>; null if that
    /// could not be determined. <c>netsh</c> answers without administrator rights and much faster than PowerShell's
    /// firewall cmdlets.
    /// </summary>
    public static async Task<FirewallRule?> CheckFirewallRuleAsync(int port, string serverPath, CancellationToken token = default)
    {
        var info = new ProcessStartInfo("netsh", $"advfirewall firewall show rule name=\"{FirewallRuleName}\" verbose")
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
        };
        try
        {
            using var process = Process.Start(info)!;
            string output = await process.StandardOutput.ReadToEndAsync(token);
            await process.WaitForExitAsync(token);
            // netsh exits with 1 and "No rules match the specified criteria." when there is none.
            if (process.ExitCode != 0 || !output.Contains(FirewallRuleName, StringComparison.OrdinalIgnoreCase)) return FirewallRule.Missing;
            return RuleCovers(output, port, serverPath) ? FirewallRule.Covers : FirewallRule.Outdated;
        }
        catch (Exception e) when (e is Win32Exception or InvalidOperationException or IOException)
        {
            return null;
        }
    }

    /// <summary>
    /// Add-FirewallRule.ps1 makes one rule for one UDP port, optionally limited to one exe. The labels of netsh's output
    /// are translated on other Windows languages, so only the values (after the first colon of each line) are compared.
    /// </summary>
    internal static bool RuleCovers(string netshOutput, int port, string serverPath)
    {
        var values = netshOutput.Split('\n')
            .Select(line => line.IndexOf(':') is int colon and >= 0 ? line[(colon + 1)..].Trim() : "")
            .Where(value => value.Length > 0)
            .ToList();
        bool portOk = values.Any(v => v.Split(',').Any(p => p.Trim() == port.ToString(System.Globalization.CultureInfo.InvariantCulture)));
        var programs = values.Where(v => v.EndsWith(".exe", StringComparison.OrdinalIgnoreCase)).ToList();
        bool programOk = programs.Count == 0 || programs.Any(p => string.Equals(p, serverPath, StringComparison.OrdinalIgnoreCase));
        return portOk && programOk;
    }

    /// <summary>
    /// Runs the bundled Add-FirewallRule.ps1 elevated (Windows asks the user for permission). Returns false if the user
    /// declined or the script failed.
    /// </summary>
    public static async Task<bool> FixFirewallAsync(int port, string serverPath)
    {
        string script = Path.Combine(AppContext.BaseDirectory, "Add-FirewallRule.ps1");
        if (!File.Exists(script)) return false;
        var info = new ProcessStartInfo("powershell.exe")
        {
            UseShellExecute = true,
            Verb = "runas",
            WindowStyle = ProcessWindowStyle.Hidden,
            Arguments = $"-NoProfile -ExecutionPolicy Bypass -File \"{script}\" -Port {port} -Program \"{serverPath}\"",
        };
        try
        {
            using var process = Process.Start(info)!;
            await process.WaitForExitAsync();
            return process.ExitCode == 0;
        }
        catch (Win32Exception)
        {
            return false; // the user said no to the elevation prompt
        }
    }

    public enum FirewallRule
    {
        Missing,
        /// <summary>The rule is for another port, or for a copy of the server in another folder.</summary>
        Outdated,
        Covers,
    }

    /// <summary>Opens a web page or a folder in the user's default handler.</summary>
    public static void Open(string target)
    {
        try
        {
            Process.Start(new ProcessStartInfo(target) { UseShellExecute = true })?.Dispose();
        }
        catch (Win32Exception)
        {
        }
    }
}
