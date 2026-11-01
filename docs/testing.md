# Test strategy

OpenBuds separates deterministic software tests from device evidence. A passing serialization, acknowledgement, or readback test does not establish that a physical gesture, sound effect, or audio-path behavior works.

## Automated checks

From the repository root:

```sh
cargo fmt --all -- --check
cargo test --workspace --all-features --locked
cargo clippy --workspace --all-targets --all-features --locked -- -D warnings
bash apps/compose/gradlew -p apps/compose :shared:desktopTest
bash apps/compose/gradlew -p apps/compose :androidApp:assembleRelease :androidApp:lintRelease
```

The Android Gradle tasks need JDK 21 and the pinned SDK/NDK documented in [`apps/compose/README.md`](../apps/compose/README.md). A local quick build can restrict native Rust compilation to `-PrustAbis=arm64-v8a`; the release workflow covers the configured production ABI set.

The Rust tests cover packet framing, Air7 command payloads, readback parsing, validation, state descriptions, cancellation and transport behavior. The CLI/RPC and C ABI use the same strict Rust request dispatcher. Compose tests cover controller lifecycle and disconnected-action safety, six-band equalizer bounds/round trips, and page content/navigation at 11 desktop sizes from 320×640 to 1280×800. The screen-test Bluetooth stub fails if a test attempts a real connection.

Android lint is expected to report the optional ChromeOS x86_64 ABI warning for an arm64-only quick build; review other warnings rather than suppressing them wholesale. The checked manifest intentionally has no Internet or scanning/location permission. Connected-device foreground-service permission supports the requested background lifetime; media/camera foreground types, optional Camera, notifications and a temporary action-only wake lock support opted-in phone actions. No boot receiver or sticky service is installed.

## Manual and hardware checks

Before a release, review narrow and wide UI screenshots, Android system-bar/keyboard behavior, permission denial/revocation, automatic connection, background retention and Recents cleanup, and the actual packaged desktop app. Minimum API support is API 24; a build is not a physical test of every Android release or vendor Bluetooth stack. Windows taskbar/tray behavior and the Compose app's memory use need their own measurements; the small tray-host memory sample is not a measurement of the full app.

For an Air7-specific evidence test, record firmware, host, setup, exact before/after reads and whether the test changed state. Restore settings only with permission. Never start fit/finding tones or change a device setting merely to make a checklist green. Fit/finding sounds need explicit user consent and a safe physical setup; finding-sound cleanup is best-effort, not a hardware watchdog. Do not test while the earbuds are worn. Read [`air7.md`](air7.md) and [`realme-link-audit.md`](realme-link-audit.md) for current evidence and unverified branches.

A persisted/read-back value is evidence of stored state only. Physical behavior, latency, negotiated host codec, playback routing, and manufacturer-app services require separate evidence. Do not describe parity as complete while any UI inventory, request mapping, or host/device behavior remains unverified.

## Continuous integration

`.github/workflows/check.yml` runs Rust checks, shared Compose UI tests and an Android release/lint build. `.github/workflows/build.yml` additionally builds platform release distributions and packages notices/source metadata. Neither CI workflow can establish physical earbud behavior or replace the manual checks above.

The Android release uses the Android Gradle plugin’s default development signing configuration; it remains non-debuggable and optimized. No project keystore or publisher signing is configured. Certificates can differ between machines; this is not publisher authentication. Desktop artifacts are unsigned.

## Earlier local run · 2026-10-27

On 2026-10-27, the workspace passed `cargo fmt --all -- --check`, `cargo test --workspace`, `cargo clippy --workspace --all-targets --all-features -- -D warnings`, and `cargo check --workspace --all-features`. Compose shared UI tests passed for all 11 configured sizes; Android release Kotlin compilation, release lint, and optimized release APK assembly passed with the quick `arm64-v8a` setting. The test-install APK passed `apksigner` verification and `zipalign`; its manifest was checked for expected Bluetooth permissions, API 24 minimum and no Internet permission. Lint reported the expected ChromeOS ABI warning. A full local native `cargo build --workspace` did not complete: this Windows shell resolved `link.exe` to an incompatible non-MSVC linker. The Android phone was unavailable for a final post-copy screenshot/install check; no device writes or sound-producing tests were run during that pass.

## Earlier local run · 2026-10-28

