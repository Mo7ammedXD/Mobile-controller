namespace MobileController.Server.Control;

internal enum CommandOp : byte
{
    Live,
    Identify,
    Disconnect,
    RumbleTest,
    Output,
}

/// <summary>Why a command failed (<see cref="CommandError.None"/> = it worked).</summary>
internal enum CommandError : byte
{
    None,
    NoSuchSlot,

    /// <summary>The phone has not sent STATUS (an older app): it cannot be identified or given a per-phone pad.</summary>
    NeedsStatus,
}

/// <summary>A command for the receive thread. <see cref="Value"/>: Live 1/0; Output 0 = phone's choice, 1 = Xbox 360, 2 = DS4.</summary>
internal readonly record struct ControlCommand(int Id, CommandOp Op, int Index, int Value);

internal readonly record struct CommandReply(int Id, CommandError Error);

/// <summary>
/// A single-producer single-consumer queue of fixed capacity: the producer and the consumer each own one index and
/// publish it with a volatile write, so neither ever locks or allocates. Used for commands (control thread → receive
/// thread) and their replies (back).
/// </summary>
internal sealed class SpscRing<T> where T : struct
{
    private readonly T[] _items;
    private readonly int _mask;
    private int _head; // next to read, owned by the consumer
    private int _tail; // next to write, owned by the producer

    public SpscRing(int capacityPowerOfTwo)
    {
        if (capacityPowerOfTwo < 2 || (capacityPowerOfTwo & (capacityPowerOfTwo - 1)) != 0)
            throw new ArgumentOutOfRangeException(nameof(capacityPowerOfTwo));
        _items = new T[capacityPowerOfTwo];
        _mask = capacityPowerOfTwo - 1;
    }

    /// <summary>Producer only. False when full (the item is dropped).</summary>
    public bool TryEnqueue(in T item)
    {
        int tail = _tail;
        int next = (tail + 1) & _mask;
        if (next == Volatile.Read(ref _head)) return false;
        _items[tail] = item;
        Volatile.Write(ref _tail, next);
        return true;
    }

    /// <summary>Consumer only.</summary>
    public bool TryDequeue(out T item)
    {
        int head = _head;
        if (head == Volatile.Read(ref _tail))
        {
            item = default;
            return false;
        }
        item = _items[head];
        Volatile.Write(ref _head, (head + 1) & _mask);
        return true;
    }
}
