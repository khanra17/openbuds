# Android signing

Release builds use the Android Gradle plugin's default `debug` signing configuration, without a project-specific keystore, key-generation script, credentials or CI secret. The plugin manages its standard development keystore outside the repository (`~/.android/debug.keystore`). No key is shipped with the source.

Signing and build optimization are independent. `assembleRelease` remains non-debuggable, R8-optimized, code/resource-shrunk, and uses Cargo release libraries (opt-level 3, thin LTO, one codegen unit, stripped debug information). No debug APK is distributed.

Google does not provide a universal production signing key. This default development signature is **not publisher authentication or Play Store release signing**. A fresh build machine may use a different certificate. Updating from a differently signed APK (including the previous public-template-key builds) requires uninstalling the old app, losing its local preferences. Do not uninstall automatically or silently.

All `*.keystore` and `*.jks` files remain ignored. Production/Play distribution would require a separate, explicitly authorized signing arrangement.
