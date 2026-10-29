# Mobile Controller

Turns Android phones into game controllers for a Windows PC. The phone draws a touch gamepad and sends its
state over UDP. The PC presents each phone to games as a virtual Xbox 360 controller through ViGEmBus.

| Part | What it is |
|---|---|
| `android/` | The phone app (Kotlin, no runtime dependencies) |
| `server/src/MobileController.Server` | The Windows server: a .NET 10 console app driving ViGEmBus |
| `server/src/MobileController.Desktop` | The desktop app (WPF): starts and watches the server, with status, diagnostics and settings |
| `server/src/MobileController.Control` | What the server and the desktop app share: control-pipe messages, the settings file, adapter hints |
| `docs/PROTOCOL.md` | The wire protocol; both sides implement it and test against its golden vectors |
| `docs/GAME_MODES.md` | The touch layouts and why they look the way they do |

## Quick start

1. On the PC, install [ViGEmBus 1.22.0](https://github.com/nefarius/ViGEmBus/releases).
2. Start the desktop app: `dotnet run --project server\src\MobileController.Desktop`. It runs the server for you.
   The **Dashboard**'s setup checklist covers the driver, the firewall rule (one click, administrator), the network
   and the first phone.
3. Install the app on the phone. Put the phone on the same Wi-Fi as the PC, or connect it over USB tethering
   (the fastest link). Open the app and tap **Find PC**. Alternatively, scan the code on the desktop app's
   **Connect a phone** page.

You can also run the server on its own, without a window:
`dotnet run --project server\src\MobileController.Server`. Use `-- --diagnose` to check the driver, the port, the
network adapters and the power settings. Use `-- --help` to list every option.

## The desktop app

It starts the server as a separate process, so the server's input loop never shares a garbage collector with a
user interface. The server is in a Windows job object, so it never outlives the app. Closing the window keeps the
phones working from the notification area.

- **Dashboard**: a health banner with the one thing worth knowing, the setup checklist, and the phones at a glance.
- **Controllers**: one card per phone with:
  - the live input as the game sees it;
  - latency, update rate, loss and jitter, with sparklines;
  - Identify (the phone buzzes and flashes);
  - a test rumble;
  - Disconnect;
  - Xbox 360 or DualShock 4 for that phone;
  - a name of your own for it.
- **Connect a phone**: a QR code and the PC's addresses (USB tethering first), refreshed as networks change.
- **Diagnostics**:
  - per-phone packet gaps (p50/p95/p99/max);
  - receive-loop and garbage-collector health;
  - a full system check;
  - a 10-second test controller;
  - a diagnostics zip for bug reports.
- **Log**: live, filterable. It is also written to `%LOCALAPPDATA%\MobileController\logs`.
- **Settings**: every server option in plain language, plus start with Windows, notifications and theme.

## Server settings

Options can be given on the command line (`--help` lists them all) or in a settings file at
`%APPDATA%\MobileController\server.json`. Options on the command line win over the file. Every key is
optional:

```jsonc
{
  "schema": 1,
  "port": 24680,
  "bind": "0.0.0.0",
  "maxPads": 4,
  "neutralTimeoutMs": 500,
  "disconnectTimeoutMs": 10000,
  "allow": ["192.168.1.50"],   // accept only these phones (empty or missing = any)
  "name": "Living room PC",     // what phones see when they search
  "gameDetect": true,           // tell phones the foreground game, for automatic modes
  "verbose": false,             // per-phone statistics every 5 s
  "highPriority": false,
  "logToFile": true,            // rotated at 2 MB, 4 old files kept
  "logFilePath": null           // default: %LOCALAPPDATA%\MobileController\logs\server.log
}
```

### DualShock 4

Each phone can appear as a DualShock 4 instead of an Xbox 360 controller, so games and Steam show PlayStation
button prompts. Games that only speak XInput don't see a DualShock 4.

You can set it in several places. When more than one applies, the first match wins:

1. Per phone, on the desktop's Controllers page (`devices` in the settings file).
2. On the phone, in its settings ("Controller on the PC").
3. For all phones, with `defaultOutput` or `--default-output ds4`.
4. Otherwise, Xbox 360.

The phone then labels its buttons ✕ ○ □ △.

## Phone settings worth knowing

- **Minimum press** (default 20 ms): a tap shorter than this is held this long, so a game that reads the
  controller once per frame still sees it, and a lost packet can't swallow it. A quick double tap still
  arrives as two taps.
- **Per-stick deadzone and anti-deadzone**: the anti-deadzone starts the output just past the game's own
  deadzone, so small thumb movements register immediately.
- **Gyro aiming**: smoothing calms slow, fine aim without delaying fast turns. Use **Calibrate gyro** if the
  aim drifts while the phone is still; the app also corrects drift by itself whenever the phone rests.
- **Haptic and rumble strength**: haptics work even with Android's system touch feedback switched off.
- **Finding the PC again**: the app remembers the PCs it connected to. If a PC's address changes (a new DHCP lease),
  the app searches for it by name. If its Wi-Fi changes, the PC keeps the same controller for it instead of
  plugging in a new one.

## Building and testing

```sh
# Android (any OS)
cd android && ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug

# Server (Windows, .NET 10 SDK)
dotnet test --solution server\MobileController.slnx
```

The server and the desktop app target `net10.0-windows`. On other operating systems:

- Compile both with `dotnet build server/MobileController.slnx -p:EnableWindowsTargeting=true`.
- `server/tests/MobileController.Control.Tests` runs anywhere.
- The server tests need Windows. The ViGEm integration tests also need the driver installed.
