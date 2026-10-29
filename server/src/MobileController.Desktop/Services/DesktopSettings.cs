using System.IO;
using System.Text.Json;
using System.Text.Json.Serialization;
using Microsoft.Win32;

namespace MobileController.Desktop.Services;

public enum AppTheme
{
    System,
    Light,
    Dark,
}

/// <summary>
/// The desktop app's own preferences (the server's live in <see cref="MobileController.Control.ServerSettings"/>), in
/// <c>%APPDATA%\MobileController\desktop.json</c>.
/// </summary>
public sealed class DesktopSettings
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string RunValue = "MobileController";

    /// <summary>Closing the window keeps the app (and with it the server) running in the notification area.</summary>
    public bool CloseToTray { get; set; } = true;

    /// <summary>When Windows starts the app (<see cref="StartWithWindows"/>), stay in the notification area.</summary>
    public bool StartMinimized { get; set; } = true;

    /// <summary>A notification when a phone connects or disconnects.</summary>
    public bool Notifications { get; set; } = true;

    public AppTheme Theme { get; set; } = AppTheme.System;

    /// <summary>The setup checklist was completed or dismissed once.</summary>
    public bool SetupDone { get; set; }

    public static string Path => System.IO.Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "MobileController", "desktop.json");

    public static DesktopSettings Load()
    {
        try
        {
            using var stream = File.OpenRead(Path);
            return JsonSerializer.Deserialize(stream, DesktopSettingsContext.Default.DesktopSettings) ?? new DesktopSettings();
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or JsonException)
        {
            return new DesktopSettings();
        }
    }

    public void Save()
    {
        Directory.CreateDirectory(System.IO.Path.GetDirectoryName(Path)!);
        File.WriteAllText(Path, JsonSerializer.Serialize(this, DesktopSettingsContext.Default.DesktopSettings));
    }

    /// <summary>Start with Windows: the per-user Run key (no administrator rights needed), starting minimised.</summary>
    public static bool StartWithWindows
    {
        get
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKey);
            return key?.GetValue(RunValue) is string;
        }
        set
        {
            using var key = Registry.CurrentUser.CreateSubKey(RunKey);
            if (value) key.SetValue(RunValue, $"\"{Environment.ProcessPath}\" --minimized");
            else key.DeleteValue(RunValue, throwOnMissingValue: false);
        }
    }
}

[JsonSourceGenerationOptions(PropertyNamingPolicy = JsonKnownNamingPolicy.CamelCase, WriteIndented = true, UseStringEnumConverter = true)]
[JsonSerializable(typeof(DesktopSettings))]
internal sealed partial class DesktopSettingsContext : JsonSerializerContext;
