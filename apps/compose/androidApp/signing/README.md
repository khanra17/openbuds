# Public development signing key

`public-dev.keystore` is the public React Native template development key, copied unchanged from:

https://github.com/react-native-community/template/blob/94590414c34f9476bab5ad19e103d2c7defa0809/template/android/app/debug.keystore

- Alias: `androiddebugkey`
- Store/key passwords: `android`
- File SHA-256: `221e0a3106aa4c3ccc154e0a418b55020b3f9ea6e84f92e8749cd9e2f39f5e58`
- Certificate SHA-256: `FAC61745DC0903786FB9EDE62A962B399F7348F0BB6F899B8332667591033B9C`

Both Android build variants use this fixed key, so local and CI APKs can update each other without private secrets or keystore setup. The release variant is still non-debuggable, R8/resource-shrunk, and linked against optimized Rust release libraries.

**This key provides no publisher identity or protection against forged updates. Anyone can sign an APK with it.** These are development/community-distribution builds, not privately signed production or Play Store releases. Never use this key for privileged/system apps, private data trust, or signing other projects' production software.

If an older installation used an automatically generated local debug key, uninstall that installation once before installing these APKs. Future builds retain the same certificate. Do not commit private production keystores; only this explicitly public file is exempted in `.gitignore`.
