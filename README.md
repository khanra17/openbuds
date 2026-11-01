# OpenBuds

OpenBuds is an open-source app and SDK for controlling earbuds directly, without a cloud account. It brings an Android app, a desktop app and a command-line tool together around one Rust protocol implementation.

**Supported today: realme Buds Air7.** The codebase has a model-driver registry for adding other devices, but no other models are implemented yet. Android, Windows and Linux can connect over Bluetooth Classic; the macOS build currently has no Bluetooth transport.

## Download

Get the latest APK, desktop app, CLI or C SDK from [GitHub Releases](https://github.com/khanra17/openbuds/releases/latest).

Desktop downloads come in two variants: **bundled runtime** includes Java, while **external Java** needs a matching Java 21+ installation. On Windows, `OpenBudsTray.exe` starts the lightweight tray host; the full app opens when needed.

Android builds are optimized and non-debuggable, but use the Android tooling's default development signing key—not a publisher key. If a new build has a different signing certificate, Android requires you to uninstall the old app first, which removes its local data. See [Android signing](apps/compose/androidApp/signing/README.md).

## Using OpenBuds

Pair the earbuds in your system Bluetooth settings, then open OpenBuds. The app tries the remembered device, or the only supported paired device. Android 12 and newer require Nearby devices permission.

On Air7, the app provides battery and wear readings, noise control, sound settings and EQ, gesture configuration, multipoint controls, and a fit test. EQ changes stay local until saved. Values the device has not reported remain unknown. The candidate Enhance voices control is experimental and its mapping is unconfirmed.

Android keeps the connection in a visible foreground service while the app is in the background. Removing the app from Recents closes the session. Optional four-tap phone actions can ring the phone, turn on the flashlight, or show an open-app notification; ring and flashlight actions are time-limited. Physical four-tap behavior has not been verified on the current build.

## Command line

```sh
buds models
buds devices
buds --model realme.buds-air7 --address AA:BB:CC:DD:EE:FF status
buds noise anc --level moderate
buds --json rpc
```

See the [CLI guide](docs/cli.md) for commands and the JSON interface.

## Build and develop

OpenBuds uses Rust 1.88+, JDK 21 and the Android SDK/NDK for Android builds. Desktop builds also need the platform's native linker; Linux needs `pkg-config` and `libdbus-1-dev`.

```sh
cargo build --workspace --release --locked
bash apps/compose/gradlew -p apps/compose :desktopApp:createReleaseDistributable
bash apps/compose/gradlew -p apps/compose :androidApp:assembleRelease
```

- [Application build instructions](apps/compose/README.md)
- [Rust and C SDK guide](docs/sdk.md)
- [Adding a model driver](docs/sdk-models.md)
- [Air7 protocol and limitations](docs/air7.md)
- [Tests and validation](docs/testing.md)

The apps and generic CLI use model-specific drivers and capability profiles. Adding a driver does not automatically add support for a different Bluetooth transport.

## Safety

Don't use finding tones while an earbud is worn. Sound-stop cleanup is best-effort, not a hardware watchdog. If a setting request times out, read the device state before trying again; it may already have succeeded. Firmware writing and factory reset are not implemented.

## License

[GPL-3.0-or-later](LICENSE). Third-party components retain their own [licenses](THIRD_PARTY_NOTICES.md). OpenBuds is unofficial and is not affiliated with the device manufacturers.
