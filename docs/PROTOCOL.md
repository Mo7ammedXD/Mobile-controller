# Mobile Controller — Wire Protocol v1

**Status: normative.** The Windows server (`server/`) and the Android client (`android/`) both implement
exactly this document. The golden vectors in §9 are executed by both test suites, so the two sides cannot
drift apart silently.

---

## 1. Transport

| Property | Value |
|---|---|
| Protocol | UDP over IPv4 |
| Server socket | binds `0.0.0.0:24680` (port configurable) |
| Client socket | OS-assigned ephemeral port, `connect()`ed to the server |
| Byte order | **little-endian** for every multi-byte integer; signed values are two's complement |
| Reliability | none — no handshake, no ACKs, no retransmits |

* Every `INPUT` carries the **complete** controller state (a snapshot, never a delta), so a lost datagram is
  repaired by the next one. This is what makes UDP safe here.
* The server identifies a client by its **source endpoint (IP:port)**. One endpoint = one virtual
  Xbox 360 controller. Default maximum: 4 (the XInput limit).
* Server → client datagrams (`FEEDBACK`, `PONG`, `DISCOVER_REPLY`) are sent to the source endpoint of the
  client's datagrams.

## 2. Common header (2 bytes)

| Offset | Size | Field | Value |
|---|---|---|---|
| 0 | 1 | `magic` | `0xA5` |
| 1 | 1 | `verType` | `(version << 4) \| type`, `version = 1` |

| Type | Name | Direction | Total size (bytes) | `verType` byte |
|---|---|---|---|---|
| `0x1` | `INPUT` | client → server | 16 | `0x11` |
| `0x2` | `PING` | client → server | 8 | `0x12` |
| `0x3` | `BYE` | client → server | 2 | `0x13` |
| `0x4` | `DISCOVER` | client → server | 4 | `0x14` |
| `0x9` | `FEEDBACK` | server → client | 8 | `0x19` |
| `0xA` | `PONG` | server → client | 8 | `0x1A` |
| `0xB` | `GAME` | server → client | 3 + N (N ≤ 64) | `0x1B` |
| `0xC` | `DISCOVER_REPLY` | server → client | 7 + N (N ≤ 32) | `0x1C` |

Types `0x1–0x7` travel client → server; types `0x8–0xF` travel server → client.

**Validation.** A receiver MUST silently drop a datagram when any of these hold:
length < 2 · `magic ≠ 0xA5` · `version ≠ 1` · unknown type · type not valid for this direction ·
length ≠ the type's exact size (`DISCOVER_REPLY`: length ≠ 7 + N, or N > 32; `GAME`: length ≠ 3 + N, or N > 64).
The server SHOULD log a version mismatch once per endpoint (not per packet).

## 3. `INPUT` — client → server, 16 bytes (the hot path)

| Offset | Size | Type | Field |
|---|---|---|---|
| 0 | 1 | u8 | `magic` = `0xA5` |
| 1 | 1 | u8 | `verType` = `0x11` |
| 2 | 2 | u16 | `seq` |
| 4 | 2 | u16 | `buttons` — XUSB bitmask (below) |
| 6 | 1 | u8 | `leftTrigger` 0‥255 |
| 7 | 1 | u8 | `rightTrigger` 0‥255 |
| 8 | 2 | i16 | `thumbLX` |
| 10 | 2 | i16 | `thumbLY` |
| 12 | 2 | i16 | `thumbRX` |
| 14 | 2 | i16 | `thumbRY` |

### 3.1 Button bits — identical to `XINPUT_GAMEPAD_*` / `XUSB_REPORT.wButtons`

| Mask | Button | Mask | Button |
|---|---|---|---|
| `0x0001` | D-pad Up | `0x0100` | LB (left shoulder) |
| `0x0002` | D-pad Down | `0x0200` | RB (right shoulder) |
| `0x0004` | D-pad Left | `0x0400` | Guide |
| `0x0008` | D-pad Right | `0x0800` | **reserved** |
| `0x0010` | Start (Menu) | `0x1000` | A |
| `0x0020` | Back (View) | `0x2000` | B |
| `0x0040` | L3 (left stick click) | `0x4000` | X |
| `0x0080` | R3 (right stick click) | `0x8000` | Y |

