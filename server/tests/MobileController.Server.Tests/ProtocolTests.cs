using System.Text;

namespace MobileController.Server.Tests;

/// <summary>PROTOCOL.md §9 golden vectors, row by row.</summary>
public sealed class ProtocolTests
{
    // §9.1
    [Theory]
    [InlineData("I1", "A5 11 00 00 00 00 00 00 00 00 00 00 00 00 00 00", 0x0000, 0x0000, 0, 0, 0, 0, 0, 0)]
    [InlineData("I2", "A5 11 34 12 01 10 00 FF FF 7F 01 80 FF FF 00 01", 0x1234, 0x1001, 0, 255, 32767, -32767, -1, 256)]
    [InlineData("I3", "A5 11 FF FF FF F7 80 01 00 80 39 30 C7 CF 00 00", 0xFFFF, 0xF7FF, 128, 1, -32768, 12345, -12345, 0)]
    public void Input_vectors_decode_and_encode(string id, string hex, int seq, int buttons, int lt, int rt, int lx, int ly, int rx, int ry)
    {
        var bytes = Hex.Bytes(hex);
        var expected = new PadState((ushort)buttons, (byte)lt, (byte)rt, (short)lx, (short)ly, (short)rx, (short)ry);

        Assert.True(Protocol.TryParseInput(bytes, out ushort parsedSeq, out var state), id);
        Assert.Equal((ushort)seq, parsedSeq);
        Assert.Equal(expected, state);

        var written = new byte[Protocol.InputSize];
        Assert.Equal(Protocol.InputSize, Protocol.WriteInput(written, (ushort)seq, expected));
        Assert.Equal(bytes, written);
    }

    [Fact]
    public void I4_reserved_button_bit_is_masked()
    {
        Assert.True(Protocol.TryParseInput(Hex.Bytes("A5 11 01 00 FF FF 00 00 00 00 00 00 00 00 00 00"), out ushort seq, out var state));
        Assert.Equal(1, seq);
        Assert.Equal(0xF7FF, state.Buttons);
        Assert.Equal(new PadState(0xF7FF, 0, 0, 0, 0, 0, 0), state);
    }

    // §9.2
    [Theory]
    [InlineData("R1", "A5 11 00 00 00 00 00 00 00 00 00 00 00 00 00", "BadLength")]
    [InlineData("R2", "A5 21 00 00 00 00 00 00 00 00 00 00 00 00 00 00", "BadVersion")]
    [InlineData("R3", "A4 11 00 00 00 00 00 00 00 00 00 00 00 00 00 00", "BadMagic")]
    [InlineData("R4", "A5 15 00 00", "UnknownType")]
    [InlineData("R5", "A5 19 FF 40 00 01 34 12", "WrongDirection")]
    [InlineData("R6", "A5 13 00", "BadLength")]
    public void Rejection_vectors_are_dropped_by_the_server(string id, string hex, string expected)
    {
        var bytes = Hex.Bytes(hex);
        Assert.Equal(expected, Protocol.Validate(bytes, atServer: true, out _).ToString());
        Assert.False(Protocol.TryParseInput(bytes, out _, out _), id);
    }

    [Theory]
    [InlineData("")]
    [InlineData("A5")]
    [InlineData("A5 10")]          // type 0
    [InlineData("A5 18 00 00")]    // type 8: server-to-client range but undefined
    [InlineData("A5 12 02 01 EF BE AD")] // PING one byte short
    [InlineData("A5 14 EF BE 00")] // DISCOVER one byte long
    public void Other_malformed_datagrams_are_dropped(string hex) =>
        Assert.NotEqual(PacketStatus.Ok, Protocol.Validate(Hex.Bytes(hex), atServer: true, out _));

    [Fact]
    public void R5_is_valid_in_the_other_direction()
    {
        Assert.True(Protocol.TryParseFeedback(Hex.Bytes("A5 19 FF 40 00 01 34 12"), out _));
        // ...and client-to-server packets are rejected by a client.
        Assert.Equal(PacketStatus.WrongDirection, Protocol.Validate(Hex.Bytes("A5 13"), atServer: false, out _));
    }

