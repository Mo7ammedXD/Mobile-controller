using System.Globalization;
using System.Windows;
using System.Windows.Media;
using MobileController.Control;

namespace MobileController.Desktop.Controls;

/// <summary>
/// What the game sees from one controller right now: both sticks, both triggers, the D-pad and every button lit while
/// pressed. Drawn from a <see cref="LivePad"/> (XUSB layout); <see cref="PlayStation"/> labels the face buttons ✕ ○ □ △.
/// </summary>
public sealed class PadVisualizer : FrameworkElement
{
    public static readonly DependencyProperty LiveProperty = DependencyProperty.Register(
        nameof(Live), typeof(LivePad), typeof(PadVisualizer), new FrameworkPropertyMetadata(null, FrameworkPropertyMetadataOptions.AffectsRender));

    public static readonly DependencyProperty PlayStationProperty = DependencyProperty.Register(
        nameof(PlayStation), typeof(bool), typeof(PadVisualizer), new FrameworkPropertyMetadata(false, FrameworkPropertyMetadataOptions.AffectsRender));

    public static readonly DependencyProperty AccentProperty = DependencyProperty.Register(
        nameof(Accent), typeof(Brush), typeof(PadVisualizer), new FrameworkPropertyMetadata(Brushes.DodgerBlue, FrameworkPropertyMetadataOptions.AffectsRender));

    public static readonly DependencyProperty ForegroundProperty = DependencyProperty.Register(
        nameof(Foreground), typeof(Brush), typeof(PadVisualizer), new FrameworkPropertyMetadata(Brushes.Gray, FrameworkPropertyMetadataOptions.AffectsRender));

    public LivePad? Live { get => (LivePad?)GetValue(LiveProperty); set => SetValue(LiveProperty, value); }
    public bool PlayStation { get => (bool)GetValue(PlayStationProperty); set => SetValue(PlayStationProperty, value); }
    public Brush Accent { get => (Brush)GetValue(AccentProperty); set => SetValue(AccentProperty, value); }
    public Brush Foreground { get => (Brush)GetValue(ForegroundProperty); set => SetValue(ForegroundProperty, value); }

    // XINPUT_GAMEPAD_* bits.
    private const int Up = 0x0001, Down = 0x0002, Left = 0x0004, Right = 0x0008, Start = 0x0010, Back = 0x0020;
    private const int L3 = 0x0040, R3 = 0x0080, LB = 0x0100, RB = 0x0200, Guide = 0x0400, A = 0x1000, B = 0x2000, X = 0x4000, Y = 0x8000;

    protected override Size MeasureOverride(Size available) =>
        new(double.IsInfinity(available.Width) ? 280 : available.Width, double.IsInfinity(available.Height) ? 140 : Math.Min(available.Height, 160));