* Clients MUST send the reserved bit as 0. The server MUST mask it off: `buttons & 0xF7FF`.
* Because the layout *is* the XUSB report layout, the server hands the masked value straight to the virtual
  controller. There is no per-button translation.
* Why 2 bytes, not 1: an Xbox 360 pad has 15 digital inputs (11 buttons + 4 D-pad directions), and 8 bits
  can't represent them.

### 3.2 Axes and triggers

* Sticks: `i16`, range −32768‥32767 (XInput `SHORT`). Clients SHOULD clamp to **−32767‥+32767** so that the
  stick is symmetric. Servers MUST accept −32768.
* **+X = right, +Y = UP** (XInput convention). Screen coordinates grow downward, so the client MUST negate
  screen-space Y.
* Deadzones belong to the game (XInput titles apply their own). A client MAY apply a small radial deadzone
  to suppress touch jitter at the centre.
* Triggers: `u8` 0‥255. A digital (touch) trigger sends 0 or 255.

### 3.3 Sequence number and server acceptance

* `seq` starts at 0 when the client socket is created and increments by 1 (mod 65536) for **every** `INPUT`
  sent.
* For each client endpoint the server keeps `lastAccepted`. Compute `d = (seq − lastAccepted) mod 65536`, then
  apply the first rule that matches:

| # | Condition | Result |
|---|---|---|
| 1 | first `INPUT` ever seen from this endpoint | accept |
| 2 | the endpoint's slot is in the timed-out (neutral) state (§4) | accept (resync) |
| 3 | `d == 0` | drop (duplicate) |
| 4 | `d ≥ 65472` (seq is 1‥64 behind) | drop (reordered / stale) |
| 5 | otherwise | accept (newer; or more than 64 behind, meaning the client restarted its counter) |

* Accepting a packet replaces the **entire** controller state with that packet's state.

### 3.4 Client send policy

* **Heartbeat:** while the controller screen is active, the client MUST send an `INPUT` at least every
  `1000 / R` ms, where `R` is the configured rate (60–120 Hz typical, **default 120 Hz**). The server never
  assumes a particular rate.
* **Event-driven:** when the state changes, the client SHOULD send immediately rather than waiting for the
  next heartbeat tick. Changes are coalesced, with at most one `INPUT` per 2 ms.
* **Press latching:** a button press that begins and ends between two sends MUST still appear as pressed in
  at least one `INPUT`.
* **Pause / background:** the client sends one neutral `INPUT` immediately, then stops sending. The server
  neutralises on timeout anyway (§4), but this releases held inputs instantly.

## 4. Server-side controller lifecycle

| Event | Server behaviour |
|---|---|
| First accepted `INPUT` from a new endpoint | Create and plug a virtual Xbox 360 pad if a slot is free. If none is free, reply `FEEDBACK` with `SERVER_FULL` (globally rate-limited to one per 250 ms). |
| No accepted `INPUT` for **500 ms** (`--neutral-timeout`) | Submit an all-zero report once and mark the slot timed-out. |
| No datagram of any type from the endpoint for **10 000 ms** (`--disconnect-timeout`) | Unplug the pad and free the slot. |
| `BYE` | Unplug the pad and free the slot immediately. |
| Report identical to the last one submitted | MAY skip the driver call. |
| Server shutdown | Unplug every pad. |

## 5. `FEEDBACK` — server → client, 8 bytes

| Offset | Size | Type | Field |
|---|---|---|---|
| 0 | 1 | u8 | `magic` = `0xA5` |
| 1 | 1 | u8 | `verType` = `0x19` |
| 2 | 1 | u8 | `largeMotor` — low-frequency (left) rumble motor, 0‥255 |
| 3 | 1 | u8 | `smallMotor` — high-frequency (right) rumble motor, 0‥255 |
| 4 | 1 | u8 | `playerIndex` — 0‥3 (player 1‥4), `0xFF` = unknown |
| 5 | 1 | u8 | `flags` |
| 6 | 2 | u16 | `lastSeq` — the most recent `INPUT` seq the server accepted from this client (0 if none) |

