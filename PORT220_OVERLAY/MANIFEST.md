# Port220 overlay — where each file goes

Copy these over a **fresh recursive clone** of
`https://github.com/CrownParkComputing/Dosbox-X-Android.git`, preserving the
paths shown. Paths here are relative to the repo root.

Four of these are new files; three are modifications to upstream files. For
the modifications I'm giving you the complete file, not a diff — overwrite
the upstream copy.

| File | New or modified | What it does |
|---|---|---|
| `android/settings.gradle.kts` | **Modified** | Adds the jitpack repo, where `usb-serial-for-android` is published. One-line change to `dependencyResolutionManagement`. |
| `android/app/build.gradle.kts` | **Modified** | Adds the `com.github.mik3y:usb-serial-for-android:3.9.0` dependency. Nothing else touched. |
| `android/app/src/main/AndroidManifest.xml` | **Modified** | Adds USB host feature + foreground service permissions, registers `Port220UsbBridgeService`, registers `Port220LatencyTestActivity` with a USB-attach intent filter. |
| `android/app/src/main/res/xml/port220_device_filter.xml` | **New** | Matches the FT232R by VID/PID (0x0403/0x6001, written in decimal as Android requires) so the tablet recognizes the Service Module. |
| `android/app/src/main/java/.../Port220UsbBridgeService.kt` | **New** | The USB↔TCP nullmodem bridge — Android's equivalent of the companion process in the Mac/Windows builds. Forwards bytes between the FT232R and a loopback TCP socket. |
| `android/app/src/main/java/.../Port220LatencyTestActivity.kt` | **New** | The isolated latency test. Also shipped standalone as `Port220LatencyTest.zip`, which is what you should use for the first test. |
| `port220/dosbox.conf.template` | **New** | The `[serial]` nullmodem section for the EMSAN1 game folder. Rename to `dosbox.conf` and place beside `EMSAN1.EXE`. |
| `.github/workflows/port220-core.yml` | **New** | Builds the native core on a GitHub Ubuntu runner and uploads the four artifacts Gradle needs. Lets you build the APK on Windows without any local Linux toolchain. See Track C. |
| `android/app/src/main/java/.../Port220ControlActivity.kt` | **New** | Start/stop the bridge and watch live status. Exists because adb can't start a non-exported service, and Android 12+ forbids background foreground-service starts — an activity solves both. |
| `frontend/retrodos_frontend.cpp` | **Modified** | Adds `[serial]` to the allowlist of sections copied verbatim from a game's own `dosbox.conf`. **Requires a core rebuild** (re-run the CI workflow). |

## Why the C++ change is necessary

My earlier claim that the app honors a game's `dosbox.conf` verbatim was wrong.
It reads exactly two things from it: the `[autoexec]` block, and the audio
sections (`[sblaster]`, `[mixer]`, `[midi]`, `[speaker]`, `[gus]`). Everything
else in the generated conf comes from the frontend.

`build_conf` writes no `[serial]` section at all, so DOSBox-X falls back to its
own default — a dummy port. The nullmodem line was being silently discarded,
and no amount of Kotlin-side work would have made the bridge connect.

## Upstream files NOT touched

No changes were needed to the native frontend (`frontend/*.cpp`), the core
submodule, or `build-core.sh`. The app already honors a game folder's own
`dosbox.conf` verbatim, which is the entire DOS-side integration point — so
the nullmodem config goes in as data, not as a code change.

## Settings still unverified against hardware

- Baud is set to **10400** primary, **9600** fallback, 8N1 — per your
  confirmation. Framing (8N1) is assumed to hold for 10400 as well as 9600;
  correct `DATA_BITS`/`STOP_BITS`/`PARITY` in `Port220UsbBridgeService.kt` if
  that's wrong.
- The nullmodem TCP port is **6403**, set in two places that must agree:
  `NULLMODEM_PORT` in the bridge service, and `port=` in `dosbox.conf`.
- Any timing-sensitive `[dosbox]`/`[cpu]` settings from your working
  Mac/Windows conf are **not** carried over — I don't have that file. Copy
  them into the template before a real ECU session.
