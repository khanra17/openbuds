# Compose application

One shared Kotlin module contains the UI, navigation state, EQ math and controller. Thin Android/desktop entry points supply platform I/O and lifecycle integration. The JNI bridge in `bindings/c` uses the same Rust session/JSON dispatcher as the C SDK and CLI. There is no second Bluetooth protocol implementation, DI framework, database or network client. Android has an explicit connected-device foreground lifetime.

## Source layout

- `shared/.../core/DeviceController.kt`: serialized SDK requests, lifecycle, events and readback.
- `core/DeviceSnapshot.kt`: observed values and wear prerequisites; unknown stays unknown.
- `core/DeviceFailure.kt`: existing SDK error fields translated into user recovery, with opt-in details.
- `core/DeviceProfile.kt`: SDK-supplied capabilities, constraints and model-aware presentation.
- `core/DeviceResult.kt` and `BoundedAction.kt`: readable device results and bounded local actions.
- `ui/App.kt`: app frame, navigation and shared confirmations.
- `ui/screens/`: Home, noise controls, Sound & EQ, Gestures and Device.
- `ui/components/` and `ui/theme/`: reusable accessible controls, aligned readings and common styling.

Test-only link fixtures live in test source sets; production contains no sample-device state or demo entry path.

## Toolchain

Versions are pinned, not dynamic: Kotlin 2.4.21, Compose Multiplatform 1.12.2, independently versioned Material 3 1.9.0, coroutines 1.11.0, serialization 1.12.0, AGP 9.4.1 and Gradle 9.8.2. These were selected from published stable artifact metadata; prereleases are not used. Android uses the current Android-KMP library plugin and separate application module.

Use a full JDK 21. Android Studio's bundled runtime may lack `jpackage`; use a full SDK/JDK for desktop packaging. If your compilation JDK differs from the packaging JDK, pass `-PpackageJavaHome=/absolute/path/to/full/jdk`.

## Desktop

Rust's native linker must be available. Windows requires MSVC tools/Windows SDK. Linux requires `pkg-config libdbus-1-dev` for Bluetooth and platform graphics/font libraries for the UI. Builds on Ubuntu 24.04 target that glibc baseline, not every older distribution.

```sh
# From the repository root
bash apps/compose/gradlew -p apps/compose :desktopApp:run
bash apps/compose/gradlew -p apps/compose :desktopApp:createReleaseDistributable
cargo build -p buds-cli -p openbuds-tray -p openbuds-launcher --release --locked
```

The optimized app image is under `desktopApp/build/compose/binaries/main-release/app/` (`OpenBuds.app` on macOS). ProGuard shrinks/optimizes JVM bytecode; Rust uses opt-level 3, thin LTO, one codegen unit and stripped debug information. The native bridge's JNI names are kept. No publisher signing/notarization is configured. Windows installers additionally need WiX; portable app images do not require a privately managed signing identity.

### Two desktop downloads

GitHub Actions → **Release builds and checks** runs on push, pull requests and manual dispatch. Each desktop runner builds the optimized application once and publishes two separately named artifacts:

| Variant | Included Java | Requirements |
| --- | --- | --- |
| `bundled-runtime` | Reduced Java runtime included | No Java installation needed. |
| `external-java` | None | A complete desktop Java **21+ JRE or JDK**, matching the download's CPU architecture. Headless/server-only runtimes are insufficient. |

The external variant uses the exact same optimized JARs in the same classpath order, plus a small Rust launcher. It checks version/architecture, uses `JAVA_HOME` first and otherwise Java on PATH, and never downloads Java or changes the user's environment. Windows uses `javaw` without a flashing console and reports setup errors in a native dialog. Linux/macOS launch from `OpenBuds/bin/OpenBuds`; the bundled macOS download remains an `.app`. Both Windows variants work with the same small tray host. A Java JDK is needed to **build**, not to run the external variant.

To stage a smaller Windows variant locally after building the release image:

```sh
python tools/desktop_variants.py --platform windows-x64 \\
  --image apps/compose/desktopApp/build/compose/binaries/main-release/app/OpenBuds \\
  --launcher target/release/openbuds-launcher.exe --output target/external-java/OpenBuds
# Compatibility check only; no UI or Bluetooth connection:
target/external-java/OpenBuds/OpenBuds.exe --launcher-check
```

Use a new/empty output path. To create final archives with source/notices, use `tools/package_release.py --runtime bundled` or `--runtime external-java --launcher ...` from a clean committed checkout. Do not make a runtime-less copy by deleting `runtime/` from a jpackage image: that native launcher still expects its own runtime.

For the Windows tray, put `OpenBudsTray.exe` next to the packaged `OpenBuds/` app directory. It has no JVM, Bluetooth connection, periodic timer or hidden Compose window. Clicking opens the full app; closing that app exits its JVM. The host remains in the Windows notification area until Quit tray. Opening the full UI also creates the normal Windows taskbar window. The Windows title bar uses a muted dark native frame rather than replacement decorations, preserving Snap, resizing and the system menu. The tray supports keyboard activation and re-registers its icon if Explorer recreates the notification area. Startup registration is not silently installed.

Desktop shortcuts: Ctrl+1…4 select Home / Sound & EQ / Gestures / Device; Alt+Left returns Home; F5 refreshes while connected and idle. Escape belongs to dialogs and menus. Text-editing shortcuts are not replaced.

macOS builds have no Bluetooth transport and cannot connect to Air7. Linux transport still needs real-host validation.

## Android

