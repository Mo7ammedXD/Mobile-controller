# Game modes: touch layouts designed for how games are played

A phone is not a controller, and copying the controller's layout onto glass makes many games harder
than they need to be. This document explains the problem, the rules the game modes follow, and which
mode to use for which game. Pick a mode in the app under **⚙ → Game mode**. Each mode remembers its
own layout edits.

## 1. Why the controller layout fails on a phone

| | Real controller | Phone in landscape |
|---|---|---|
| Fingers on inputs | 4 (two thumbs and two index fingers) | 2 (thumbs only) |
| LB / RB / LT / RT | pressed by the index fingers, *at the same time* as the thumbs | a thumb has to leave the stick or the buttons to reach them |
| Holding a button | free: the index finger rests on the trigger | costs a whole thumb for as long as it is held |
| Finding a button | by feel | by sight, because glass has no edges |

So on touch, **every action a game wants held (throttle, sprint, aim) and every shoulder or trigger
action competes with the stick and the face buttons for the same two thumbs.** The top corners of the
screen, where a controller's shoulder buttons would be, are the least reachable places of all.

The green bands in the pictures below are the comfortable thumb zones: the arc each thumb sweeps while
the hand still grips the phone. Anything outside them means stretching, and stretching means letting go
of something.

## 2. The rules every game mode follows

1. **The most-used actions are the largest controls, inside the thumb arcs.** Rarely used ones are
   smaller and further away. Menus go to the top centre.
2. **Anything a game wants HELD must not occupy a thumb.** Held actions become:
   - **a toggle** (AUTO GAS in Rocket League: tap once and the car drives; REV overrides it while held),
   - or **the stick's edge ring**: push the thumb past the dashed ring around a stick to sprint, the way
     you push a real stick into its rim.
3. **Two-finger chords become one-touch combos** (FINESSE = RB+X, CHIP = LB+X, J+B = jump + boost).
4. **The thumb can roll.** Related buttons form a group, and sliding the thumb from one to the next
   presses the next one without lifting. A combo between two buttons means the shared button never
   drops: BOOST → J+B → JUMP keeps boost held the whole way.
5. **Buttons never sit inside a stick's touch zone.** A thumb reaching for the stick must always grab the
   stick (this is enforced by a unit test for every mode).
6. **Labels say what the button does in the game** ("BOOST"). The small text says which Xbox input is
   sent ("B"), so the layout stays readable if you changed your in-game bindings.

All labels assume each game's **default** controller bindings.

## 3. Which mode for which game

| How the game plays | Mode | Examples |
|---|---|---|
| Steer + 3 big actions, throttle always on | **Car soccer** | Rocket League |
| Run + pass/shoot, sprint held, modifier combos | **Football** | EA SPORTS FC, FIFA |
| Steer + analog gas/brake | **Racing** | Forza Horizon/Motorsport, F1, Need for Speed, The Crew, Dirt |
| Move + aim + fire at the same time | **Shooters** | Call of Duty, Fortnite, Apex, Battlefield, Halo, Destiny, third-person action |
| Precise directions + many buttons pressed together | **Fighting & platformers** | Street Fighter, Tekken, Mortal Kombat, Hollow Knight, Cuphead, Celeste, emulators |
| Anything else, menus, games that show Xbox prompts | **Classic controller** | everything |

If your game isn't listed, choose the mode whose *gameplay* is closest. Then use **⚙ → Edit layout** to
drag and resize controls; the edit is saved for that mode only.

## 4. The modes

### Car soccer (Rocket League)

![Rocket League mode](img/modes/rocket.png)

| On screen | Sends | Rocket League action |
|---|---|---|
| STEER (left stick) | LS | steer / air pitch and yaw |
| BOOST | B | boost |
| JUMP | A | jump, dodge |
| J+B | A + B | jump and boost together |
| SLIDE | X | powerslide / air roll |
| BALL CAM | Y | ball cam |
| REV | LT | reverse / brake |
| AUTO GAS (toggle) | RT held | throttle |
| LOOK (small stick) | RS | look around |
| D-pad | D-pad | quick chat |
| SCORE / MENU | Back / Start | scoreboard / pause |

**Why it looks like this.** In Rocket League you hold throttle for almost the whole match. On a
controller that costs nothing (an index finger on RT). On a phone it would lock up your right thumb, so
throttle is a toggle. Tap **AUTO GAS** at kick-off and the car drives itself forward. **REV** overrides
it while held, and tapping AUTO GAS again stops the car. The right thumb is then free for the actions
that decide plays. BOOST and JUMP are the two biggest controls in the arc. For aerials, rest the thumb on
BOOST and roll it over **J+B** onto JUMP (or straight onto J+B): boost is never released on the way.

Toggles switch off automatically whenever the app pauses or the settings open, so the car can never keep
driving on its own.

### Football (EA SPORTS FC / FIFA)

![Football mode](img/modes/football.png)

