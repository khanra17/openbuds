# Model drivers and capability contract

Only `realme.buds-air7` is implemented. A registry is an extension point, not a claim that other earbuds already work.

## One registered driver, no frontend protocol

`bindings/c/src/drivers/mod.rs` declares each model module once with its ID and Bluetooth service. The registration macro pairs that module's `profile` and `create` functions. `driver.rs` supplies the shared interface and registry lookup; unknown IDs are rejected before opening a transport. No Air7 fallback is applied to another registered model.

An adapter owns its request decoder, wire-specific client, initialization, event normalization and capabilities. Air7 lives in `drivers/air7.rs` and uses the existing typed `realme_buds::devices::air7` protocol implementation. Its enum names, EQ frequencies and feature IDs are derived from that module where possible, not copied into Kotlin.

To add a model:

1. Implement its protocol/client in an isolated module (and export that Rust module if it is a public typed client).
2. Add a matching SDK adapter module implementing `Driver`, plus `create` and `profile`.
3. Add one entry to the `models!` registry. Test metadata, normalization, request validation and real device evidence.

The current platform transports use Bluetooth Classic RFCOMM. A different transport family, such as BLE/GATT, still needs a platform transport adapter; registering a model alone cannot supply it. The C callback transport can already be supplied by an embedding application.

Do not edit Android/desktop Bluetooth UUIDs, CLI device selectors, or frontend model-ID switches for another RFCOMM model. Existing normalized control kinds render from the profile. A genuinely new kind of control may require a new reusable renderer and contract extension—not an Air7 branch or invented support.

## Discovery and compatibility

- C: `ob_v1_driver_catalog` returns registered IDs, connection metadata and profiles without Bluetooth I/O. Existing C ABI types/functions remain intact.
- Rust application interface: `openbuds::session::catalog` and model-selected `Session`.
- JNI: the catalog, explicit model ID and SDK timeout access are thin wrappers over that same layer.
- CLI: `buds models`; select with `--model ID`. Generic `call`/`rpc` route to the selected SDK driver.
- Existing named CLI shortcuts and typed Air7 C entry points remain explicitly Air7-specific. They do not become generic by changing a label.

## Profile data

Profiles declare identity/name aliases and Bluetooth service, components, noise choices/wire names/ANC levels/default strength/wear requirements, EQ frequencies/ranges/presets/name-byte limit, feature preferences and presentation groups, gesture bindings/functions/cycles, dynamic bands/units, multipoint state/action policy, tools, contextual rejection codes, and host-event IDs.

Kotlin `DeviceProfile` is a renderer/controller contract. It contains no production Air7 ID/frequency table. Model/device identities scope saved connection selection, remembered ANC, page state, EQ drafts and opted-in Android phone actions. Pending confirmations are cleared when device identity changes.

Static metadata means the driver offers a control; it does not prove every firmware exposes a current value or that its physical effect was verified. Missing readback remains unavailable, not Off. Experimental declarations remain explicit. Air7's candidate voice-enhancement flag 9 is experimental; its correspondence to Link's Enhance voices control remains unconfirmed. Find earbuds is not advertised for Air7.

## Host behavior is not firmware behavior

Android supplies optional phone actions for a model that declares the host-event capability. Air7 uses preference 38 and host operations 5/6, not touch function 26. Local per-device opt-in and current readback are required. Events are not replayed by the shared flow.

Ring and torch use a bounded 15-second action helper; repeated starts do not extend its deadline. A temporary, timeout-bounded partial wake lock keeps that stop timer running with the screen off. Nothing holds a wake lock at idle. Cleanup runs on stop, failure, connection loss and task removal; it is software cleanup, not a hardware watchdog.

Ringing requests the built-in speaker, leaves system volume/DND unchanged, and stops if Android reports routing elsewhere. This requires Android 9/API 28; older supported Android versions can use the other actions. Torch requires Camera permission and the appropriate foreground lifetime; it captures no images. Background Show OpenBuds uses a notification rather than bypassing Android activity-launch restrictions. Android 13+ notification permission is required for that action.

These are application implementations, not newly discovered earbud protocol commands or proof of a physically tested four-tap gesture.
