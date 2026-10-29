using System.Windows;
using System.Windows.Media;
using QRCoder;

namespace MobileController.Desktop.Controls;

/// <summary>
/// A QR code of <see cref="Text"/>, drawn as vector geometry from QRCoder's module matrix: sharp at any size and DPI, and
/// always dark on light (a phone camera reads that best, whatever the app's theme).
/// </summary>
public sealed class QrCode : FrameworkElement
{
    public static readonly DependencyProperty TextProperty = DependencyProperty.Register(
        nameof(Text), typeof(string), typeof(QrCode), new FrameworkPropertyMetadata(null, FrameworkPropertyMetadataOptions.AffectsRender, OnTextChanged));

    private Geometry? _modules;
    private int _size;

    public string? Text
    {
        get => (string?)GetValue(TextProperty);
        set => SetValue(TextProperty, value);
    }

    private static void OnTextChanged(DependencyObject d, DependencyPropertyChangedEventArgs e) => ((QrCode)d).Build();

    private void Build()
    {
        _modules = null;
        if (string.IsNullOrEmpty(Text)) return;
        using var generator = new QRCodeGenerator();
        using var data = generator.CreateQrCode(Text, QRCodeGenerator.ECCLevel.M);
        var matrix = data.ModuleMatrix;
        _size = matrix.Count;
        var group = new GeometryGroup { FillRule = FillRule.Nonzero };
        for (int y = 0; y < _size; y++)
        {
            for (int x = 0; x < _size; x++)
            {
                if (matrix[y][x]) group.Children.Add(new RectangleGeometry(new Rect(x, y, 1.02, 1.02)));
            }
        }
        group.Freeze();
        _modules = group;
    }

    protected override Size MeasureOverride(Size available)
    {
        double side = Math.Min(double.IsInfinity(available.Width) ? 220 : available.Width, double.IsInfinity(available.Height) ? 220 : available.Height);
        return new Size(side, side);
    }

    protected override void OnRender(DrawingContext dc)
    {
        double side = Math.Min(ActualWidth, ActualHeight);
        if (side <= 0) return;
        dc.DrawRoundedRectangle(Brushes.White, null, new Rect(0, 0, side, side), 8, 8);
        if (_modules is null) return;
        // QRCoder's matrix includes the 4-module quiet zone.
        double scale = side / _size;
        dc.PushTransform(new ScaleTransform(scale, scale));
        dc.DrawGeometry(Brushes.Black, null, _modules);
        dc.Pop();
    }
}