    protected override void OnRender(DrawingContext dc)
    {
        double w = ActualWidth, h = ActualHeight;
        if (w <= 0 || h <= 0) return;
        double u = Math.Min(w / 2.2, h); // layout unit
        double cx = w / 2;
        var outline = new Pen(Foreground, 1);
        var faint = Foreground.Clone();
        faint.Opacity = 0.15;
        faint.Freeze();
        var live = Live;
        int buttons = live?.Buttons ?? 0;
        bool On(int bit) => (buttons & bit) != 0;

        // Triggers and shoulders across the top.
        DrawTrigger(dc, new Rect(cx - u * 0.95, 0, u * 0.35, u * 0.09), (live?.LeftTrigger ?? 0) / 255.0, faint, outline);
        DrawTrigger(dc, new Rect(cx + u * 0.60, 0, u * 0.35, u * 0.09), (live?.RightTrigger ?? 0) / 255.0, faint, outline);
        Pill(dc, new Rect(cx - u * 0.95, u * 0.12, u * 0.35, u * 0.08), On(LB), PlayStation ? "L1" : "LB", faint, outline);
        Pill(dc, new Rect(cx + u * 0.60, u * 0.12, u * 0.35, u * 0.08), On(RB), PlayStation ? "R1" : "RB", faint, outline);

        // Sticks.
        DrawStick(dc, new Point(cx - u * 0.62, u * 0.52), u * 0.2, live?.LX ?? 0, live?.LY ?? 0, On(L3), faint, outline);
        DrawStick(dc, new Point(cx + u * 0.25, u * 0.78), u * 0.2, live?.RX ?? 0, live?.RY ?? 0, On(R3), faint, outline);

        // D-pad.
        var d = new Point(cx - u * 0.25, u * 0.78);
        double s = u * 0.07;
        Square(dc, new Rect(d.X - s / 2, d.Y - s * 1.5, s, s), On(Up), faint, outline);
        Square(dc, new Rect(d.X - s / 2, d.Y + s * 0.5, s, s), On(Down), faint, outline);
        Square(dc, new Rect(d.X - s * 1.5, d.Y - s / 2, s, s), On(Left), faint, outline);
        Square(dc, new Rect(d.X + s * 0.5, d.Y - s / 2, s, s), On(Right), faint, outline);

        // Face buttons.
        var f = new Point(cx + u * 0.62, u * 0.52);
        double r = u * 0.065;
        Round(dc, new Point(f.X, f.Y + r * 2), r, On(A), PlayStation ? "✕" : "A", faint, outline);
        Round(dc, new Point(f.X + r * 2, f.Y), r, On(B), PlayStation ? "○" : "B", faint, outline);
        Round(dc, new Point(f.X - r * 2, f.Y), r, On(X), PlayStation ? "□" : "X", faint, outline);
        Round(dc, new Point(f.X, f.Y - r * 2), r, On(Y), PlayStation ? "△" : "Y", faint, outline);

        // Centre buttons.
        Pill(dc, new Rect(cx - u * 0.24, u * 0.40, u * 0.16, u * 0.07), On(Back), PlayStation ? "Share" : "View", faint, outline);
        Pill(dc, new Rect(cx + u * 0.08, u * 0.40, u * 0.16, u * 0.07), On(Start), PlayStation ? "Options" : "Menu", faint, outline);
        Round(dc, new Point(cx, u * 0.28), u * 0.05, On(Guide), PlayStation ? "PS" : "⊗", faint, outline);

        // Rumble: a bar under everything.
        if (live is { } l && (l.LargeMotor > 0 || l.SmallMotor > 0))
        {
            double level = Math.Max(l.LargeMotor, l.SmallMotor) / 255.0;
            dc.DrawRoundedRectangle(Accent, null, new Rect(cx - u * 0.5, h - u * 0.05, u * level, u * 0.04), 2, 2);
        }
    }

    private void DrawTrigger(DrawingContext dc, Rect rect, double value, Brush faint, Pen outline)
    {
        dc.DrawRoundedRectangle(faint, outline, rect, 3, 3);
        if (value > 0) dc.DrawRoundedRectangle(Accent, null, new Rect(rect.X, rect.Y, rect.Width * value, rect.Height), 3, 3);
    }

    private void DrawStick(DrawingContext dc, Point centre, double radius, int x, int y, bool pressed, Brush faint, Pen outline)
    {
        dc.DrawEllipse(faint, outline, centre, radius, radius);
        // +Y is up in XInput, down on screen.
        var knob = new Point(centre.X + x / 32767.0 * radius * 0.75, centre.Y - y / 32767.0 * radius * 0.75);
        dc.DrawEllipse(pressed ? Accent : Foreground, null, knob, radius * 0.35, radius * 0.35);
    }

    private void Square(DrawingContext dc, Rect rect, bool on, Brush faint, Pen outline) =>
        dc.DrawRoundedRectangle(on ? Accent : faint, outline, rect, 2, 2);

    private void Round(DrawingContext dc, Point centre, double radius, bool on, string label, Brush faint, Pen outline)
    {
        dc.DrawEllipse(on ? Accent : faint, outline, centre, radius, radius);
        Label(dc, centre, label, radius * 1.1);
    }

    private void Pill(DrawingContext dc, Rect rect, bool on, string label, Brush faint, Pen outline)
    {
        dc.DrawRoundedRectangle(on ? Accent : faint, outline, rect, rect.Height / 2, rect.Height / 2);
        Label(dc, new Point(rect.X + rect.Width / 2, rect.Y + rect.Height / 2), label, rect.Height * 0.75);
    }

    private void Label(DrawingContext dc, Point centre, string text, double size)
    {
        var formatted = new FormattedText(text, CultureInfo.CurrentUICulture, FlowDirection.LeftToRight,
            new Typeface("Segoe UI"), Math.Max(size, 6), Foreground, VisualTreeHelper.GetDpi(this).PixelsPerDip);
        dc.DrawText(formatted, new Point(centre.X - formatted.Width / 2, centre.Y - formatted.Height / 2));
    }
}
