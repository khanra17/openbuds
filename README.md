# OpenBuds

An unofficial, direct Bluetooth Classic earbud controller. The first implemented model is realme Buds Air7. No cloud account, phone bridge, telemetry or browser engine.

- **Android and desktop:** Kotlin Multiplatform + Compose, sharing screens and controller state.
- **Protocol:** one Rust implementation, shared by the app, CLI and versioned C SDK.
- **Desktop downloads:** self-contained with Java included, or a smaller external-Java variant for matching Java 21+ desktop runtimes.
- **Windows tray:** a small Rust host. The JVM starts only when you open the full UI and exits when you close it.
- **CLI:** named commands plus a persistent, versioned NDJSON interface for applications that do not want to link an SDK.

Only Air7 is implemented. Model drivers register their protocol factory, transport service and capability profile together; the applications and generic CLI discover that registry. Other models do not inherit Air7 opcodes, feature IDs, EQ limits or controls. See [adding a model](docs/sdk-models.md). Windows and Linux have native RFCOMM transports; Android uses its Bluetooth framework. macOS builds have no Bluetooth transport and cannot connect to Air7.

```text
apps/compose                 Shared Kotlin UI, Android app and desktop app
apps/tray                    Native Windows tray launcher (no JVM)
apps/launcher                Small external-Java desktop launcher
apps/cli                     clap CLI and persistent JSON session
crates/realme-buds            Device protocol, state, validation and sound guards
crates/realme-buds-windows    Windows SDP/RFCOMM transport
crates/realme-buds-linux      BlueZ client-profile transport
bindings/c                   Versioned C ABI, JSON dispatcher and thin JNI bridge
```

## Use

Pair in system Bluetooth settings and open OpenBuds. The app automatically tries the remembered or sole supported paired device; use Connect to choose among several. Android 12+ requires Nearby devices permission. Android keeps its connection in a user-visible connected-device foreground service while you use other apps. Removing OpenBuds from Recents closes the session and phone actions; it has no boot receiver or sticky restart. There is no scanning, location or internet permission.

Home has readback-driven noise controls and aligned earbud/case readings, with distinct docked states. Case battery can be relayed through an in-case earbud without a direct case connection. Tapping a wear-blocked noise choice opens an explanation, not a silent dead control. Cancellation restores the last observed ANC strength saved for that model/device; first use has a driver-declared fallback. Wind reduction appears under ANC and Transparency. The candidate Enhance voices control is explicitly experimental: its Air7 mapping is not confirmed. Reduce motion is persisted; placement/page transitions are finite, with no idle animation. Android has adaptive/themed icons and system SplashScreen integration. Unknown data remains unknown; no demo device or manufacturer artwork is bundled.

The equalizer separates read-only built-ins, saved profiles, local editing and the device-reported applied profile. Air7 editing uses six frequency handles at 62, 250, 1000, 4000, 8000 and 16000 Hz, with live frequency/gain feedback plus keyboard and accessibility actions—not six sliders. Edits stay local until explicit save; profile IDs and selection come from readback. Dynamic bands appear only while dynamic audio is enabled. Multipoint menus offer the appropriate Connect or Disconnect action. Gesture illustrations and graphical fit-test consent/progress/results explain their controls; unsupported Air7 Find earbuds is hidden.

Android's Gestures page also offers per-device, opt-in four-tap phone actions: ring the phone (Android 9+), flashlight, or a notification that opens OpenBuds. Ring/torch stop after 15 seconds; optional Camera/notification permissions and foreground-service types apply. No system volume is changed. Physical four-tap effects are not established by readback tests.

Single-tap is not offered: the user's Air7 stored that binding without performing the gesture. A persisted setting is not evidence of physical support. Reserved gestures and unidentified actions stay outside the normal UI.

## CLI