    // §9.3
    [Fact]
    public void P1_ping_and_P2_pong()
    {
        var p1 = Hex.Bytes("A5 12 02 01 EF BE AD DE");
        Assert.True(Protocol.TryParsePing(p1, out ushort id, out uint time));
        Assert.Equal(0x0102, id);
        Assert.Equal(0xDEADBEEF, time);

        var ping = new byte[Protocol.PingSize];
        Protocol.WritePing(ping, 0x0102, 0xDEADBEEF);
        Assert.Equal(p1, ping);

        var pong = new byte[Protocol.PongSize];
        Assert.Equal(Protocol.PongSize, Protocol.WritePong(pong, p1));
        Assert.Equal(Hex.Bytes("A5 1A 02 01 EF BE AD DE"), pong);
        Assert.True(Protocol.TryParsePong(pong, out id, out time));
        Assert.Equal((0x0102, 0xDEADBEEFu), (id, time));
    }

    [Fact]
    public void P3_bye()
    {
        var bye = new byte[Protocol.ByeSize];
        Protocol.WriteBye(bye);
        Assert.Equal(Hex.Bytes("A5 13"), bye);
        Assert.Equal(PacketStatus.Ok, Protocol.Validate(bye, atServer: true, out var type));
        Assert.Equal(PacketType.Bye, type);
    }

    [Fact]
    public void P4_discover()
    {
        var discover = new byte[Protocol.DiscoverSize];
        Protocol.WriteDiscover(discover, 0xBEEF);
        Assert.Equal(Hex.Bytes("A5 14 EF BE"), discover);
        Assert.True(Protocol.TryParseDiscover(discover, out ushort nonce));
        Assert.Equal(0xBEEF, nonce);
    }

    [Fact]
    public void P5_discover_reply()
    {
        var reply = new byte[Protocol.MaxDatagramSize];
        int length = Protocol.WriteDiscoverReply(reply, 0xBEEF, 1, 4, "PC"u8);
        Assert.Equal(Hex.Bytes("A5 1C EF BE 01 04 02 50 43"), reply[..length]);

        Assert.True(Protocol.TryParseDiscoverReply(reply.AsSpan(0, length), out ushort nonce, out byte inUse, out byte max, out var name));
        Assert.Equal((0xBEEF, 1, 4), (nonce, inUse, max));
        Assert.Equal("PC", Encoding.UTF8.GetString(name));
    }

    [Fact]
    public void P6_feedback()
    {
        var feedback = new byte[Protocol.FeedbackSize];
        Protocol.WriteFeedback(feedback, 255, 64, 0, FeedbackFlags.PadAttached, 0x1234);
        Assert.Equal(Hex.Bytes("A5 19 FF 40 00 01 34 12"), feedback);
        Assert.True(Protocol.TryParseFeedback(feedback, out var parsed));
        Assert.Equal(new FeedbackPacket(255, 64, 0, FeedbackFlags.PadAttached, 0x1234), parsed);
    }

    [Fact]
    public void P7_name_is_truncated_at_a_character_boundary()
    {
        string name = new string('A', 31) + "é"; // 33 UTF-8 bytes
        Span<byte> encoded = stackalloc byte[Protocol.MaxNameBytes];
        int n = Protocol.EncodeName(name, encoded);
        Assert.Equal(31, n);

        var reply = new byte[Protocol.MaxDatagramSize];
        int length = Protocol.WriteDiscoverReply(reply, 0, 0, 4, encoded[..n]);
        var expected = Hex.Bytes("A5 1C 00 00 00 04 1F").Concat(Enumerable.Repeat((byte)0x41, 31)).ToArray();
        Assert.Equal(expected, reply[..length]);
    }

    [Theory]
    [InlineData("PC", 2)]
    [InlineData("", 0)]
    [InlineData("0123456789abcdef0123456789abcdef", 32)]      // exactly 32 ASCII bytes
    [InlineData("0123456789abcdef0123456789abcdefXYZ", 32)]   // longer ASCII: hard cut
    [InlineData("0123456789abcdef0123456789abc😀", 29)]       // 29 + 4-byte emoji would be 33: emoji dropped whole
    [InlineData("0123456789abcdef0123456789a😀", 31)]         // 27 + 4 = 31 fits
    public void Names_are_encoded_without_splitting_characters(string name, int expectedBytes)
    {
        Span<byte> encoded = stackalloc byte[64];
        int n = Protocol.EncodeName(name, encoded);
        Assert.Equal(expectedBytes, n);
        Assert.StartsWith(Encoding.UTF8.GetString(encoded[..n]), name, StringComparison.Ordinal);
    }