`flags`: bit 0 `PAD_ATTACHED` (a virtual pad is plugged for this client) · bit 1 `SERVER_FULL` (no free slot) ·
bit 2 `DRY_RUN` (server started with `--dry-run`, so no virtual pad exists) · bits 3‥7 are 0.

* The server sends `FEEDBACK` **immediately** when the game changes rumble (ViGEm feedback callback), and
  **every 100 ms** to every client that holds a slot. The periodic send is a heartbeat and also repairs lost
  rumble packets.
* The client treats itself as *connected* while a `FEEDBACK` has arrived within the last 1000 ms.
* The client MUST stop rumbling if no `FEEDBACK` has arrived for **300 ms**, so that a lost "stop" packet
  can never leave the phone vibrating.

## 6. `PING` / `PONG` — round-trip time

| Offset | Size | `PING` (client → server, 8 bytes) | `PONG` (server → client, 8 bytes) |
|---|---|---|---|
| 0 | 1 | `0xA5` | `0xA5` |
| 1 | 1 | `0x12` | `0x1A` |
| 2 | 2 | u16 `pingId` | copied from the `PING` |
| 4 | 4 | u32 `clientTime` (opaque to the server) | copied from the `PING` |

* The server replies **immediately** from its receive loop, whether or not the endpoint holds a slot.
* The client sends about one `PING` per second. RTT = now − the send time of the matching `pingId`.

## 7. `BYE` — client → server, 2 bytes

