namespace MobileController.Server;

/// <summary>
/// Endpoints that sent something but hold no slot yet (§4 "status-aware clients"): when their first INPUT arrived, and
/// their latest STATUS, so the pad that is eventually plugged in is of the right kind, or the slot of the same phone on
/// its old endpoint is moved instead. A fixed table, written by the receive thread only; when it is full the oldest
/// entry is replaced, which at worst costs a phone the short STATUS wait a second time.
/// </summary>
internal sealed class PendingEndpoints
{
    public const int Capacity = 16;

    private readonly ulong[] _keys = new ulong[Capacity];
    private readonly long[] _touched = new long[Capacity];
    private readonly long[] _firstInput = new long[Capacity];
    private readonly byte[][] _status = new byte[Capacity][];
    private readonly int[] _statusLength = new int[Capacity];
    private readonly bool[] _used = new bool[Capacity];

    public PendingEndpoints()
    {
        for (int i = 0; i < Capacity; i++) _status[i] = new byte[Protocol.MaxStatusSize];
    }

    /// <summary>The entry of <paramref name="key"/>, created if needed. Its first INPUT time is 0 until <see cref="MarkInput"/>.</summary>
    public int Get(ulong key, long now)
    {
        int oldest = 0;
        for (int i = 0; i < Capacity; i++)
        {
            if (_used[i] && _keys[i] == key)
            {
                _touched[i] = now;
                return i;
            }
            if (!_used[oldest]) continue;
            if (!_used[i] || _touched[i] < _touched[oldest]) oldest = i;
        }
        _used[oldest] = true;
        _keys[oldest] = key;
        _touched[oldest] = now;
        _firstInput[oldest] = 0;
        _statusLength[oldest] = 0;
        return oldest;
    }

    /// <summary>When the endpoint's first INPUT arrived (recorded on the first call).</summary>
    public long MarkInput(int entry, long now)
    {
        if (_firstInput[entry] == 0) _firstInput[entry] = now;
        return _firstInput[entry];
    }

    public void SetStatus(int entry, ReadOnlySpan<byte> datagram)
    {
        datagram.CopyTo(_status[entry]);
        _statusLength[entry] = datagram.Length;
    }

    public bool HasStatus(int entry) => _statusLength[entry] != 0;

    /// <summary>The entry's last STATUS datagram (validated when it was stored).</summary>
    public ReadOnlySpan<byte> Status(int entry) => _status[entry].AsSpan(0, _statusLength[entry]);

    public void Remove(int entry) => _used[entry] = false;
}