    [Fact]
    public void P8_game_from_the_foreground_process_name()
    {
        Span<byte> name = stackalloc byte[Protocol.MaxGameNameBytes];
        int n = Protocol.NormalizeGameName("RocketLeague.exe", name);
        var game = new byte[Protocol.MaxDatagramSize];
        int length = Protocol.WriteGame(game, name[..n]);

        var p8 = Hex.Bytes("A5 1B 10 72 6F 63 6B 65 74 6C 65 61 67 75 65 2E 65 78 65");
        Assert.Equal(p8, game[..length]);
        Assert.True(Protocol.TryParseGame(p8, out var parsed));
        Assert.Equal("rocketleague.exe", Encoding.ASCII.GetString(parsed));
    }

    [Fact]
    public void P9_game_unknown()
    {
        var game = new byte[Protocol.MaxDatagramSize];
        Assert.Equal(3, Protocol.WriteGame(game, []));
        Assert.Equal(Hex.Bytes("A5 1B 00"), game[..3]);
        Assert.True(Protocol.TryParseGame(game.AsSpan(0, 3), out var name));
        Assert.True(name.IsEmpty);
    }

    [Theory]
    [InlineData("A5 1B 05 61 62")]   // §9.3: N = 5 but 2 bytes follow
    [InlineData("A5 1B 01 61 62")]   // one byte too many
    [InlineData("A5 1B")]            // no length byte
    public void Malformed_games_are_rejected_by_the_client(string hex)
    {
        Assert.Equal(PacketStatus.BadLength, Protocol.Validate(Hex.Bytes(hex), atServer: false, out _));
        Assert.False(Protocol.TryParseGame(Hex.Bytes(hex), out _));
    }

    [Fact]
    public void Game_with_N_above_64_is_rejected_by_the_client()
    {
        var tooLong = Hex.Bytes("A5 1B 41").Concat(Enumerable.Repeat((byte)0x61, 65)).ToArray(); // §9.3
        Assert.Equal(PacketStatus.BadLength, Protocol.Validate(tooLong, atServer: false, out _));
        Assert.False(Protocol.TryParseGame(tooLong, out _));

        var longest = Hex.Bytes("A5 1B 40").Concat(Enumerable.Repeat((byte)0x61, 64)).ToArray();
        Assert.True(Protocol.TryParseGame(longest, out var name));
        Assert.Equal(64, name.Length);
        Assert.Throws<ArgumentOutOfRangeException>(() => Protocol.WriteGame(new byte[100], new byte[65]));
    }

    [Theory]
    [InlineData("A5 1B 10 72 6F 63 6B 65 74 6C 65 61 67 75 65 2E 65 78 65")] // P8
    [InlineData("A5 1B 00")]                                                 // P9
    [InlineData("A5 1B 05 61 62")]                                           // even malformed: direction is checked first
    public void Game_is_dropped_by_the_server_as_wrong_direction(string hex) =>
        Assert.Equal(PacketStatus.WrongDirection, Protocol.Validate(Hex.Bytes(hex), atServer: true, out _));

