# realme Link Air7 control audit

## Scope and evidence

The reference is the installed `com.realme.link` version code `530214`, paired with realme Buds Air7 firmware `1.1.0.62` on the Android 10 phone. Device pages, settings lists and help were inspected without changing settings or starting audio. Earlier protocol/physical evidence is recorded separately in [`air7.md`](air7.md).

The application scope is **earbud controls and device information**. Firmware installation, MindFlow/O Relax soundscapes, demonstration audio and account/store/health/cloud services are excluded. Android phone actions were subsequently requested and are implemented separately with explicit per-device opt-in. There are no reference catalogues or inactive entry points for these services. Firmware-version information remains. No synthetic device mode is shipped.

This inventory is specific to the observed Link/firmware versions. It is not proof that every preference has a verified physical effect or that every undocumented feature is known.

## Control coverage

| Link control / information | OpenBuds placement and implementation |
| --- | --- |
| Air7 identity and battery | Home: separate, aligned left/right/case readings. Missing percentages stay unknown. Disconnected component percentages are unavailable except for a known in-case relay route; case battery does not require a direct case link when an earbud is in the case. Raw disconnected values alone never establish live telemetry; their raw numbers remain in SDK reports. Case connectivity does not imply lid position or prove that a reported 0% means an empty case. |
| Noise cancellation / Off / Transparency | Home: mutually exclusive choices driven by readback. ANC exposes Mild, Moderate, Maximum and Adaptive. Returning to ANC restores its last read-back strength saved per model/device. Before one has been observed, the driver's Moderate fallback is used, not a claimed hidden firmware register. |
| Wind-noise reduction | Home, within ANC and Transparency: feature 26. The warning explicitly says it may affect the transparency effect. Toggling it does not switch noise modes. Placement/warning follow the user's Link observation; this pass did not change noise mode to retest the acoustic effect. |
| Dynamic audio | Sound & EQ: reported preference; device-defined Low/Mid/High adjustments appear only while enabled. No invented dB units. |
| EQ mode | Sound & EQ: Original sound, Serenade, Clear Bass and Deep Bass; custom create/select/edit/rename/delete. All six frequencies remain independently editable: 62, 250, 1000, 4000, 8000 and 16000 Hz. Edits stay local until explicit save. |
| High-definition sound | Sound & EQ: LHDC 5.0 preference, with a compatible-phone requirement. Not a claim that the host negotiated LHDC. |
| Volume enhancer | Sound & EQ: actual readback, with explicit loudness confirmation before enabling. Not Android media volume. |
| Spatial Audio | Sound & EQ: reported on-device preference. No fabricated Listen demonstration or head-tracking control. |
| Game mode | Sound & EQ: reported low-latency preference. No latency benchmark claim. |
| Button settings | Gestures: left/right double-tap, triple-tap and hold assignments; Link's playback, volume, assistant, game, noise-cycle and None choices. Unverified combinations require confirmation. Single-tap stays excluded because stored state did not establish working behavior. |
| Hold noise-control modes | Gestures: four protocol-supported combinations of cancellation, Off and Transparency, when a hold noise-cycle binding is reported. Physical cycling remains separately unverified. |
| Auto play/pause | Gestures: reported wearing-detection preference. Actual playback also depends on the audio host. |
| Auto-answer | Gestures: reported take-from-case/wear-to-answer preference. Host call routing still applies. |
| Dual-device connection / history | Device: reported preference, returned host list, connect/disconnect/remove-stored-pairing actions. Menus offer Connect for a disconnected host or Disconnect for a connected host, using the model's state/action policy. Disruptive actions require confirmation; the settings link is separate from audio ownership. |
| Ear tip fit test | Device: graphical fit guidance, both-worn prerequisite, explicit sound consent, active progress and per-ear verdicts. Device-reported verdicts are named per ear; missing or unknown verdicts are not treated as success. This pass did not play the test. |
| Firmware version and Bluetooth address | Device information: actual reported version and selected address. No firmware-update entry. |
| Pairing | System Bluetooth settings manage the phone's bond. Removing a host's stored pairing from the earbuds is a separate, confirmation-gated SDK operation. Link account/device unbinding is not reproduced. |
| Help / licences | Device information and packaged source/notices describe OpenBuds, its evidence boundaries and dependencies, without copying proprietary Link content. |