- **Rust:** 65 tests passed with workspace/all features; Clippy passed with all targets/features and warnings denied; formatting and the optimized, locked workspace build passed. The local linker issue was resolved by putting the installed Windows linker wrapper in `~/.cargo/bin` ahead of Git's unrelated `link.exe` on PATH.
- **Compose/Android:** 32 shared tests passed (3 controller, 7 equalizer, 22 UI cases across 11 sizes). The new modal checks exercise scrolling and require sound consent without sending a write. The arm64 test-install release assembled and passed lint (only the expected ChromeOS ABI warning), APK signature verification and 16-KiB zip alignment. The APK is non-debuggable, minimum API 24, and has only expected Bluetooth and framework-internal receiver permissions.
- **Physical phone:** the Android 10 Redmi Note 4 ran the updated `org.openbuds.air7.test` package alongside the untouched public-package installation. Home, EQ bands, reported sound preferences, gesture choices, host list, tools and firmware were reviewed. Reported readings included 100% left/right, 80% case, both ears in case and firmware `1.1.0.62`. Portrait/landscape navigation and background disconnect/manual reconnect were checked; rotation settings were restored. No earbud setters or sound-producing tests were used. A later final warm-palette build was installed and reconnected successfully.
- **Desktop:** `createReleaseDistributable` passed. The actual optimized Windows package was launched, reviewed at 1450×980 and 760×780 physical pixels, and closed. Shared screen tests cover navigation, EQ draft retention and details at other sizes; this is not a manual integration test of every desktop control. The taskbar window is not a tool window; OpenBuds's own icon replaces the JVM default. The desktop launcher and tray binary both reported `NotSigned`.
- **Tray:** startup created no JVM; a second launcher reused the existing host/UI; version-4 activation restored a minimized window. Closing the desktop exited its JVM and packaged launcher while leaving the native tray alive. The host then closed cleanly. The recovery test removed only OpenBuds's shell icon and sent `TaskbarCreated`; the host recreated its absent icon. User Explorer was not restarted. These activation/recovery checks used native messages, not a physical click test of every Windows shell configuration.
- **Resource sample:** the tray alone used **9.68 MiB working set / 1.49 MiB private memory**, with **0 ms CPU added during a 10-second idle sample**. The full UI separately sampled **181.97 MiB working set / 160.91 MiB private memory** soon after startup. These are local samples, not upper bounds or cross-platform benchmarks.
- **Packaging guard:** `package_release.py` now rejects dirty/untracked source changes before touching staging, preventing an archive of stale HEAD from being paired with new binaries. That rejection was checked. Final source/release archives and GitHub workflows are still not validated end-to-end from a committed revision.

Screenshots, logs and native review scripts for this pass are local under `target/validation/` (ignored by Git). These are dated observations, not a guarantee about future source changes or CI runs.

## Scope and UX follow-up · 2026-10-28

- Removed the production synthetic-device path, launcher argument, sample snapshot, soundscape catalogue and firmware-update UI entries. Renderer smoke checks now open the actual disconnected app without enumerating or connecting Bluetooth. Test fixtures remain test-only.
- Split the application into focused screens, shared components/theme, snapshot/controller, result presentation and UI error policy. Rust protocol commands and the public C ABI are unchanged; JNI now preserves their existing error category/command/device-status fields instead of flattening them into a Java exception string.
- **69 shared tests passed**: 6 controller, 8 error-policy, 3 wear-readiness, 8 EQ/snapshot, 3 result-formatting, 3 density/alignment, 4 noise controls, 1 result-dialog and 33 screen cases at 11 sizes. These include the exact formerly exposed status-14 message, contextual wear rules, no-write preflight, no automatic setter replay, opt-in technical details and actual icon/value center alignment at densities 2.625, 2.7 and 3.0. The 320-dp layout stacks noise choices rather than breaking their labels.
- **69 Rust tests passed** with workspace/all features, including preservation of JNI rejection fields. All-target/all-feature Clippy with warnings denied and formatting passed. Seven Python packaging tests, workflow actionlint and the tracked diff whitespace check passed.
- **Android:** rebuilt the optimized test-install APK for ARM64, ARMv7 and x86_64; release lint passed with 0 errors and 4 warnings. Reviewed warnings concern the prescribed splash-vector dimensions, the dynamic ABI expression (the actual APK includes x86_64), the API-26 icon resource (an API-33 monochrome override exists), and a SharedPreferences KTX suggestion. They were not suppressed or represented as a warning-free build. Signature, API-24 minimum, non-debuggable manifest, expected permissions and 16-KiB ZIP alignment were checked.
- **Phone:** installed into `org.openbuds.air7.test`, preserving the public installation. Compared the live Link device screen without changing a control, then connected OpenBuds read-only. Both buds reported 80% and not worn; the case link was absent. The new home displayed aligned icons/labels/values, an unavailable offline case percentage rather than a live-looking 0%, and disabled noise choices with the wear instruction. No mode/feature setter or sound test was used. The raw-rejection and Details paths were exercised with test-only links, not by writing to the physical earbuds.
- **Desktop:** the final optimized distribution passed (`ux-final-release.log`, 6m 18s). Both actual Windows variants passed the renderer/open/close smoke check, and the external-Java launcher compatibility check passed (`ux-desktop-smoke.log`). The bundled check hid external Java. Renderer startup remained disconnected, without a synthetic device. These are local Windows checks, not a GitHub-hosted Linux/macOS validation.

