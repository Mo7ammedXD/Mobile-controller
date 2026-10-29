using System.IO;
using System.IO.Pipes;
using System.Text;
using System.Text.Json;
using System.Windows.Threading;
using MobileController.Control;

namespace MobileController.Desktop.Services;

/// <summary>
/// The desktop side of the control pipes (<see cref="ControlProtocol"/>): connects (retrying while the server starts),
/// reads events on a thread of its own and raises them on the UI thread, and writes commands.
/// </summary>
public sealed class ControlClient(Dispatcher dispatcher) : IDisposable
{
    private readonly Lock _gate = new();
    private CancellationTokenSource? _session;
    private StreamWriter? _commands;
    private int _nextId;

    public event Action<HelloMessage>? Hello;
    public event Action<SnapshotMessage>? Snapshot;
    public event Action<LiveMessage>? Live;
    public event Action<LogMessage>? Log;
    public event Action<ReplyMessage>? Reply;

    /// <summary>The connection was lost (the server stopped or restarted).</summary>
    public event Action? Disconnected;

    public bool IsConnected { get; private set; }

    /// <summary>Starts connecting to <paramref name="pipeName"/>; keeps trying until it works or <see cref="Disconnect"/>.</summary>
    public void Connect(string pipeName)
    {
        Disconnect();
        var session = new CancellationTokenSource();
        _session = session;
        new Thread(() => Run(pipeName, session.Token)) { IsBackground = true, Name = "control pipe reader" }.Start();
    }

    public void Disconnect()
    {
        _session?.Cancel();
        _session = null;
        lock (_gate)
        {
            _commands?.Dispose();
            _commands = null;
        }
    }

    /// <summary>Sends a command; the server's <see cref="ReplyMessage"/> carries the returned id. -1 when not connected.</summary>
    public int Send(string op, int? index = null, string? value = null)
    {
        lock (_gate)
        {
            if (_commands is null) return -1;
            int id = ++_nextId;
            try
            {
                _commands.WriteLine(ControlProtocol.Serialize(new CommandMessage(id, op, index, value)));
                return id;
            }
            catch (IOException)
            {
                return -1;
            }
        }
    }

    private void Run(string pipeName, CancellationToken token)
    {
        while (!token.IsCancellationRequested)
        {
            using var events = new NamedPipeClientStream(".", ControlProtocol.EventsPipe(pipeName), PipeDirection.In, PipeOptions.Asynchronous);
            using var commands = new NamedPipeClientStream(".", ControlProtocol.CommandsPipe(pipeName), PipeDirection.Out, PipeOptions.Asynchronous);
            try
            {
                // The server may still be starting: try often, it is a local pipe.
                events.ConnectAsync(250, token).GetAwaiter().GetResult();
                commands.ConnectAsync(2000, token).GetAwaiter().GetResult();
            }
            catch (TimeoutException)
            {
                continue;
            }
            catch (OperationCanceledException)
            {
                return;
            }
            catch (IOException)
            {
                token.WaitHandle.WaitOne(250);
                continue;
            }

            lock (_gate) _commands = new StreamWriter(commands, new UTF8Encoding(false)) { AutoFlush = true };
            IsConnected = true;
            try
            {
                using var reader = new StreamReader(events, Encoding.UTF8);
                while (!token.IsCancellationRequested)
                {
                    string? line = reader.ReadLineAsync(token).AsTask().GetAwaiter().GetResult();
                    if (line is null) break;
                    ControlMessage? message;
                    try
                    {
                        message = ControlProtocol.ParseEvent(line);
                    }
                    catch (JsonException)
                    {
                        continue;
                    }
                    if (message is not null) dispatcher.BeginInvoke(DispatcherPriority.Background, () => Raise(message));
                }
            }
            catch (OperationCanceledException)
            {
                return;
            }
            catch (IOException)
            {
            }
            finally
            {
                IsConnected = false;
                lock (_gate)
                {
                    _commands?.Dispose();
                    _commands = null;
                }
            }
            if (!token.IsCancellationRequested) dispatcher.BeginInvoke(() => Disconnected?.Invoke());
            // The server restarts with the same pipe name; reconnect to the new one.
            token.WaitHandle.WaitOne(250);
        }
    }

    private void Raise(ControlMessage message)
    {
        switch (message)
        {
            case HelloMessage m: Hello?.Invoke(m); break;
            case SnapshotMessage m: Snapshot?.Invoke(m); break;
            case LiveMessage m: Live?.Invoke(m); break;
            case LogMessage m: Log?.Invoke(m); break;
            case ReplyMessage m: Reply?.Invoke(m); break;
        }
    }

    public void Dispose() => Disconnect();
}
