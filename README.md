# SWIS Controller

An Android app that makes a phone act as a wireless controller for a Nintendo Switch 2,
with a macro player and a ready-made macro for the infinite jump slash glitch in the
Ocarina of Time remake.

No root and no extra hardware: the phone talks to the console directly over Bluetooth.

## Status

| Part | State |
| --- | --- |
| Controller protocol, macro engine, touch handling, screens | Built and covered by 50 automated tests |
| Pairing with a real Switch 2 | **Not yet tried on hardware** — see "Will the console accept it?" |
| Infinite jump slash macro | Input order from published descriptions; timings are a starting point to tune |

## How it works

The Switch 2 accepts original Switch Pro Controllers over classic Bluetooth. Android (9 and
later) lets an app register as a Bluetooth HID device, so the app registers a HID gamepad
with the Pro Controller's report descriptor, renames the phone's Bluetooth to
`Pro Controller`, and answers the console's setup questions (device info, calibration,
player lights) the way a real controller does. After that it streams buttons and sticks.

The phone cannot speak the newer Switch 2 controller protocol (Joy-Con 2 / Pro Controller 2):
that one needs the Bluetooth link key to be set by the app, which Android does not allow
without root. A USB-C cable does not help either, because Android cannot present itself as a
USB gamepad without root.

## Will the console accept it?

This is the one open question, and only a real pairing attempt answers it.

Since system version 12, the *original* Switch only pairs with devices whose Bluetooth
"class of device" says gamepad. A phone reports "phone", and Android offers no way to change
that without root. Whether the Switch 2 applies the same check to original-style controllers
is not documented anywhere I could find.

Everything an app is allowed to set already matches a real Pro Controller: the Bluetooth
name, the HID service name, description and provider, the gamepad subclass and the report
descriptor. Three things are set by the phone's system and cannot be changed by an app: the
device class, the vendor/product ID record, and the maker prefix of the Bluetooth address.
Current Android has no setting, permission or shell command for the device class; it is
fixed by a system property only root can write.

The connection log on the main screen shows what happened (it also prints the phone's
device class when Android allows reading it):

- **`Connected to …` followed by `Console → 0x02 request device info`** — the console
  accepted the phone. Setup continues and the status turns green.
- **Nothing after `Phone is visible …`** — the console is ignoring the phone, which is what
  the device-class check looks like. In that case no app-only fix exists; the options are a
  rooted phone or a small ESP32-S3 board acting as a bridge.

## What was tried and what is left

First attempt (Galaxy S24 Ultra, Android 17, Switch 2): the phone registered, renamed itself
and became visible, and the console never connected. That is the device-class block.

Findings from Android's Bluetooth source and security policy:

- Android 14 and later take the device class from the system property
  `bluetooth.device.class_of_device` when Bluetooth starts. Only the system and root can
  write it; the `shell` user (ADB, Shizuku) cannot. Nothing else in the stack changes the
  major/minor class, and registering as a HID device does not touch it.
- Android 9 to 13 additionally read the global setting `bluetooth_class_of_device` every
  time Bluetooth turns on. ADB can write that setting without root:

  ```
  settings put global bluetooth_class_of_device 9480      # 0x002508, gamepad
  # turn Bluetooth off and on, then pair
  settings delete global bluetooth_class_of_device        # undo
  ```

  So an unrooted phone on Android 9 to 13 can announce itself as a gamepad. This app runs
  on Android 9 and later. Android 14 removed that code; whether a vendor build still honours
  the setting can only be found out by trying it.
- According to the JoyCon Droid documentation the console checks the device class only on
  first pairing. A console that already remembers the phone's Bluetooth address lets the
  unrooted phone connect. Getting it remembered needs one helper device once (a rooted
  phone, or a Linux computer with Bluetooth) set to the phone's address; they describe it
  as not guaranteed.

## Install

Download `app-debug.apk` (from the latest run of the Build workflow, or build it yourself),
open it on the phone and allow installation from that source.

Build it yourself:

```
./gradlew :app:assembleDebug        # APK in app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest    # tests
```

Needs JDK 17 or newer and the Android SDK (platform 35).

## Connect

1. On the phone, find Settings → About phone → Status information → Bluetooth address and
   type it into the app's address field, then Save. Android does not let apps read it, and
   the console asks the controller for it during setup.
2. On the Switch 2 open Controllers → Change Grip/Order and leave it open.
3. In the app tap **Start controller and pair** and allow the phone to be visible.
4. Accept the pairing request on the phone if one appears.
5. When the status turns green, tap **Open gamepad** and press L + R, then A.

Later, with the console awake, **Reconnect to a paired console** is enough.

The app renames the phone's Bluetooth to `Pro Controller` while it runs and restores the
old name when you tap **Stop controller**.

## Gamepad

Landscape, multi-touch, with both sticks, D-pad, A/B/X/Y, L/R/ZL/ZR, +/−, Home, Capture and
stick clicks. In the middle:

- **▶ macro name** — play or stop the selected macro
- **⋯** — choose which macro the play button runs
- **● REC** — record what you press; the recording is saved as a new macro
- **MENU** — back to the connection screen

Buttons held by a macro are drawn blue, buttons under your fingers red. While a macro runs
you can still press buttons, and moving the left stick takes steering over.

## Macros

Macros are plain text, edited in the app:

```
name = Example
loops = 0            # 0 repeats until stopped
scale = 1.0          # multiplies every duration
400 ZL LS(0,1)       # milliseconds, then everything held during that time
loop:                # steps above run once, steps below repeat
70  ZL LS(0,1) A
4f  ZL LS(0,1)       # "f" = frames at 60 fps
50  -                # nothing held
```

Buttons: `A B X Y L R ZL ZR PLUS MINUS HOME CAPTURE UP DOWN LEFT RIGHT LS RS`.
Sticks: `LS(x,y)` / `RS(x,y)` from -1 to 1 (+x right, +y up), or `LS_UP`, `LS_DOWN`,
`LS_LEFT`, `LS_RIGHT` and the same for `RS`.

Steps shorter than 20 ms are rejected: a Bluetooth controller reports about every 15 ms, so
a shorter press can fall between two reports and never reach the game.

### Infinite jump slash (Ocarina of Time remake)

The built-in macro follows the published descriptions of the glitch: lock on and hold
forward, jump attack with a two-handed weapon (ZL + forward + A), swap the item in mid-air
through the quick menu (R), attack (ZR), repeat. Set-up: Deku Stick in hand as Child Link or
Megaton Hammer as Adult Link, and a second item reachable from the quick menu.

Those descriptions give the order of inputs but not the frame timing, and not which input
picks the other item inside the quick menu. So treat the shipped timings as a starting
point:

- change `scale` to stretch or squeeze the whole loop,
- adjust one step at a time (one frame is about 17 ms),
- if the swap needs a direction, add it to the `R` step (for example `R RS_RIGHT`),
- or perform the trick once by hand with **● REC** running and loop the recording.

## Project layout

```
app/src/main/java/app/swis/controller/
  core/   Pro Controller protocol, report pacing, macro text/player/recorder (plain Kotlin)
  bt/     foreground service around Android's BluetoothHidDevice
  data/   settings and stored macros
  ui/     connection screen, gamepad, macro editor
app/src/test/   protocol bytes, pacing, macro timing, and Robolectric tests of the screens
```

Protocol bytes follow the open-source emulators NXBT and joycontrol and dekuNukem's
reverse-engineering notes. Not affiliated with or endorsed by Nintendo.