`A5 13`. The client sends it 3 times (it's unreliable) when the user exits the controller.

## 8. Discovery

**`DISCOVER`** (client → server, 4 bytes): `A5 14` + u16 `nonce`. The client sends it with `SO_BROADCAST` to
`255.255.255.255:24680` **and** to the directed broadcast address of every up IPv4 interface
(e.g. `192.168.1.255`, the USB-tethering subnet's broadcast).

**`DISCOVER_REPLY`** (server → client, 7 + N bytes):

| Offset | Size | Type | Field |
|---|---|---|---|
| 0 | 1 | u8 | `0xA5` |
| 1 | 1 | u8 | `0x1C` |
| 2 | 2 | u16 | `nonce` (echo) |
| 4 | 1 | u8 | `padsInUse` |
| 5 | 1 | u8 | `maxPads` |
| 6 | 1 | u8 | `N` — name length, 0‥32 |
| 7 | N | UTF-8 | server name (machine name), truncated to ≤ 32 bytes **at a character boundary** |

The server unicasts the reply to the `DISCOVER`'s source endpoint. The client uses the reply's **source IP and
port** as the server address.

## 9. Golden test vectors (hex)

### 9.1 `INPUT`

| # | Meaning | Bytes |
|---|---|---|
| I1 | neutral, seq 0 | `A5 11 00 00 00 00 00 00 00 00 00 00 00 00 00 00` |
| I2 | seq `0x1234`, buttons A + D-pad Up (`0x1001`), LT 0, RT 255, LX 32767, LY −32767, RX −1, RY 256 | `A5 11 34 12 01 10 00 FF FF 7F 01 80 FF FF 00 01` |
| I3 | seq `0xFFFF`, buttons `0xF7FF` (all), LT 128, RT 1, LX −32768, LY 12345, RX −12345, RY 0 | `A5 11 FF FF FF F7 80 01 00 80 39 30 C7 CF 00 00` |
| I4 | seq 1, raw buttons `0xFFFF` → decodes to `0xF7FF` (reserved bit masked) | `A5 11 01 00 FF FF 00 00 00 00 00 00 00 00 00 00` |

### 9.2 Must be rejected

| # | Bytes | Reason |
|---|---|---|
| R1 | `A5 11 00 00 00 00 00 00 00 00 00 00 00 00 00` | 15 bytes, wrong length |
| R2 | `A5 21 00 00 00 00 00 00 00 00 00 00 00 00 00 00` | version 2 |
| R3 | `A4 11 00 00 00 00 00 00 00 00 00 00 00 00 00 00` | bad magic |
| R4 | `A5 15 00 00` | unknown type 5 |
| R5 | `A5 19 FF 40 00 01 34 12` received by the **server** | wrong direction |
| R6 | `A5 13 00` | `BYE` must be exactly 2 bytes |

### 9.3 Other packets

| # | Packet | Bytes |
|---|---|---|
| P1 | `PING` id `0x0102`, clientTime `0xDEADBEEF` | `A5 12 02 01 EF BE AD DE` |
| P2 | `PONG` reply to P1 | `A5 1A 02 01 EF BE AD DE` |
| P3 | `BYE` | `A5 13` |
| P4 | `DISCOVER` nonce `0xBEEF` | `A5 14 EF BE` |
| P5 | `DISCOVER_REPLY` nonce `0xBEEF`, 1 in use, 4 max, name `"PC"` | `A5 1C EF BE 01 04 02 50 43` |
| P6 | `FEEDBACK` large 255, small 64, player 0, flags `PAD_ATTACHED`, lastSeq `0x1234` | `A5 19 FF 40 00 01 34 12` |
| P7 | `DISCOVER_REPLY` name = 31 × `A` + `é` (33 UTF-8 bytes) → the `é` is dropped whole: N = 31 | `A5 1C 00 00 00 04 1F` + 31 × `41` |
| P8 | `GAME` `"rocketleague.exe"` (foreground process `RocketLeague.exe`, lower-cased) | `A5 1B 10 72 6F 63 6B 65 74 6C 65 61 67 75 65 2E 65 78 65` |
| P9 | `GAME` unknown / no foreground game | `A5 1B 00` |

`GAME` rejections (client): `A5 1B 05 61 62` (N = 5 but 2 bytes follow) · `A5 1B 41` + 65 bytes (N > 64).

### 9.4 Sequence acceptance (§3.3, slot active, not timed-out)

| lastAccepted | incoming | result |
|---|---|---|
| 100 | 101 | accept |
| 100 | 100 | drop |
| 100 | 99 | drop |
| 100 | 36 | drop (64 behind) |
| 100 | 35 | accept (65 behind → restart) |
| 65535 | 0 | accept (wrap) |
| 0 | 65535 | drop |
| 0 | 65472 | drop (64 behind) |
| 0 | 65471 | accept |
| 10 | 5000 | accept (jump after loss) |

## 10. `GAME` — foreground game hint, server → client, 3 + N bytes

Lets the phone switch to the right game mode by itself (the phone keeps the table of which game uses
which mode; the server only reports what is running).

| Offset | Size | Type | Field |
|---|---|---|---|
| 0 | 1 | u8 | `0xA5` |
| 1 | 1 | u8 | `0x1B` |
| 2 | 1 | u8 | `N` — name length, 0‥64 |
| 3 | N | ASCII | file name of the process that owns the foreground window, without path, **lower-cased** (A–Z), every character outside printable ASCII 0x20‥0x7E replaced by one `?`, truncated to 64 bytes; e.g. `rocketleague.exe`. N = 0: unknown (no foreground window, or its process name could not be read). Microsoft Store (UWP) apps report their frame host, `applicationframehost.exe`. |

* The server polls the foreground window about twice a second. It sends `GAME` to every client that
  holds a slot **when the name changes**, to a client **right after it gets a slot**, and **every
  2000 ms** to every slot holder (repetition for loss). It is never sent with `--test-pattern`; it is sent
  in `--dry-run`. `--no-game-detect` disables it entirely (privacy).
* The client ignores names it does not know, never switches mode while the user is editing a layout or
  has the settings open, and only switches when the user enabled automatic mode switching.
* Old clients drop the unknown type (§2), so this is compatible with v1 clients.

## 11. Security note

UDP has no authentication. Anyone who can reach UDP 24680 on the PC can drive the virtual controller. Keep
the firewall rule scoped to `LocalSubnet`, and use the server's `--allow <ip>` option to pin a specific phone.
