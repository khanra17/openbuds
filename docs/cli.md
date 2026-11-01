# CLI and application wrappers

`buds` uses clap for argument parsing. `buds models` lists the SDK registry without connecting. Select a registered model with `--model ID`; generic `call` and `rpc` use its driver and Bluetooth service. Named shortcuts below remain Air7-specific and reject other models. Pair the device in system Bluetooth settings. Windows resolves its control UUID through SDP; Linux uses the BlueZ client profile. macOS Bluetooth is not implemented. If exactly one paired device has the standard Air7 name it is selected automatically; renamed/multiple devices need `--address`.

```sh
buds models
buds devices
buds --model realme.buds-air7 --address AA:BB:CC:DD:EE:FF status
buds battery
buds noise anc --level moderate
buds noise transparency
buds toggle auto-play-pause on
buds eq list
buds eq preset 0
buds eq create Warm 3 2 0 0 -1 -2
buds eq select 7
buds eq rename 7 "Evening"
buds eq delete 7 --confirm
buds dynamic 1 2
buds touch
buds bind left double-tap play-pause
buds bind right triple-tap next
buds multipoint
buds watch
```

Flat `eq-list`, `eq-save`, `eq-select`, `eq-rename` and `eq-delete` remain compatible. Named gesture values are `double-tap`, `triple-tap`, `hold`; named actions are `play-pause`, `previous`, `next`, `assistant`, `noise-control`, `off`. Numeric IDs remain available for experts, but reserved/unverified gesture/action combinations require `--confirm-experimental`. A returned binding proves only stored state, not that the gesture is supported. Single-tap is known to persist without working on the user's Air7.

Six EQ gains always correspond to 62, 250, 1000, 4000, 8000 and 16000 Hz, within −6..6 dB. The CLI does not invent a stock curve or convert dynamic-sound strengths into dB.

## Machine output

Use `--json` for compact JSON and structured errors on stdout; connection progress goes to stderr. Ordinary commands return the requested semantic JSON value, with `--raw` available for numeric wire-level representations. `watch` emits one JSON event per line. Successful process exit is 0; operation failure is 1 and argument parsing failure is 2. Never parse human-facing error text to control a device.

For a stable, uniform response envelope across the entire SDK, use `call` or `rpc` instead of depending on each friendly command's presentation.

```sh
buds --json call '{"op":"noise"}'
buds --json call '{"op":"set_noise","mode":"Maximum"}'
buds --json call '{"op":"read_property","property":"Color"}'
```

The last property name must be one of the exact `ReadProperty` enum variants in the matching SDK source; unknown names are rejected, not guessed. Successful `call` output uses the SDK envelope:

```json
{"schema_version":1,"driver":"realme.buds-air7","data":{}}
```

A write response with null data is an acknowledgement, not evidence of physical effect. Read the relevant state afterward. Do not automatically retry setters after a timeout: the write may already have succeeded.

## Persistent NDJSON session

Start `buds --address AA:BB:CC:DD:EE:FF --json rpc`. Initialization happens once; each request uses the same Bluetooth session. Write one JSON frame per line and flush it:

```json
{"id":1,"request":{"op":"snapshot"}}
{"id":2,"request":{"op":"set_noise","mode":"Moderate"}}
{"id":3,"request":{"op":"noise"}}
{"id":4,"request":{"op":"subscribe"}}
{"id":5,"request":{"op":"next_event","timeout_ms":1000}}
```

Responses preserve the request ID:

```json
{"id":3,"schema_version":1,"ok":true,"data":{}}
{"id":5,"schema_version":1,"ok":false,"error":{"code":"timeout","message":"..."}}
```

`{"id":6,"request":{"op":"cancel"}}` is a transport-level cancellation request, handled even while a sound/test request is active. Its acknowledgement means cancellation was requested; wait for the active request's result/cleanup. Ctrl+C also cancels bounded sound/test work. There are at most 16 queued requests and a one-MiB frame limit. Excess requests return `busy`; invalid JSON produces a per-frame error without crashing the session. Requests after EOF are not accepted; already queued frames are drained before exit. Process termination can still interrupt cleanup, so it is not a hardware watchdog.

Only explicit `next_event` calls return notifications; they are not mixed into unrelated request responses. Never run simultaneous Bluetooth sessions for the same device unless you have separately validated that firmware behavior.

## Complete SDK request coverage

Both `call` and `rpc` pass requests to the selected SDK driver, just like the C SDK/JNI bridge. No operation is dropped by a separate CLI translation layer. Air7 uses the strict `Request` enum; its canonical request fields/enums are in [`bindings/c/src/air7.rs`](../bindings/c/src/air7.rs).

| Area | Operations |
| --- | --- |
| Session/state | `initialize`, `snapshot`, `battery`, `versions`, `wear` |
| Feature settings | `features`, `feature_ids`, `set_feature`, `set_feature_id` |
| Noise | `noise`, `set_noise`, `set_anc`, `set_transparency`, `noise_cycle`, `set_noise_cycle` |
| Gestures | `touch`, `set_touch` |
| EQ | `eq_preset`, `set_eq_preset`, `custom_eq`, `set_custom_eq` |
| Dynamic sound/loudness | `dynamic_bands`, `set_dynamic_band`, `volume_enhancer`, `set_volume_enhancer` |
| Multipoint | `multipoint`, `multipoint_action` |
| Notifications | `subscribe`, `next_event` |
| Bounded sound routines | `run_fit_test`, `stop_fit_test`, `find_for`, `stop_finding` |
| Diagnostics | `firmware`, `diagnostics`, `read_property`, `status_features`, `stored_data`, `use_time`, `raw_read` |
| Explicit experimental preference | `set_auto_power_off` |

`set_feature_id` and experimental gesture/auto-off writes require their explicit confirmation fields. Volume increases, finding/fit sounds, pairing removal and other disruptive actions retain their API confirmation and sensor guards. `raw_read` is read-only; there is no arbitrary-write, firmware-flash or factory-reset escape hatch.

Example of adding a custom EQ through the wrapper interface:

```json
{"id":10,"request":{"op":"set_custom_eq","action":"Add","eq":{"selected":false,"min":-6,"max":6,"id":0,"name":"Warm","bands":[{"frequency":62,"gain":3},{"frequency":250,"gain":2},{"frequency":1000,"gain":0},{"frequency":4000,"gain":0},{"frequency":8000,"gain":-1},{"frequency":16000,"gain":-2}]}}}
{"id":11,"request":{"op":"custom_eq"}}
```

Use the returned assigned ID. Do not predict IDs or interpret every acknowledged/persisted setting as an implemented physical feature. See [Air7 evidence and limits](air7.md).

Opt-in `--trace FILE` can include addresses, host names and custom EQ names. Do not enable logging silently in wrapper applications.
