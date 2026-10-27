<#
.SYNOPSIS
    Allows the Mobile Controller server to receive UDP from phones on the local network.

.DESCRIPTION
    Creates (or updates in place) one inbound Windows Firewall rule: allow UDP <Port> from LocalSubnet, on every
    network profile. "Any" profile matters: a USB-tethering adapter is usually classified as an unidentified Public
    network, so a Private-only rule would silently not apply. LocalSubnet keeps the rule closed to the internet.
    Running it again is harmless; it reports what it changed.

    Must run elevated (Windows Firewall changes need administrator rights).

.PARAMETER Port
    UDP port the server listens on (default 24680; match the server's --port).

.PARAMETER Program
    Optional full path to MobileController.Server.exe. When given, the rule only applies to that executable.
    Leave it out when you run the server via "dotnet run" or move the exe around.

.PARAMETER Remove
    Deletes the rule instead of creating it.

.EXAMPLE
    # From an elevated PowerShell:
    .\Add-FirewallRule.ps1
    .\Add-FirewallRule.ps1 -Port 24681 -Program 'C:\Tools\MobileController.Server.exe'
    .\Add-FirewallRule.ps1 -Remove
#>
[CmdletBinding()]
param(
    [ValidateRange(1, 65535)]
    [int]$Port = 24680,
    [string]$Program,
    [switch]$Remove
)

$ErrorActionPreference = 'Stop'
$ruleName = 'Mobile Controller (UDP-In)'

$principal = [Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Host "This script changes Windows Firewall rules, which requires administrator rights." -ForegroundColor Yellow
    Write-Host "Right-click PowerShell (or Windows Terminal) > 'Run as administrator', then run:"
    Write-Host "    & '$PSCommandPath' -Port $Port"
    exit 1
}

$existing = Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue

if ($Remove) {
    if ($existing) {
        $existing | Remove-NetFirewallRule
        Write-Host "Removed firewall rule '$ruleName'."
    } else {
        Write-Host "No firewall rule named '$ruleName' exists; nothing to remove."
    }
    exit 0
}

if ($Program) {
    $Program = (Resolve-Path -LiteralPath $Program).ProviderPath
}
$programFilter = if ($Program) { $Program } else { 'Any' }

$settings = @{
    Direction     = 'Inbound'
    Action        = 'Allow'
    Protocol      = 'UDP'
    LocalPort     = "$Port"
    RemoteAddress = 'LocalSubnet'
    Profile       = 'Any'
    Program       = $programFilter
    Enabled       = 'True'
    Description   = 'Phone-as-controller input for Mobile Controller (docs/PROTOCOL.md). Local subnet only.'
}

if ($existing) {
    # Set-NetFirewallRule takes the rule fields and the address/port/application filters in one call.
    $existing | Set-NetFirewallRule @settings
    Write-Host "Updated firewall rule '$ruleName': inbound UDP $Port from LocalSubnet, all profiles, program $programFilter."
} else {
    New-NetFirewallRule -DisplayName $ruleName @settings | Out-Null
    Write-Host "Created firewall rule '$ruleName': inbound UDP $Port from LocalSubnet, all profiles, program $programFilter."
}