Minimum API 24 (Android 7), including the requested Android 10 baseline. Android uses the AndroidX SplashScreen API (compat library 1.2.0), with no splash Activity or artificial startup delay. Launcher icons have separate adaptive foreground/background layers, API 33 monochrome artwork and an API 24/25 fallback; foreground artwork stays inside the adaptive safe zone. The current dependencies and used framework APIs compile at this minimum; that is not a claim that every old phone has been physically tested. Target/compile API 37. Default APK includes ARM64, ARMv7 and x86_64; there is no Rust Skia build because Android Compose uses the platform renderer.

```sh
sdkmanager --channel=3 "platforms;android-37.0" "build-tools;37.0.0" "ndk;30.0.16248370"
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk --version 4.1.2 --locked
# Set ANDROID_HOME and JAVA_HOME first.
bash apps/compose/gradlew -p apps/compose :androidApp:assembleRelease :androidApp:lintRelease
adb install -r apps/compose/androidApp/build/outputs/apk/release/androidApp-release.apk
```

`--channel=3` permits the precisely pinned NDK package; it does not select unpinned SDK versions. A quicker phone build can use `-PrustAbis=arm64-v8a`. Both debug and release variants deliberately use the [fixed public development certificate](androidApp/signing/README.md); the release APK is nevertheless non-debuggable and R8/resource-shrunk. Changing from an older randomly generated debug certificate requires uninstalling that older installation once.

Pair in system Bluetooth settings. On Android 12+, Nearby devices permission is required; permanent denial has an app-settings path. Revocation/framework errors are surfaced rather than treated as empty successful readings. There is no scanning, location or internet permission. The application owns its controller and automatically connects to a remembered or sole supported paired device. A non-sticky connected-device foreground service retains the control link while the activity is backgrounded. Recents task removal stops that service, closes the socket and cancels local phone actions; no boot receiver or periodic background reconnect worker is installed. Reconnection attempts after link loss are bounded and system Bluetooth broadcasts can signal a connected case/control link even without an audio profile. Rotation does not opt out of activity recreation. Backgrounding cancels active fit/finding work, not the connection. Android may cache the process after task removal; OpenBuds does not keep a service, control socket or phone action alive.

Optional four-tap phone actions are scoped to the selected model/device. Ringing uses API-28 speaker-routing support and does not change volume/DND; Android 7/8 users can choose the other actions. Torch asks for Camera permission but captures no images. Show OpenBuds requires notifications on Android 13+ and opens through a notification in the background. Ring/torch share a 15-second bounded runner and a short timeout-bounded wake lock, never an idle wake lock. Effects/routing and modern foreground-service permission behavior still need physical validation.

## UI behavior

- Mobile uses a dashboard and detail pages with Back, not a desktop sidebar moved into bottom tabs.
- Wide desktop layouts use task navigation and two-column working areas. Small/short windows remain scrollable.
- Navigation, per-page scroll and EQ drafts use saveable state. Android system bars and IME insets are handled natively.
- EQ starts with read-only stock presets and saved profiles, not an editable default curve. Explicit create/edit opens independently draggable frequency handles and live frequency/gain feedback. Keyboard arrows and labelled accessibility actions replace six sliders.
- Frequencies, gain/name limits and presets come from the selected SDK model; Air7 has six real bands. Edits remain local until save, and device-reported applied selection is separate from the local draft.
- Dynamic sound is separate, with model-defined bands/units; its band controls appear only while enabled. No fabricated stock curve or invented dB units are shown.
- Custom IDs come from device readback after Add, not predicted IDs. Save/apply never silently replays a timed-out setter.
- Disconnected readings stay unknown. No synthetic device mode, downloadable soundscape catalogue or firmware-update entry point is shipped.
- Wind reduction appears under ANC and Transparency and changes only its feature preference. Transparency warns that this may affect its effect. Enhance voices uses an explicitly experimental candidate mapping, not a verified Air7 claim.
- Original earbud/case identification icons share a centered grid with percentages and plain status labels. Docked buds have a distinct appearance with finite placement/tint transitions; case battery can be relayed through them. No lid sensor is inferred. Page transitions and fit progress are purposeful; nothing decorative loops at idle.
- Known wear prerequisites are explained before an action; wear-blocked noise choices open a help dialog instead of behaving like dead disabled controls. Unexpected SDK/transport failures become shared recovery messages; technical details require an explicit Details action. Setters are never automatically retried.
- Large system fonts can wrap the readings into fewer columns. The persisted **Reduce motion** setting disables status transitions (and the optional Android splash fade); Android's system animation scale is also respected by Compose.
- No continuous decorative animation, UI repaint timer, procedural product models or unlicensed manufacturer artwork is shipped.

## Tests and packaging

```sh
bash apps/compose/gradlew -p apps/compose :shared:desktopTest
bash apps/compose/gradlew -p apps/compose :desktopApp:writeRuntimeMetadata :androidApp:writeRuntimeMetadata
```

Tests cover disconnected/lifecycle safety, wear preflight, contextual error handling and detail disclosure, icon/value alignment, labelled preset readback, fit-result presentation, ANC/Transparency wind controls, variable-model capabilities, case relay, remembered ANC, bounded host actions, keyboard/accessibility EQ input, contextual multipoint menus, band bounds, six-band round trips, reserved gestures and shared screen navigation at 320–1280 logical widths. Shared renderer tests do not substitute for Android IME, accessibility, permissions or actual-earbud checks. See [test strategy](../../docs/testing.md). Packaging tests run with `python -m unittest discover -s tools -p 'test_*.py' -v`.

`tools/package_release.py` packages release outputs, license notices, dependency coordinates/checksums and the exact committed workspace source. Run it only after committing the source revision that produced the binaries. It does not turn the workspace archive into a complete offline third-party corresponding-source bundle.
