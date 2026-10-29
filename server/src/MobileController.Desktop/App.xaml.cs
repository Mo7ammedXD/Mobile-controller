using System.ComponentModel;
using System.IO;
using System.Windows;
using MobileController.Desktop.Services;
using MobileController.Desktop.ViewModels;
using Forms = System.Windows.Forms;

namespace MobileController.Desktop;

/// <summary>
/// One instance per user (a second launch only brings the window forward), a notification-area icon that keeps the app
/// and its server running while the window is closed, and the theme.
/// </summary>
public partial class App : Application
{
    private const string InstanceName = @"Local\MobileController.Desktop";

    private Mutex? _instance;
    private EventWaitHandle? _showRequest;
    private RegisteredWaitHandle? _showWait;
    private Forms.NotifyIcon? _tray;
    private MainViewModel? _model;
    private MainWindow? _window;
    private bool _quitting, _trayHintShown;

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        _instance = new Mutex(initiallyOwned: true, InstanceName, out bool first);
        _showRequest = new EventWaitHandle(false, EventResetMode.AutoReset, InstanceName + ".Show");
        if (!first)
        {
            _showRequest.Set();
            Shutdown();
            return;
        }
        _showWait = ThreadPool.RegisterWaitForSingleObject(_showRequest, (_, _) => Dispatcher.BeginInvoke(ShowWindow), null, Timeout.Infinite, false);

        _model = new MainViewModel(Dispatcher);
        _model.Notify += (title, text) => _tray?.ShowBalloonTip(4000, title, text, Forms.ToolTipIcon.None);
        _model.PropertyChanged += (_, args) =>
        {
            if (args.PropertyName == nameof(MainViewModel.HealthTitle) && _tray is not null)
                _tray.Text = Truncate("Mobile Controller: " + _model.HealthTitle, 63);
        };
        _window = new MainWindow(_model);
        _window.Closing += OnWindowClosing;
        CreateTray();
        _model.Start();

        // Windows starts the app with --minimized; it then stays in the notification area unless the user wants the window.
        bool hidden = e.Args.Contains("--minimized") && _model.Desktop.StartMinimized;
        if (!hidden) _window.Show();
    }

    public static void ApplyTheme(AppTheme theme)
    {
        if (Current is null) return;
        Current.ThemeMode = theme switch
        {
            AppTheme.Light => ThemeMode.Light,
            AppTheme.Dark => ThemeMode.Dark,
            _ => ThemeMode.System,
        };
    }

    private void CreateTray()
    {
        var menu = new Forms.ContextMenuStrip();
        menu.Items.Add("Open Mobile Controller", null, (_, _) => ShowWindow());
        menu.Items.Add("Restart server", null, (_, _) => _model?.RestartServer());
        menu.Items.Add(new Forms.ToolStripSeparator());
        menu.Items.Add("Quit", null, (_, _) => Quit());
        _tray = new Forms.NotifyIcon
        {
            Icon = LoadIcon(),
            Text = "Mobile Controller",
            ContextMenuStrip = menu,
            Visible = true,
        };
        _tray.MouseClick += (_, args) =>
        {
            if (args.Button == Forms.MouseButtons.Left) ShowWindow();
        };
    }

    private static System.Drawing.Icon? LoadIcon()
    {
        var resource = GetResourceStream(new Uri("pack://application:,,,/Assets/app.ico"));
        if (resource is null) return null;
        using Stream stream = resource.Stream;
        return new System.Drawing.Icon(stream);
    }

    private void ShowWindow()
    {
        if (_window is null) return;
        _window.Show();
        if (_window.WindowState == WindowState.Minimized) _window.WindowState = WindowState.Normal;
        _window.Activate();
    }

    private void OnWindowClosing(object? sender, CancelEventArgs e)
    {
        if (_quitting) return;
        if (_model?.Desktop.CloseToTray != true)
        {
            Quit();
            return;
        }
        // Closing the window keeps the phones working: the app lives on in the notification area.
        e.Cancel = true;
        _window!.Hide();
        if (!_trayHintShown)
        {
            _trayHintShown = true;
            _tray?.ShowBalloonTip(4000, "Mobile Controller is still running",
                "Phones stay connected. Use the icon in the notification area to open it again or to quit.", Forms.ToolTipIcon.None);
        }
    }

    public void Quit()
    {
        if (_quitting) return;
        _quitting = true;
        _window?.Close();
        _model?.Dispose();
        if (_tray is not null)
        {
            _tray.Visible = false;
            _tray.Dispose();
        }
        _showWait?.Unregister(null);
        Shutdown();
    }

    protected override void OnExit(ExitEventArgs e)
    {
        _model?.Dispose();
        _tray?.Dispose();
        _instance?.Dispose();
        _showRequest?.Dispose();
        base.OnExit(e);
    }

    private static string Truncate(string text, int length) => text.Length <= length ? text : text[..(length - 1)] + "…";
}
