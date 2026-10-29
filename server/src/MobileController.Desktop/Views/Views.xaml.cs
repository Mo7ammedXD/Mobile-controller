using System.Collections.Specialized;
using System.Windows.Controls;

namespace MobileController.Desktop.Views;

// The pages are plain views of MainViewModel (Settings: SettingsViewModel); their code-behind only loads the XAML.

public partial class DashboardView : UserControl
{
    public DashboardView() => InitializeComponent();
}

public partial class ControllersView : UserControl
{
    public ControllersView() => InitializeComponent();
}

public partial class ConnectView : UserControl
{
    public ConnectView() => InitializeComponent();
}

public partial class DiagnosticsView : UserControl
{
    public DiagnosticsView() => InitializeComponent();
}

public partial class SettingsView : UserControl
{
    public SettingsView() => InitializeComponent();
}

public partial class HelpView : UserControl
{
    public HelpView() => InitializeComponent();
}

public partial class LogsView : UserControl
{
    public LogsView()
    {
        InitializeComponent();
        // Follow the newest line while "Follow" is on.
        ((INotifyCollectionChanged)Lines.Items).CollectionChanged += (_, e) =>
        {
            if (e.Action == NotifyCollectionChangedAction.Add && Follow.IsChecked == true && Lines.Items.Count > 0)
                Lines.ScrollIntoView(Lines.Items[^1]);
        };
    }
}
