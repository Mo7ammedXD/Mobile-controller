using System.Collections.Specialized;
using System.Windows;
using System.Windows.Media;

namespace MobileController.Desktop.Controls;

/// <summary>A small line chart of recent values (e.g. latency), redrawn whenever the collection changes.</summary>
public sealed class Sparkline : FrameworkElement
{
    public static readonly DependencyProperty ValuesProperty = DependencyProperty.Register(
        nameof(Values), typeof(IEnumerable<double>), typeof(Sparkline),
        new FrameworkPropertyMetadata(null, FrameworkPropertyMetadataOptions.AffectsRender, OnValuesChanged));

    public static readonly DependencyProperty StrokeProperty = DependencyProperty.Register(
        nameof(Stroke), typeof(Brush), typeof(Sparkline), new FrameworkPropertyMetadata(Brushes.DodgerBlue, FrameworkPropertyMetadataOptions.AffectsRender));

    /// <summary>The top of the scale; the line never scales below it, so a quiet link looks quiet (0 = the data's maximum).</summary>
    public static readonly DependencyProperty MinimumScaleProperty = DependencyProperty.Register(
        nameof(MinimumScale), typeof(double), typeof(Sparkline), new FrameworkPropertyMetadata(0.0, FrameworkPropertyMetadataOptions.AffectsRender));

    public IEnumerable<double>? Values
    {
        get => (IEnumerable<double>?)GetValue(ValuesProperty);
        set => SetValue(ValuesProperty, value);
    }

    public Brush Stroke
    {
        get => (Brush)GetValue(StrokeProperty);
        set => SetValue(StrokeProperty, value);
    }

    public double MinimumScale
    {
        get => (double)GetValue(MinimumScaleProperty);
        set => SetValue(MinimumScaleProperty, value);
    }

    private static void OnValuesChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
    {
        var line = (Sparkline)d;
        if (e.OldValue is INotifyCollectionChanged old) old.CollectionChanged -= line.OnCollectionChanged;
        if (e.NewValue is INotifyCollectionChanged next) next.CollectionChanged += line.OnCollectionChanged;
    }

    private void OnCollectionChanged(object? sender, NotifyCollectionChangedEventArgs e) => InvalidateVisual();

    protected override void OnRender(DrawingContext dc)
    {
        var values = Values?.ToArray();
        if (values is null || values.Length < 2 || ActualWidth <= 0 || ActualHeight <= 0) return;
        double max = Math.Max(values.Max(), MinimumScale);
        if (max <= 0) max = 1;
        double step = ActualWidth / (PadViewModelHistory - 1);
        double x0 = ActualWidth - step * (values.Length - 1);
        var geometry = new StreamGeometry();
        using (var g = geometry.Open())
        {
            for (int i = 0; i < values.Length; i++)
            {
                var point = new Point(x0 + i * step, ActualHeight - 1 - values[i] / max * (ActualHeight - 2));
                if (i == 0) g.BeginFigure(point, isFilled: false, isClosed: false);
                else g.LineTo(point, isStroked: true, isSmoothJoin: true);
            }
        }
        geometry.Freeze();
        dc.DrawGeometry(null, new Pen(Stroke, 1.5) { LineJoin = PenLineJoin.Round }, geometry);
    }

    private const int PadViewModelHistory = ViewModels.PadViewModel.HistoryPoints;
}
