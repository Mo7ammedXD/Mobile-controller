using System.Diagnostics;

namespace MobileController.Server;

/// <summary>
/// How one client's INPUT stream arrives: the spacing of packets (mean, jitter, percentiles of the gaps) and how many
/// were lost on the way, measured in windows of about a second. The phone sends a heartbeat at its configured rate
/// even when nothing changes, so long gaps mean Wi-Fi power saving or congestion, not an idle thumb. Loss is counted
/// from the sequence numbers: they advance by one per INPUT the phone sent (§3.3).
/// Receive thread only; everything is preallocated per slot, so measuring never allocates.
/// </summary>
internal sealed class SlotMetrics
{
    /// <summary>Gaps remembered for the percentiles; at 120 Hz about two windows.</summary>
    public const int GapCapacity = 256;

    private static readonly double MsPerTick = 1000.0 / Stopwatch.Frequency;

    private readonly float[] _gaps = new float[GapCapacity];
    private readonly float[] _sorted = new float[GapCapacity];
    private int _gapNext, _gapCount;
    private long _lastArrival;

    private bool _windowOpen;
    private ushort _windowFirstSeq, _windowLastSeq;
    private int _windowAccepted;

    /// <summary>Smoothed time between INPUTs and its smoothed deviation (RFC 3550 style, gain 1/16), in ms.</summary>
    public double MeanGapMs { get; private set; }
    public double JitterMs { get; private set; }

    // Results of the last completed window.
    public double InputsPerSecond { get; private set; }
    public double LossPercent { get; private set; }
    public double GapP50Ms { get; private set; }
    public double GapP95Ms { get; private set; }
    public double GapP99Ms { get; private set; }
    public double GapMaxMs { get; private set; }

    /// <summary>An accepted INPUT arrived (duplicates and stale packets are not counted).</summary>
    public void OnAccepted(long now, ushort seq)
    {
        if (_lastArrival != 0)
        {
            double gap = (now - _lastArrival) * MsPerTick;
            if (MeanGapMs == 0) MeanGapMs = gap;
            else MeanGapMs += (gap - MeanGapMs) / 16;
            JitterMs += (Math.Abs(gap - MeanGapMs) - JitterMs) / 16;
            _gaps[_gapNext] = (float)gap;
            _gapNext = (_gapNext + 1) % GapCapacity;
            if (_gapCount < GapCapacity) _gapCount++;
        }
        _lastArrival = now;

        // Accepted packets only move forward, except when the phone restarts its counter (§3.3 rule 5): then the
        // window restarts too, or the jump would count as tens of thousands of lost packets.
        if (_windowOpen && (ushort)(seq - _windowLastSeq) >= 0x8000) _windowOpen = false;
        if (!_windowOpen)
        {
            _windowOpen = true;
            _windowFirstSeq = seq;
            _windowAccepted = 0;
        }
        _windowLastSeq = seq;
        _windowAccepted++;
    }

    /// <summary>The input stream paused (neutral timeout): the silence is not a gap of the stream.</summary>
    public void OnPause()
    {
        _lastArrival = 0;
        _windowOpen = false;
    }

    /// <summary>Closes the current window of <paramref name="seconds"/> and computes its results.</summary>
    public void Roll(double seconds)
    {
        if (_windowOpen && _windowAccepted > 0)
        {
            int sent = (ushort)(_windowLastSeq - _windowFirstSeq) + 1;
            LossPercent = sent > 1 ? Math.Max(0, 100.0 * (1 - (double)_windowAccepted / sent)) : 0;
            InputsPerSecond = seconds > 0 ? _windowAccepted / seconds : 0;
        }
        else
        {
            LossPercent = 0;
            InputsPerSecond = 0;
        }
        // The next window starts where this one ended, so a packet lost across the boundary still counts.
        _windowFirstSeq = (ushort)(_windowLastSeq + 1);
        _windowAccepted = 0;

        int n = _gapCount;
        if (n == 0)
        {
            GapP50Ms = GapP95Ms = GapP99Ms = GapMaxMs = 0;
            return;
        }
        Array.Copy(_gaps, _sorted, n);
        Array.Sort(_sorted, 0, n);
        GapP50Ms = _sorted[(n - 1) * 50 / 100];
        GapP95Ms = _sorted[(n - 1) * 95 / 100];
        GapP99Ms = _sorted[(n - 1) * 99 / 100];
        GapMaxMs = _sorted[n - 1];
    }
}