| On screen | Sends | Attacking | Defending |
|---|---|---|---|
| MOVE (left stick) | LS | move / dribble | move |
| SPRINT ring on MOVE | RT | sprint (push past the ring) | sprint |
| PASS | A | short pass | contain |
| SHOOT | X | shoot | slide tackle |
| THROUGH | Y | through ball | rush keeper |
| LOB | B | lob / cross | tackle |
| FINESSE | RB + X | finesse shot | n/a |
| CHIP | LB + X | chip shot | n/a |
| SWITCH | LB | n/a | change player |
| JOCKEY | LT | n/a | jockey |
| SPRINT | RT | sprint (button) | sprint |
| SKILL (small stick) | RS | skill moves | n/a |
| D-pad | D-pad | tactics | tactics |

**Why it looks like this.** You sprint most of the time you move, but holding RT would steal the thumb
you pass and shoot with. So sprint is the **edge ring**: steer normally inside the ring, and push the
thumb out past it to sprint. You get a haptic tick when sprint starts. PASS is the largest control and
sits right where the thumb rests, because it is used far more than anything else. SHOOT is towards the
corner, so passing and shooting never get confused. Finesse and chip shots normally need two fingers;
here they are one button each.

### Racing

![Racing mode](img/modes/racing.png)

| On screen | Sends |
|---|---|
| STEER (left stick) | LS (only the X axis matters) |
| GAS pedal | RT, **analog** |
| BRAKE pedal | LT, **analog** |
| E-BRAKE | A (handbrake in Forza and most arcade racers) |
| X / Y / B, LB / RB | as labelled (gears, rewind, camera… depending on the game) |
| LOOK | RS |

**Why it looks like this.** Racing is the one genre where trigger *pressure* matters, so the pedals are
analog. The higher your thumb is on a pedal, the harder it presses: the bottom edge gives about 30 %, and
from a bit above the middle up it gives 100 %. Slide the thumb to feather the throttle out of a corner.
The pedals sit side by side under the right thumb, which rocks between them the way a foot does.

### Shooters

![Shooter mode](img/modes/shooter.png)

| On screen | Sends | Typical action |
|---|---|---|
| MOVE (left stick) | LS | move |
| SPRINT ring on MOVE | L3 | sprint |
| AIM (right stick) | RS | aim / look |
| FIRE (right) and FIRE (left) | RT | fire |
| AIM | LT | aim down sights |
| RELOAD | X | reload / interact |
| MELEE | R3 | melee |
| JUMP / CROUCH | A / B | jump / crouch, slide |
| SWAP | Y | switch weapon |
| ABILITY / GRENADE | LB / RB | tactical / lethal, abilities |

**Why it looks like this.** In a shooter the right thumb aims, and you have to fire *while* aiming. A
right-hand fire button alone means you stop aiming to shoot. So FIRE exists twice: next to the aim zone,
and above the move stick for the **left thumb**, the trick mobile shooters use. Hold the aim with the
right thumb and fire with the left. Sprint is the move stick's edge ring (L3 in most shooters). The
buttons between the aim zone and FIRE form a column (reload, melee, jump) the right thumb reaches without
leaving the aim area for long.

### Fighting & platformers

![Fighting and platformers mode](img/modes/arcade.png)

| Grid | Sends | Street Fighter (classic) | Tekken | Mortal Kombat |
|---|---|---|---|---|
| top row | X · Y · RB | LP · MP · HP | 1 · 2 · n/a | front punch · back punch · n/a |
| bottom row | A · B · RT | LK · MK · HK | 3 · 4 · n/a | front kick · back kick · n/a |
| left of grid | LB · LT | 3 punches · 3 kicks | n/a | n/a |

**Why it looks like this.** Fighting games need exact directions (a D-pad beats a floating stick for
quarter-circles and charge inputs) and pressing several buttons together. The 2 × 3 grid is the arcade
stick layout these games were designed around. Two fingers on the grid give throws and supers, and a
finger slides across a row for rolling inputs. Platformers use the same D-pad and big buttons.

### Classic controller

![Classic mode](img/modes/classic.png)

Every Xbox input in its familiar place. Use it for menus, launchers, and games where the on-screen
prompts show Xbox buttons and nothing else fits.

## 5. Feel and comfort features

All of these are in **⚙ Settings**. The *Feel in this mode* settings are stored per game mode, so
Shooters can have a precise aim curve while Racing stays linear.

### Automatic mode switching

The PC server watches which program is in the foreground and tells the phone (twice a second it
checks; it only sends a name when it changes, and repeats it every 2 s). When it is a game the app knows,
the phone switches to that game's mode and shows a short message. It never switches while you edit a
layout or have the settings open, and if you pick another mode by hand while the same game is running,
your choice stays. Turn it off with **Switch mode for the game on the PC**, or for privacy start the
server with `--no-game-detect` (then the PC never reads the foreground window).

