# Douhua

[中文](README.md) | English

Douhua (豆花儿) turns your Android phone's screen into a trackpad and keyboard. Gestures follow the Mac trackpad, with haptic feedback, a typing panel and a numpad. Three- and four-finger gestures and the buttons on the trackpad's four corners are all configurable.

There are two ways to use it:

- **Desktop mode** controls the phone itself: the external display it is connected to (Samsung DeX, XREAL glasses, …), or the app on the other half of a split screen (for example Moonlight streaming).
- **Bluetooth mode** makes the phone a Bluetooth keyboard and mouse for a computer, tablet or TV. It works on any phone with Android 13 or later whose system supports it, and needs no service.

It started as a replacement for Samsung DeX's built-in "phone as touchpad": hold the phone in your left hand, a column of buttons on the left for your thumb, and the rest of the screen is the trackpad. Portrait and landscape are laid out automatically. The default theme is pure black with dim grey outlines, because the screen stays on and black saves power.

**In desktop mode, Douhua takes over the touchscreen while it is in the foreground** (as Samsung's own DeX touchpad does in full screen). System gestures such as pulling down the notification shade or swiping back from the edge stop working, and banner notifications can't be tapped. In split screen or a pop-up window this applies only to Douhua's own area: the other app, the divider, and system gestures that start from the screen edge still work. Ways to leave Douhua:

- **Exit** in the ⋯ menu at the top centre of the trackpad (the small top-left button is also Exit by default)
- Press the power button
- Opening Settings or the paste panel gives touch back to the system automatically
- If Douhua hangs for more than 3 seconds, the service gives touch back to the system on its own

## Two connection modes

Choose at the top of Settings (desktop mode is the default):

- **Desktop mode**: controls this phone and the screen it drives, either an external display (Samsung wired / wireless DeX, XREAL glasses, …) or the other half of the phone's own split screen (for example Moonlight streaming).
  It is not Samsung-only, but so far it has only been used on Samsung phones: the service finds the touchscreen through a coordinate transform matrix in the system's input information, and some phones don't expose one.
  It needs the Douhua service. After pairing once the phone can start it by itself (see "Without a computer" below); you can also start it from a computer with adb.
- **Bluetooth mode**: the phone acts as an ordinary Bluetooth keyboard + mouse for a computer, tablet or TV. No service needed. Allow the "Nearby devices" permission the first time.
  1. In Settings, set the connection mode to Bluetooth and tap "Pair new device". The system asks whether other devices may discover this phone; allow it.
  2. In the Bluetooth settings of the computer / tablet / TV, add a device, pick this phone, and confirm pairing on both sides. **Douhua must be open while pairing.**
  3. Once connected, the bottom of the trackpad shows "Bluetooth: device name". From then on, opening Douhua reconnects to the last device; pick another one under "Which device" in Settings.

  If the two devices were paired before (for example to send files), remove the pairing on both sides and pair again. Otherwise the other side won't recognise the phone as a keyboard and mouse.

  What differs in Bluetooth mode:
  - Gestures, the full-screen keyboard, haptics and buttons are the same. The other side sees an ordinary keyboard and mouse; zoom is Ctrl + scroll wheel, which browsers and most desktop software understand.
  - Back, Home, play/pause and volume go out as standard Bluetooth media keys. Recents, all apps and the notification shade have no universal key and are unavailable. The paste panel's "Send" is unavailable (the clipboard is on the phone); "Type out" works.
  - Touch is not taken over by the service: swiping in from the left or right screen edge does not trigger system Back (Douhua asks the system to stand aside), but swiping up from the bottom still goes home.
  - Rotation: works as usual when the service is running. Without the service Douhua asks the system to rotate; that works on most phones, but not on screens that ignore app orientation requests, such as Samsung's cover screen.

## Languages

Chinese, Japanese, Korean, English, French, German, Italian, Spanish and Arabic, following the system language; English is shown for any other system language.
You can also choose a language just for Douhua: system Settings → Apps → Douhua → Language. In Arabic the interface is laid out right to left (the keyboard and numpad keep the physical layout and are not mirrored).

The translations were written by AI and have not been checked by native speakers.

## Gestures

| Gesture | Action |
|---|---|
| One-finger move | Move the pointer |
| One-finger tap | Left click |
| Two-finger tap | Right click |
| Tap, then immediately press and move | Drag; lift to finish |
| Hold one finger still for a moment (one buzz) | Presses the left button; move to drag, lift to finish (duration adjustable) |
| Double tap | Double click |
| Two-finger swipe up / down / left / right | Scroll |
| One finger along the trackpad's left or right edge (the strip outside the faint line) | Vertical scroll |
| One finger along the trackpad's top or bottom edge | Horizontal scroll (corners count for both, depending on which way you move) |
| Two-finger spread / pinch | Zoom in / out (holds Ctrl and scrolls, which browsers and desktop apps understand; can be turned off) |
| Three / four fingers: tap, swipe up, down, left, right | Your choice in Settings (defaults below) |

A tap inside an edge strip is still a click. Each scroll step gives a "click" buzz like a scroll wheel (off / edge scrolling only / all scrolling; all scrolling by default).
The trackpad fills the whole screen up to its edges, so the edge strips sit right next to the phone's frame and are easy to find by feel.

Three-finger defaults: tap = Back, swipe up = Recents, swipe down = Home, swipe left / right = switch apps. Four-finger defaults: tap = app list, the rest unassigned.

Clicks, drag start and end, and three- / four-finger gestures each give a buzz. Strength is set in Settings (off / low / medium / high).

## Buttons on the main screen

By default there is only the trackpad and four small corner buttons: top left = Exit, top right = Rotate, bottom right = Keyboard, bottom left = Numpad. Each can be changed under Settings → Trackpad, or set to "None" to remove it.
A ⋯ sits at the top centre of the trackpad (no frame, in the accent colour, visible in every theme). It opens Keyboard / Settings / Rotate / Exit. The small trackpad on the keyboard screen has one too, with a few more items (see below).
Tapping outside the menu or pressing Back only closes the menu.

There are also **custom keys** (formerly the left button strip): turn them on under Settings → Buttons & keys (off by default) to get a column of buttons to the left of the trackpad. You can add or remove buttons (up to 12).
The defaults are Rotate, Keyboard, Numpad, Back, Copy, Paste, Undo, Left button. On a short screen (landscape) the column splits in two automatically.

- **Rotate**: switches between portrait and landscape; the whole screen really rotates (like the rotation suggestion button in the system navigation bar; it works with auto-rotate off).
  Which way landscape faces depends on how you are holding the phone at that moment. The chosen orientation is remembered and applied the next time Douhua opens; your original orientation lock is restored when you exit.
- **Keyboard**: switches to the keyboard screen (full screen, shared by the keyboard and a small trackpad). While Douhua is open the system has an extra "USB keyboard" (Douhua Keyboard),
  which it treats as a real keyboard: key repeat and shortcuts are native.
  - The letter area follows Gboard. Above it is a row with Esc Tab Shift Ctrl Alt Win Caps Fn Del, then the number row. Below is "?123 , Space . ↵",
    and at the very bottom a shortcut row: Copy Paste Cut Undo Select all ← ↑ ↓ →.
  - Ctrl / Alt / Win / Shift behave like a real keyboard: they are sent the moment you press, holding one while tapping another key makes a combination (hold Shift, tap a = A), and releasing releases.
    A lone tap is a lone tap (so "tap Shift to switch input language" on the host works; on Windows a lone Alt activates the menu bar and a lone Win opens Start, as with a real keyboard).
    To lock a modifier, hold it alone for 0.4–1 second and release (a buzz and a highlight at 0.4 s). It unlocks after the next key, or when you tap the locked key again. Holding longer than 1 second cancels the highlight and does not lock.
    If you pressed another key, clicked, scrolled or zoomed while holding it, it does not lock. While Shift is locked or held, key caps show capitals and the number row shows `!@#…`.
    Shift in the top row and ⇧ in the letter area are the same key, so it also works on the symbol and Fn pages (Shift + arrows, Shift + F keys).
  - Caps: a tap sends Caps Lock to the host once, highlights the key and capitalises the letter caps. Holding for 0.4 s only flips Douhua's own display without sending anything
    (Douhua can't see the host's Caps light; hold once to resync when the two disagree; the display resets to off when the host changes).
  - When you press a letter, number or symbol key, an enlarged copy pops up above it so you can see what you hit.
  - **⋯ menu**: the small trackpad has a ⋯ at its top centre (at least 20dp from the screen edge so your grip doesn't hit it). When the small trackpad is off, ⋯ is the rightmost key of the top row (an accent-coloured key cap).
    It opens "Hide keyboard / Resize / Layout / Pointer / Settings / Rotate / Exit". "Layout" cycles Auto → Split → Merged (the menu stays open so you can keep tapping),
    and "Pointer" cycles Trackpad → TrackPoint → Off. Tapping outside the menu or pressing Back only closes it. In a short window the menu splits into two columns.
  - **TrackPoint** (modelled on ThinkPad): push in a direction and the pointer moves that way, faster the further you push, and stops when you let go. Laid out like a ThinkPad keyboard:
    the red dot sits in the middle of the keyboard (where G, H, V and B meet), and the bottom of the keyboard gains left / middle / right buttons:
    hold left + push the dot = drag; hold middle + push = scroll, and releasing middle without scrolling = middle click.
    In TrackPoint state the bottom row is "?123 [, .] Space │ left middle right │ Space ↵": comma and period share one key (tap for comma, hold 0.4 s for period with a buzz), and there is a Space on the right-hand side too.
    Sensitivity is under Settings → Trackpad, where you can also add a row of left / middle / right buttons along the top or bottom edge of the trackpads.
  - All 9 positions of the bottom shortcut row can be changed in Settings (the same action list, so "Back", "Keyboard screen" and the like can go there too).
  - "?123" switches to the symbol page (``! @ # - _ = + [ ] { } \ | ; : ' " ~ < > ? /`` with one press each); "Fn" switches to F1–F12, Home / End and paging.
  - **Resize**: drag the keyboard's edges to resize and its middle to move; portrait and landscape are remembered separately. "Reset" returns to the default size.
    Dragging snaps lightly: a clearly vertical drag moves only vertically, a clearly horizontal one only horizontally; near the centre or a screen edge it snaps gently (with a buzz), and you can keep dragging past it.
    While resizing, "Done / Reset" appears at the top of the screen (at the bottom when the keyboard is moved to the top); tap "Done" when finished.
    The space left over above the keyboard is the small trackpad, so you can click and move the pointer mid-typing without hiding the keyboard.
  - **Split keyboard**: when the keyboard screen is wide and short (the lower half of a large split screen, or a phone in landscape; width more than twice the height), the keyboard splits into two halves
    with the small trackpad between them: left `q w e r t / a s d f g / ⇧ z x c v`, right `y u i o p / h j k l / b n m ⌫`, with a Space on each side.
    It merges back when the window narrows; "Layout" in the ⋯ menu can pin it to split or merged. Resizing a split keyboard adjusts both halves symmetrically: drag either half's inner edge to widen or narrow both (the trackpad in the middle follows),
    the outer edge to pull both inward, the top or bottom edge for height, the middle to move both up or down. Split and merged sizes are remembered separately, and "Reset" restores only the current one.
  - **Physical keyboard layouts**: besides the Douhua layout, the keyboard screen can show a simulated physical keyboard: 34 Latin-script layouts (US, UK, German, French, Brazilian ABNT2, Turkish, …), ANSI / ISO / ABNT2 shapes, six sizes (Apple compact, laptop, 65%, 75%, TKL, full size) and Windows / Mac modes, with an Fn layer and configurable second functions for F1–F12. Set it under Settings → Keyboard.
  - **Typing Chinese** relies on the receiving side's input method (as with a real keyboard: the phone's in desktop mode, the other device's in Bluetooth mode). Use an input method that supports hardware-keyboard pinyin, such as Gboard.
- **Numpad**: a numeric keypad, also sent through the virtual keyboard.
- **Paste panel** (not on any button by default; assign it in Settings): type a sentence with the phone's own input method (voice, Chinese) and press "Send" to paste it where you are typing.
  It works by copying to the clipboard and pressing Ctrl+V, so **it overwrites what was on the clipboard**. Bluetooth mode has no "Send" (the clipboard is on the phone and can't reach the other side). The trackpad pauses while the panel is open (the input method needs touch).
  The panel also has **Type out**: without the clipboard, it finds which key produces each character, using the language and mode of the physical keyboard under Settings → Keyboard, and types them one key at a time at a steady pace.
  It works for a BIOS, machines without clipboard sync, and Bluetooth mode. Speed is at the bottom of Settings → Keyboard, and the host must be set to the same layout. In Bluetooth mode the panel's ⌫ ↵ Tab are real keyboard keys too. Characters that can't be typed (Chinese, emoji) are skipped and counted.
  You can press "Stop" at any time; disconnecting, going to the background or closing the panel also stops it. It never resumes by itself and never adds an Enter you didn't write. In desktop mode it needs split screen (Douhua and the receiving app each on one half).
- **Left button**: holds the left mouse button for as long as you press, so moving with your other hand drags (like a laptop's physical button).

**Every configurable position shares one action list**: the trackpad's four corner buttons, custom keys,
three- / four-finger gestures, the keyboard's bottom row, and the volume keys. The list has Back, Home, Recents, switch apps, show desktop, screenshot, copy / paste / undo, browser back / forward,
open / close tab, arrow keys, media keys, keyboard screen, numpad, rotate, exit and more. The last entry, "Custom shortcut", lets you tick Ctrl / Shift / Alt / Super and pick a key.

Other things in Settings worth knowing about: 48 colour themes (some with material textures on the trackpad), a trackpad background image, a gamepad page (desktop mode; shows up as an Xbox-style controller through Moonlight), an air mouse (Bluetooth mode, gyroscope), inertial scrolling, and optional key sounds (off by default).

## Install and start

There are no prebuilt releases yet; build from source.

You need adb on a computer and a phone with wireless debugging (or USB debugging) on and connected. With several phones connected, the scripts list them for you to choose from. You can also pass `-s <serial>`, or set it first:

```bash
export ANDROID_SERIAL=<the phone's adb serial or IP:port>
```

First install (building needs the Android SDK under the project's `.sdk/`; see the error messages from `tools/build.sh`). There is no Gradle: the script calls javac, d8, aapt2, zipalign and apksigner directly.

```bash
tools/build.sh --install
```

This builds, installs, and starts the Douhua service on the phone. **The service is gone after the phone reboots**; run this again:

```bash
tools/start-server.sh
```

If the service is already running, the script checks it first: it restarts a stuck service (port not accepting connections) and does nothing if all is well. Add `--restart` to force a restart.

### Without a computer: the phone starts the service itself

Douhua can start the service through the phone's own "Wireless debugging", with no computer and no Shizuku. You only pair once:

1. Open Douhua → Settings. The "Douhua service" section at the top lists each condition, and for any that isn't met, a button below it jumps to the right system page:
   - **Connected to Wi-Fi** (wireless debugging can only be turned on over Wi-Fi; mobile data alone or your own hotspot won't do)
   - **Developer options**: About phone → Software information → tap "Build number" 7 times
   - **USB debugging**: turn it on in Developer options. The service depends on it: **turning off USB debugging or Developer options stops the service**. Samsung's "Auto Blocker" turns USB debugging off.
2. Tap "Start pairing". Douhua takes you to Developer options: turn on "Wireless debugging", open it and choose "Pair device with pairing code".
   The first time on a given Wi-Fi, the system asks "Allow wireless debugging on this network?" **Choose "Always allow" only at home or on a Wi-Fi you trust** (see below).
3. Pull down the notification shade and enter the 6-digit pairing code in Douhua's notification (the pairing dialog closes as soon as you leave it, so you enter the code in the notification without switching back).
4. After pairing, Douhua starts the service right away and turns on "Start service automatically": from then on the service is started whenever you open Douhua and it isn't running, and after a reboot once Wi-Fi connects.
   Douhua does not turn off wireless debugging that you turned on yourself for pairing (your computer may be connected through it); the notification reminds you to turn it off when not needed and has a button to go there.

Notes:
- On first start Douhua grants itself "modify secure settings", so later it can turn wireless debugging on by itself and off again as soon as the service is up (it leaves alone wireless debugging that you turned on).
- The first time wireless debugging is turned on over a given Wi-Fi, the system asks whether to allow it on that network. **Choose "Always allow" only at home or on a Wi-Fi you trust,
  not on public networks such as cafés, offices or hotels**: on a network you allowed, Douhua turns wireless debugging on for a few seconds each time it starts the service, and during those seconds devices on the same network can see that it is on.
  Connecting needs a paired key or a pairing code, but a vulnerability that bypassed this check was only fixed in May 2026 (CVE-2026-0073).
- If automatic start (on opening Douhua, after boot, after a Douhua update) runs into that system dialog and it isn't allowed within 3 minutes, automatic start pauses: Douhua stops turning wireless debugging on by itself, to avoid dialogs popping up everywhere
  (Douhua doesn't read the Wi-Fi name, so it can't tell networks apart). While paused, automatic start still works if wireless debugging happens to be on. Starting the service by hand once from Settings resumes it; the Settings page says so.
- When wireless debugging was turned on by you (not temporarily by Douhua), the "Wireless debugging" item in Settings reminds you to turn it off when no computer needs it, especially on public networks.
  Turning it off does not affect a service that is already running (as long as USB debugging stays on).
- A paired key that goes unused for 7 days is invalidated by the system. Settings then prompts you to pair again.
- Douhua's debugging key is stored encrypted on the phone and is only used to connect to this phone itself.

Then open Douhua on the phone. The first time, it asks for permission to send notifications. Once allowed, **every time you connect an external display (Samsung wired / wireless DeX, XREAL glasses, …)
a notification appears, and tapping it opens Douhua on the phone**. The notification goes away when the display is unplugged (like Samsung's own touchpad notification).
If Samsung's own notification feels redundant, turn it off in the system notification settings.

The line at the bottom of the trackpad is the status:

| Status | Meaning |
|---|---|
| XREAL One (or your display's name) | All good, touch taken over |
| No external display found… | Touch taken over, but no external display: keys go to the current focus (this is the normal state when using split screen on the phone itself) |
| Taking over the touchscreen… | Just opened; wait a moment |
| Pasting · trackpad paused | The paste panel is open |
| Douhua service isn't running: tap here to start it on the phone… | The service isn't up (usually after a reboot). Tap to go to Settings and start it; with "Start service automatically" on, Douhua tries by itself first |
| Douhua service isn't responding: tap here to restart… | The service is stuck. Tap to go to Settings and restart it, or run tools/start-server.sh on a computer (it detects this and restarts). In Bluetooth mode this follows the Bluetooth status |
| Starting Douhua service… | The phone is starting the service by itself |
| Douhua service is in use by another connection… | Another Douhua window or a test tool is connected to the service; Douhua connects once it disconnects |

Building a release package (to give to others):

```bash
tools/package.sh
```

It requires a clean commit (it builds only from the latest commit). The output is `dist/douhua-<version>.apk`, signed with the release key and not debuggable.
The release keystore goes in `.private/release.keystore` and its password in `.env` (template: `.env.example`). Neither is in git, so **back them up yourself**.
Without a commit you can try `tools/package.sh --snapshot`. Debug and release builds are signed differently; uninstall one before installing the other.

Other scripts:

| Command | What it does |
|---|---|
| `tools/start-server.sh --debug` | Starts the service and logs one line of mouse-packet statistics per second (for diagnosing stutter). The log is on the phone at `/data/local/tmp/douhua-server.log` (the previous run's is `.prev`) |
| `tools/start-server.sh --restart` | Forces a restart whether or not the service looks healthy |
| `tools/stop-server.sh` | Stops the service (releases the touchscreen, removes the virtual mouse); force-kills if it hasn't exited after about 3 seconds |
| `tools/selftest.sh` | Tests whether the app can reach the service without opening the UI (inaccurate with the screen off, when the system cuts background networking) |
| `tools/test.sh` | Offline tests (gesture recognition, touch routing, HID reports, multitouch decoding, touchscreen lookup, and more) |

## Notes

- With Samsung DeX, keep "Flow pointer to phone screen" in DeX settings **off**. When it is on, the virtual mouse switches between DeX and the phone screen, which has a known freeze-and-reboot problem (the scrcpy community has reported the same).
- While Douhua is open, the system shows a USB mouse named Douhua Touchpad and a USB keyboard named Douhua Keyboard. They disappear when Douhua closes.
- Why take over touch: when an accessibility service with the "perform gestures" permission (such as Tasker) is enabled, the system only accepts one input device at a time,
  so the finger and the virtual mouse interrupt each other and the pointer stutters. After the takeover the virtual mouse is the only device the system sees.
- Douhua requests the network permission only to reach the Douhua service on 127.0.0.1 and the phone's own wireless debugging (finding the port uses mDNS on the local network and only accepts this phone itself). It does not go online.
- The "Nearby devices" permission is only used in Bluetooth mode: connecting to paired devices, and making the phone discoverable when pairing a new one.

## Settings

| Setting | Default | Notes |
|---|---|---|
| Connection mode | Desktop | Desktop / Bluetooth, see "Two connection modes"; in Bluetooth mode you can also pick the device and pair a new one |
| Pointer speed | 2.0 | How far the pointer moves per 1dp of finger travel |
| Acceleration | 1.0 | Extra gain for fast swipes; 0 = none |
| Fine movement | Medium | Off / low / medium / high: the pointer slows down for slow movements, for precise positioning; the faster you move, the closer to normal speed, and quick swipes are unaffected |
| Tap then press and move = drag | On | With it off, clicks have no delay but tap-and-drag is unavailable |
| Drag wait time | 220 ms | How long to wait after a tap to see whether you drag; longer makes drags easier to trigger and clicks slower |
| Scroll speed | 5 | Levels 1–10 |
| Natural scrolling | On | Content follows your fingers (the Mac default) |
| Two-finger pinch = zoom | On | With it off, two fingers only scroll |
| Vertical scroll on left / right edges, horizontal on top / bottom | On / On | |
| Edge strip width | 28dp | 12–64dp |
| Scroll "click" haptic | All scrolling | Off / edge scrolling only / all scrolling |
| "Click" strength | Medium | Low / medium / high; it buzzes a few times when you choose, to try the feel. Adjusted separately from "Haptics" below, but silent when "Haptics" is off |
| Long press = press left button | On | How long counts as a long press: 450 ms by default. The finger must stay almost still; a small movement (such as nudging the pointer) doesn't count |
| Haptics | Medium | Off / low / medium / high |
| Continuous light buzz while dragging | Low | Off / low / medium / high: a steady light hum during a drag (tap-and-hold, long press, holding the "Left button"), stopping on release; buzzes 0.6 s when you choose |

## License

Douhua is released under the [MIT License](LICENSE).

It includes third-party material under its own terms: a Java port of BoringSSL's SPAKE2 (Apache-2.0), and key-cap data generated from Unicode CLDR, AOSP key character maps and xkeyboard-config. The full notices are in `res/raw/licenses.txt` and in the app under Settings → General → Open-source licenses.