```sh
buds models
buds devices
buds --model realme.buds-air7 --address AA:BB:CC:DD:EE:FF status
buds noise anc --level moderate
buds eq create Warm 3 2 0 0 -1 -2
buds bind left double-tap play-pause
buds --json status
buds --json call '{"op":"dynamic_bands"}'
buds --json rpc
```

JSON requests use the same dispatcher as the SDK and app, including read-only diagnostic properties beyond the ordinary UI. See [CLI and wrapper protocol](docs/cli.md).

## Build

Install Rust 1.88+, a full JDK 21 (including `jpackage`), and Android SDK command-line tools. Desktop needs its native linker; Windows needs MSVC and a Windows SDK. Linux additionally needs `pkg-config` and `libdbus-1-dev`.

```sh
cargo build --workspace --release --locked
bash apps/compose/gradlew -p apps/compose :desktopApp:createReleaseDistributable
bash apps/compose/gradlew -p apps/compose :androidApp:assembleRelease
```

See [app build instructions](apps/compose/README.md) for SDK/NDK installation, development runs and packaging. GitHub Actions builds optimized desktop app images without publisher signing and a non-debuggable, R8/resource-shrunk Android release. Desktop artifacts are separately labelled `bundled-runtime` and `external-java` for Windows, Linux and macOS. The latter needs a complete, matching-architecture Java 21+ desktop runtime and never downloads Java. GUI apps, standalone CLI applications, and developer SDKs are separate downloads. All downloads include source/build information.

| Download | Contents |
| --- | --- |
| `OpenBuds-Android-release` | Directly accessible `OpenBuds-android-release.apk` (ARM64, ARMv7, x86_64), checksum and source/notices; no nested application ZIP |
| `OpenBuds-desktop-<platform>-bundled-runtime` | Optimized GUI with Java included; Windows also includes the native tray host |
| `OpenBuds-desktop-<platform>-external-java` | Same optimized GUI, requiring Java 21+ |
| `OpenBuds-CLI-<platform>` | Standalone `buds` executable and CLI documentation; no SDK libraries or headers |
| `OpenBuds-C-SDK-<platform>` | Native shared library, Windows import library, C header, documentation and C example; no CLI executable |
| `OpenBuds-Rust-SDK-source` | Matching Rust source workspace/lockfile for developing your own applications |

Platforms: Windows x64, Linux x64, macOS ARM64. macOS has no built-in Bluetooth transport. See [SDK integration](docs/sdk.md). Lint and UI tests still run, but diagnostic artifacts are uploaded only on failure, not alongside successful release downloads. Downloads are under the **Release builds and checks** workflow; **Check** is validation only.

**Android uses the Android Gradle plugin’s default development signing configuration, not a custom project key.** The APK is still an optimized, non-debuggable release build. Google supplies no universal production signing key; this signature is not publisher authentication or Play Store signing. Certificates can differ across build machines, so updating a differently signed installation requires reinstalling and loses local preferences. [Signing details](apps/compose/androidApp/signing/README.md).

## Safety and evidence

- Never play finding tones while either earbud is worn. Check physical removal; sensors can be stale.
- Sound cancellation and cleanup are bounded and best-effort, not a hardware watchdog.
- A timed-out write may have succeeded. Reconnect/read before retrying; setters are not replayed automatically.
- Acknowledgement, stored readback and physical behavior are different evidence. See [Air7 behavior and limitations](docs/air7.md).
- Status exports and opt-in wire logs can contain device addresses, host names and EQ names.
- Firmware writing and factory reset are not implemented.

## Check

```sh
cargo test --workspace --locked
cargo clippy --workspace --all-targets --all-features --locked -- -D warnings
cargo fmt --all -- --check
bash apps/compose/gradlew -p apps/compose :shared:desktopTest :androidApp:lintRelease
```

[Test strategy and manual checks](docs/testing.md).

## License

[GPL-3.0-or-later](LICENSE), including the C SDK. Dependencies retain their own [licenses](THIRD_PARTY_NOTICES.md). This is an unofficial project; product names identify compatibility. GPL grants no trademark rights or legal immunity.