| Mode | Recognised games (executable) |
|---|---|
| Car soccer | `RocketLeague.exe` |
| Football | `FC26.exe`, `FC25.exe`, `FC24.exe`, `FIFA23.exe`, `FIFA22.exe`, `FIFA21.exe` |
| Racing | Forza Horizon 4/5, Forza Motorsport, F1 23/24/25, NFS Unbound/Heat, The Crew Motorfest, DiRT Rally 2, EA WRC |
| Shooters | Call of Duty (`cod.exe`), Fortnite, Apex Legends, Battlefield 2042, Halo Infinite, Destiny 2, Overwatch |
| Fighting & platformers | Street Fighter 6, Tekken 8, Mortal Kombat 1, Guilty Gear Strive, Hollow Knight, Cuphead, Celeste, RetroArch |

Executable names are best effort; a game the app doesn't recognise simply leaves the mode alone. Store
(UWP) apps all report the same frame host, so they are never matched.

### Gyro aiming (Shooters, Classic) and tilt steering (Racing)

- **Gyro aiming:** turn the phone to aim, like Switch or Steam Deck gyro. Turning is measured
  around gravity, so it works whether you hold the phone upright or tilted back. By default it is
  active **only while you hold AIM (LT)**: aim down sights, then fine-tune with your wrists. The touch
  aim stick still works at the same time; the two add up. Options: always on, invert up/down,
  sensitivity.
- **Tilt steering:** turn the phone like a steering wheel. About 35° is full lock (at 100 %
  sensitivity), with a small centre deadzone so the car goes straight when you hold the phone level.
  Off by default (it would steer for anyone holding the phone at an angle); switch it on in Racing.

### Stick feel

| Setting | What it does | When to raise it |
|---|---|---|
| **Anti-deadzone** | The stick's output starts just past the game's own deadzone (XInput games ignore roughly the first quarter of stick travel). | The character or camera reacts late to small thumb movements. |
| **Aim precision** | Bends the right stick's response: small movements stay small, full travel is still full speed. | Aiming feels twitchy. |
| **Stick sensitivity** | Full output after less thumb travel (e.g. 150 % = at two-thirds of the ring). | You run out of thumb room. |

### Playing without looking

- **Haptic landmarks:** a tick when the thumb lands on or rolls onto a button, a light "clock" tick
  when a stick reaches full output, a firm pulse when you push past the sprint ring or switch a toggle
  on, and a double buzz when a finger lands on empty glass (or a toggle switches off). You feel what you
  touched.
- **Buttons follow your thumb:** when your thumb lands on (or close to) a button cluster, the whole
  cluster moves slightly so that button is exactly under your thumb. The neighbouring buttons keep their
  distances from where your thumb really is, so rolling to the next button works even if your hand has
  drifted. It moves at most a short way, never while a finger holds a button in that cluster, never
  steals a touch from a stick, and returns home on pause.

### Comfort

- **Dim the screen while playing:** 4 seconds after connecting, the screen goes very dark (you're
  watching the PC). Less battery and heat, so the phone doesn't slow down. Settings, the layout editor
  and pausing bring the brightness back.
- **Player colours:** each phone gets a coloured frame for its player number (P1 green, P2 blue, P3
  orange, P4 purple) and a big "P1"–"P4" for a moment when the PC assigns it. With four phones on one
  sofa, everyone knows which car is theirs.
- **Left-handed:** mirrors every layout left ↔ right, including your custom ones.
- **Layout editor:** shows the comfortable thumb zones in green, snaps controls to a grid (toggle
  **Snap**), and has **Undo**.

## 6. In-game settings that suit touch

- **Raise stick sensitivity** a little in shooters and in Rocket League. Touch sticks reach full
  deflection with a short thumb movement, so the game's response curve feels slow at default.
- **Lower the game's stick deadzone** where the game allows it (e.g. Rocket League "Controller
  Deadzone"). The app already applies a small deadzone (⚙ → Stick deadzone, 5 % by default), so the
  game's large default deadzone only adds dead travel. Where the game has no such setting, raise the
  app's **Anti-deadzone** instead.
- **Keep the game's assists on.** Aim assist in shooters, assisted passing and shooting in football,
  braking and steering assists in racing all exist for controllers, and the PC sees this phone as an Xbox
  controller.
- **Rumble:** leave the game's vibration on; the phone vibrates with it (⚙ → Game rumble).

## 7. Making your own

Every mode is a starting point: **⚙ → Edit layout** lets you drag any control, pinch or use − / + to
resize it, then Save. Reset returns that mode to its defaults. To change what a mode *contains* (new
combos, other games' bindings), edit `GameModes` in
`android/app/src/main/kotlin/com/mobilecontroller/client/ui/GameMode.kt`. Each control is one line
saying what it sends and where it sits. To check that your layout fits every screen shape, run the unit
tests. To see it, render it:

```powershell
$env:LAYOUT_PREVIEW_DIR = "$PWD\previews"
.\gradlew.bat testDebugUnitTest --tests '*LayoutPreviewTest' --rerun
```
