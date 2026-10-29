using System.Windows;
using System.Windows.Controls;
using MobileController.Desktop.ViewModels;

namespace MobileController.Desktop;

public partial class MainWindow : Window
{
    private readonly MainViewModel _model;

    public MainWindow(MainViewModel model)
    {
        InitializeComponent();
        _model = model;
        DataContext = model;
        IsVisibleChanged += (_, _) => UpdateLive();
        StateChanged += (_, _) => UpdateLive();
    }

    private void OnPageChanged(object sender, SelectionChangedEventArgs e)
    {
        if (ReferenceEquals(e.OriginalSource, Pages)) UpdateLive();
    }

    /// <summary>Live input costs the server a little work: only ask for it while someone can see it.</summary>
    private void UpdateLive() =>
        _model.LiveWanted = IsVisible && WindowState != WindowState.Minimized && ReferenceEquals(Pages.SelectedItem, ControllersPage);
}