    [Theory]
    [InlineData("RocketLeague.exe", "rocketleague.exe")]
    [InlineData(@"C:\Program Files\Epic Games\rocketleague\Binaries\Win64\RocketLeague.exe", "rocketleague.exe")]
    [InlineData(@"\\?\D:\Games\FH5\ForzaHorizon5.EXE", "forzahorizon5.exe")]
    [InlineData("C:/Games/Mixed/Sep\\Game.Exe", "game.exe")]
    [InlineData("Ünïcödé Spiel.exe", "?n?c?d? spiel.exe")]       // non-ASCII → '?', the space stays
    [InlineData("游戏.exe", "??.exe")]
    [InlineData("😀game.exe", "?game.exe")]                       // a surrogate pair is one character
    [InlineData("tab\there\u007F.exe", "tab?here?.exe")]          // control characters and DEL
    [InlineData("A-Z_a-z 0-9 ~!{}.exe", "a-z_a-z 0-9 ~!{}.exe")]  // printable ASCII kept, only letters lowered
    [InlineData("", "")]
    [InlineData(@"C:\Games\", "")]                                // no file name at all
    public void Game_names_are_normalised(string path, string expected)
    {
        Span<byte> name = stackalloc byte[Protocol.MaxGameNameBytes];
        int n = Protocol.NormalizeGameName(path, name);
        Assert.Equal(expected, Encoding.ASCII.GetString(name[..n]));
    }

    [Fact]
    public void Lone_surrogates_become_one_question_mark_each()
    {
        Span<byte> name = stackalloc byte[Protocol.MaxGameNameBytes];
        int n = Protocol.NormalizeGameName("\ud800x\udc00.exe\ud83d", name);
        Assert.Equal("?x?.exe?", Encoding.ASCII.GetString(name[..n]));
    }

    [Fact]
    public void Game_names_are_cut_at_64_bytes()
    {
        string exactly64 = new string('B', 60) + ".exe";
        string longer = @"C:\x\" + new string('C', 70) + ".exe";
        Span<byte> name = stackalloc byte[128];

        Assert.Equal(64, Protocol.NormalizeGameName(exactly64, name));
        Assert.Equal(new string('b', 60) + ".exe", Encoding.ASCII.GetString(name[..64]));

        Assert.Equal(64, Protocol.NormalizeGameName(longer, name)); // even with a larger destination
        Assert.Equal(new string('c', 64), Encoding.ASCII.GetString(name[..64]));

        // Characters, not UTF-16 units, count: 70 emoji (140 chars) still give 64 bytes.
        Assert.Equal(64, Protocol.NormalizeGameName(string.Concat(Enumerable.Repeat("😀", 70)), name));
        Assert.Equal(new string('?', 64), Encoding.ASCII.GetString(name[..64]));
    }

    [Theory]
    [InlineData("A5 1C EF BE 01 04 21" +" 41 41 41 41 41 41 41 41 41 41 41 41 41 41 41 41 41 41 41 41 41 41 41 41 41 41 41 41 41 41 41 41 41")] // N = 33
    [InlineData("A5 1C EF BE 01 04 02 50")]       // N = 2 but only 1 name byte
    [InlineData("A5 1C EF BE 01 04 02 50 43 44")] // one byte too many
    [InlineData("A5 1C EF BE 01 04")]             // header cut short
    public void Malformed_discover_replies_are_rejected(string hex) =>
        Assert.False(Protocol.TryParseDiscoverReply(Hex.Bytes(hex), out _, out _, out _, out _));

    // §9.4
    [Theory]
    [InlineData(100, 101, true)]
    [InlineData(100, 100, false)]
    [InlineData(100, 99, false)]
    [InlineData(100, 36, false)]
    [InlineData(100, 35, true)]
    [InlineData(65535, 0, true)]
    [InlineData(0, 65535, false)]
    [InlineData(0, 65472, false)]
    [InlineData(0, 65471, true)]
    [InlineData(10, 5000, true)]
    public void Sequence_acceptance_table(int lastAccepted, int incoming, bool accepted)
    {
        var verdict = Protocol.CheckSequence(hasAccepted: true, timedOut: false, (ushort)lastAccepted, (ushort)incoming);
        Assert.Equal(accepted, verdict == SequenceVerdict.Accept);
        if (!accepted)
            Assert.Equal(lastAccepted == incoming ? SequenceVerdict.Duplicate : SequenceVerdict.Stale, verdict);
    }

    [Fact]
    public void First_packet_and_timed_out_slot_always_accept()
    {
        Assert.Equal(SequenceVerdict.Accept, Protocol.CheckSequence(hasAccepted: false, timedOut: false, 0, 0));
        Assert.Equal(SequenceVerdict.Accept, Protocol.CheckSequence(hasAccepted: true, timedOut: true, 100, 100));
        Assert.Equal(SequenceVerdict.Accept, Protocol.CheckSequence(hasAccepted: true, timedOut: true, 100, 99));
    }

    [Fact]
    public void PadState_has_the_XUSB_report_size() =>
        Assert.Equal(12, System.Runtime.CompilerServices.Unsafe.SizeOf<PadState>());
}