Air7's capability profile hides **Find earbuds** because a working positive tone was not established. A generic Stop active test/sound button is not shown; stop controls are labelled for an actually active fit test or phone action. Gesture rows include finger/tap/hold illustrations.

The requested **Enhance voices** control uses candidate generic SDK feature 9 only behind experimental confirmation. No decompilation established that mapping: the available REA Android provider lacked its caller-supplied JADX JAR. Neither this mapping nor its physical effect is considered verified.

## Existing protocol / UI crosswalk

The shared Rust dispatcher already supplies the C SDK, CLI `call`/`rpc` and Compose JNI bridge. UI work must use it, not reconstruct the protocol.

| Control | Existing request path |
| --- | --- |
| Readings and information | `snapshot`, `battery`, `wear`, `versions`, `firmware`, `diagnostics`, `read_property` |
| Noise modes and hold cycle | `noise`, `set_noise`, `noise_cycle`, `set_noise_cycle` |
| Named feature preferences, including wind reduction | `features`, `set_feature` |
| Stock/custom EQ | `eq_preset`, `set_eq_preset`, `custom_eq`, `set_custom_eq` |
| Dynamic sound | `dynamic_bands`, `set_dynamic_band` |
| Volume enhancer | `volume_enhancer`, `set_volume_enhancer` |
| Multipoint | `multipoint`, `multipoint_action` |
| Gestures | `touch`, `set_touch` |
| Fit/finding | `run_fit_test`, `stop_fit_test`, `find_for`, `stop_finding` |
| Reported changes | `subscribe`, `next_event` |

Android phone actions now consume feature 38 and host operations 5/6 with local opt-in: Ring phone, Flashlight, or Show OpenBuds. This is an application handler, not touch function 26. Ring/torch are bounded and stop on lifecycle cleanup; physical four-tap behavior and OS routing were not established by readback. Background Show OpenBuds uses a tappable notification to respect Android launch restrictions. There is no firmware writer or soundscape implementation in the SDK/UI path.

## Wear-state UX and errors

The first-day evidence already established:

- Noise-mode writes work with either ear worn; neither-worn attempts returned **command `0x0404`, status `14`**.
- The fit test requires both ears worn; one-ear starts returned status `14`.
- Status `14` is contextual evidence, not a universal label for every command.

The frontend now mutes known-neither-worn noise choices and opens a plain-language help dialog when tapped, explaining **“Put on at least one earbud to change noise control.”** Fit-test availability similarly explains the both-worn requirement. Missing/contradictory sensor readings remain unknown rather than pretending to prove a wear state. Controller preflight also checks before dispatch, covering changes while an action/dialog is pending.

If the device still rejects a request, JNI preserves the C SDK's existing category, command and device-status fields. The shared frontend presents a recovery message; raw protocol/transport text is available only through **Details**. New wear data clears a resolved wear instruction. No rejected or timed-out setter is automatically replayed, and timeouts do not claim that a write failed.

## Validation boundary

Automated tests cover the contextual status-14 messages, all SDK error categories, no-write wear preflight, error-detail disclosure, actual readback decoding, sensor uncertainty and icon/value alignment at fractional Android densities. Renderer fixtures live only in test source sets, not in the shipped application.

Read-only Link and OpenBuds observations do not establish physical DSP effects, every gesture, negotiated audio codecs, factory reset, OTA or complete future-version parity. The local screenshots are under ignored `target/validation/`; they are not application assets. See [`testing.md`](testing.md) for dated build and device checks.