Evidence: `ux-regressions.log`, `ux-final-release.log`, `ux-rust-tests.log`, `ux-clippy.log`, `ux-apk-*`, `ux-final-phone.png` and the shared renderer reports. Local evidence is ignored, not shipped as application artwork. No GitHub workflow or clean committed-source release archive has been run by this follow-up. Modern Android splash/themed-icon shell behavior and Linux/macOS hardware integration remain outside the physical tests.

## Capability and lifecycle revision

- **81 shared tests passed**, including variable-model capabilities/EQ, case-battery relay, in-case precedence over a disconnected flag, automatic selection without unknown-model fallback, task-end-during-connect safety, remembered ANC, contextual wear help, no setter replay, gated/non-replayed host requests, bounded action cleanup/deadlines, keyboard/accessibility EQ editing and contextual multipoint menus. Screens still cover 11 dimensions. Reviewed narrow EQ and fit-consent screenshots; the frequency readout occupies its own row below the chart.
- **70 Rust tests passed** with workspace/all features; formatting and all-target/all-feature Clippy with warnings denied passed. The new C catalog test checks profile/transport identity and typed EQ metadata. An unknown CLI model was rejected before Bluetooth I/O. The optimized locked native workspace build passed.
- **Release:** the final integrated shared-test, Android release/lint and optimized desktop distribution build succeeded in **6m 30s** (`capability-final.log`). Android lint has **0 errors / 3 reviewed warnings**: prescribed splash-vector dimensions, the dynamic ABI-expression warning, and the API-26 icon resource despite its API-33 monochrome override. Gradle deprecations and ProGuard's 57 duplicate-class note remain.
- **APK:** `org.openbuds.air7.test`, API 24 minimum/target 37, non-debuggable, ARM64/ARMv7/x86_64, verified development signature and 16-KiB ZIP alignment. Expected connected-device/media/camera foreground, notification, optional Camera and wake-lock permissions are present; Internet/scanning/location permissions are absent. SHA-256: `87e3cb1a07f90e7ec2e5ee2f011271909c52d88cf7fcad3fe7e517f2b016090c`.
- **Windows:** both actual bundled and freshly staged external-Java renderers passed open/render/close smoke checks; the external launcher check passed. Bundled testing hid external Java. Current external staging is `target/validation/capability-external-java/OpenBuds/`. Seven packaging tests, actionlint and whitespace checks passed.
- **Physical boundary:** ADB listed no connected device at final validation. This revision was **not installed or physically tested** on the phone. Background retention/Recents cleanup, API-34+ foreground/camera policy, actual case-relay transitions, four-tap phone actions, speaker routing and DSP changes remain hardware checks—not conclusions from the software suite. No physical setting write, ringing, torch or earbud sound test was run.
- **Scope:** only Air7 is registered; the architecture permits additional drivers, not unimplemented compatibility. Candidate voice-enhancement flag 9 remains experimental and is not confirmed as Link's Enhance voices control. macOS transport, hosted workflows and clean committed-source archives remain unvalidated.

Evidence is local under ignored `target/validation/capability-*`, `redesign-core-tests.log` and `redesign-clippy.log`; renderer images remain under the shared test reports. No commit or push was made.
