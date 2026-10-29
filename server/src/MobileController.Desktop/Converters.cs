using System.Globalization;
using System.Windows;
using System.Windows.Data;
using System.Windows.Media;
using MobileController.Desktop.ViewModels;

namespace MobileController.Desktop;

/// <summary>A status level as a theme colour: green, accent, amber, red, grey.</summary>
public sealed class LevelToBrushConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, CultureInfo culture) => value switch
    {
        Level.Good => Find("SystemFillColorSuccessBrush", Color.FromRgb(0x0F, 0x7B, 0x0F)),
        Level.Info => Find("AccentFillColorDefaultBrush", Color.FromRgb(0x00, 0x5F, 0xB8)),
        Level.Warning => Find("SystemFillColorCautionBrush", Color.FromRgb(0x9D, 0x5D, 0x00)),
        Level.Error => Find("SystemFillColorCriticalBrush", Color.FromRgb(0xC4, 0x2B, 0x1C)),
        _ => Find("TextFillColorSecondaryBrush", Color.FromRgb(0x80, 0x80, 0x80)),
    };

    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture) => Binding.DoNothing;

    // The Fluent theme's brushes, with fixed colours for a theme that lacks them.
    private static Brush Find(string key, Color fallback) =>
        Application.Current?.TryFindResource(key) as Brush ?? new SolidColorBrush(fallback);
}

/// <summary>A status level as a Segoe Fluent Icons glyph.</summary>
public sealed class LevelToGlyphConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, CultureInfo culture) => value switch
    {
        Level.Good => "",    // CheckMark
        Level.Info => "",    // Info
        Level.Warning => "", // Warning
        Level.Error => "",   // ErrorBadge
        _ => "",             // Sync
    };

    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture) => Binding.DoNothing;
}

/// <summary>Visible when true; with ConverterParameter "invert", visible when false.</summary>
public sealed class BoolToVisibilityConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, CultureInfo culture) =>
        (value is true) != (parameter as string == "invert") ? Visibility.Visible : Visibility.Collapsed;

    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture) => Binding.DoNothing;
}

/// <summary>Visible when the value is not null (and, for strings, not empty).</summary>
public sealed class NotNullToVisibilityConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, CultureInfo culture) =>
        value is null || value is string { Length: 0 } ? Visibility.Collapsed : Visibility.Visible;

    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture) => Binding.DoNothing;
}

/// <summary>Visible when the value equals the parameter (compared as text), e.g. a pad kind.</summary>
public sealed class EqualsToVisibilityConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, CultureInfo culture) =>
        string.Equals(value?.ToString(), parameter?.ToString(), StringComparison.Ordinal) ? Visibility.Visible : Visibility.Collapsed;

    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture) => Binding.DoNothing;
}

/// <summary>Visible when a count is zero (for "nothing here yet" messages).</summary>
public sealed class ZeroToVisibilityConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, CultureInfo culture) =>
        value is int count && count == 0 ? Visibility.Visible : Visibility.Collapsed;

    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture) => Binding.DoNothing;
}
