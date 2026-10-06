# Adaptune

Unofficial settings app for the Carsifi wireless Android Auto adapter.

The official Carsifi app crashes on launch on current Android (Android 17, and Android 14 once recent Google Play system updates are installed). An old gRPC library bundled in the app calls hidden TLS methods that Android's Conscrypt module no longer allows, so the app dies before you ever get to the adapter. The adapter itself still works fine for wireless Android Auto, you just lose the ability to change its settings. Adaptune gives that back.

It talks to the adapter directly over Bluetooth using the same JSON commands the official app uses (documented in [docs/PROTOCOL.md](docs/PROTOCOL.md)). Nothing goes through Carsifi's servers.

Not affiliated with or endorsed by Carsifi. "Carsifi" is a registered trademark of its owner and is only used here to say which hardware this works with. Use at your own risk, it's changing settings on hardware you own.

## What it can do

- Read everything the adapter reports (firmware version, Wi-Fi gateway, paired phones, all ~97 settings flags)
- Change the settings the official app exposes, with readable names (magic button clicks, start/stop behavior, Wi-Fi band and channel, intercept AA, screen DPI, USB mode, debug mode)
- Change every other flag the adapter has under **Advanced settings** (collapsed by default, listed by raw key, the official app hides these)
- Switch the Wi-Fi gateway between the adapter's own hotspot, a phone hotspot, or another Wi-Fi network
- Remove a paired phone
- Reset settings to defaults
- Save the adapter's logs to Downloads (nothing is uploaded) and clear them on the adapter

## Requirements

- Android 13 or newer
- The adapter already paired in Android's Bluetooth settings (it shows up as `Carsifi-xxxxxx`)

## Install

1. Download `adaptune-<version>.apk` from [Releases](../../releases) and open it on the phone (allow installs from your browser or file manager when Android asks).
2. Open **Adaptune** and allow the Nearby devices permission. If the adapter isn't powered or in range yet, the app keeps retrying every 10 s while it's open (or tap **Retry now**), no relaunch needed.

To check a download before installing, compare it against the release's `SHA256SUMS`, or verify the build provenance with `gh attestation verify adaptune-<version>.apk --repo <owner>/adaptune` (releases built while the repo was public). Release APKs are signed with this certificate (Android refuses updates signed with anything else):

```
SHA-256: 53:C9:1E:E3:29:7A:29:75:8C:60:DD:61:48:E4:25:9F:2C:55:CD:D3:D3:E2:42:64:FE:6E:62:61:DC:A7:0A:82
```

`apksigner verify --print-certs adaptune-<version>.apk` shows the certificate of a downloaded APK.

## Build it yourself

Needs JDK 17 and the Android SDK with `platforms;android-37.0` and `build-tools;37.0.0`. There's no Gradle project, it builds with the plain SDK tools since it's a single Activity with no dependencies.

1. `sdkmanager "platforms;android-37.0" "build-tools;37.0.0"`
2. `app/build.sh` (uses `$ANDROID_HOME`, falls back to `~/Library/Android/sdk`)
3. `adb install -r app/build/adaptune-0.0.0-dev.apk`

Local builds are signed with `app/debug.keystore` (created on first build, not committed). Release APKs are built and signed by GitHub Actions when a version tag is pushed. A debug build can't install over a release build or the other way around, since the signing keys differ: uninstall first.

## Things to know

- **The adapter is slow to answer some changes.** Saves and phone removals can take up to a minute to get acknowledged (the app waits up to 90 s). Some changes reboot the adapter, debug mode for sure. If the adapter is mid-restart the app shows "Adapter busy, retrying…" and keeps trying for 30 s.
- **Debug mode only shows up in the adapter's settings once it's been set**, so until then the switch shows whatever this app last set it to (off by default).
- **Phone hotspot is untested.** The official app hangs on its own compatibility check before sending it, so the value came from the app binary instead of a capture. If the adapter can't reach the hotspot, Bluetooth still works, switch the gateway back to Carsifi Wi-Fi.
- **The adapter's log only covers the current boot.** Any reboot (including toggling debug mode) starts it over. The adapter hands it over ~3 MB at a time; Save logs fetches every chunk into one file, trimming each chunk off the adapter as it goes. A long session also has an older rotated file (`session_dmesg_backup0.log`, the start of the boot) saved alongside. Clear logs erases what's left of the current log.
- **Phones can't be reordered.** The firmware doesn't support it (the official app doesn't offer it either), removal is all there is.
- **Reset to defaults only resets the settings flags**, not the Wi-Fi gateway.
- **On the bench (no head unit attached)** the adapter drops and restarts the Android Auto session about every 2 minutes since nothing answers on its USB side. That's normal and stops once it's plugged into the car.

Tested on Android 17 with adapter firmware `1.10.0`.

## Protocol notes

[docs/PROTOCOL.md](docs/PROTOCOL.md) has the commands, the settings key mapping, and how it was captured. [docs/rfcomm_dump.py](docs/rfcomm_dump.py) pulls the RFCOMM payloads out of an Android Bluetooth HCI snoop log if you want to capture something new (`python3 docs/rfcomm_dump.py --json btsnoop_hci.log` prints just the adapter commands and replies, with Wi-Fi passwords redacted).

Open items are in [app/BACKLOG.md](app/BACKLOG.md).
