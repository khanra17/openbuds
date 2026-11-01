// SPDX-License-Identifier: GPL-3.0-or-later
//! Air7-specific wire adapter and UI capabilities. No other model inherits this profile.
use crate::{
    air7 as api,
    driver::Driver,
    runtime::{self as error, Failure},
};
use realme_buds::{Transport, devices::air7};
use std::{
    any::Any,
    sync::atomic::{AtomicBool, Ordering},
    time::Duration,
};
pub struct Air7 {
    pub client: air7::Client<Box<dyn Transport>>,
    pub initialized: bool,
}
impl Driver for Air7 {
    fn initialize(&mut self) -> Result<(), Failure> {
        self.initialized = false;
        self.client.initialize()?;
        self.initialized = true;
        Ok(())
    }
    fn set_timeout(&mut self, duration: Duration) -> Result<(), Failure> {
        self.client.set_timeout(duration)?;
        Ok(())
    }
    fn call(&mut self, request: &[u8], cancel: &AtomicBool) -> Result<Vec<u8>, Failure> {
        let request: api::Request = serde_json::from_slice(request)
            .map_err(|e| Failure::new(error::INVALID_ARGUMENT, e.to_string()))?;
        let init = request.is_initialize();
        if !init && !self.initialized {
            return Err(Failure::new(
                error::NOT_INITIALIZED,
                "initialize the selected model first",
            ));
        }
        if init {
            self.initialized = false;
        }
        let result = api::execute(&mut self.client, request, || cancel.load(Ordering::Relaxed))?;
        if init {
            self.initialized = true;
        }
        serde_json::to_vec(&result).map_err(|e| Failure::new(error::INTERNAL, e.to_string()))
    }
    fn as_any_mut(&mut self) -> &mut dyn Any {
        self
    }
}

pub fn create(transport: Box<dyn Transport>) -> Box<dyn Driver> {
    Box::new(Air7 {
        client: air7::Client::new(transport),
        initialized: false,
    })
}
fn feature(
    key: &str,
    value: air7::Feature,
    name: &str,
    detail: &str,
    group: &str,
) -> serde_json::Value {
    serde_json::json!({"key":key,"id":value as u8,"wire_name":value,"name":name,"detail":detail,"group":group})
}
fn function(value: air7::TouchFunction) -> serde_json::Value {
    let mut identifier = air7::touch_function_identifier(value as u8);
    if value == air7::TouchFunction::Disabled {
        identifier.name = "None".into();
    }
    serde_json::to_value(identifier).expect("identifier")
}
pub fn profile() -> serde_json::Value {
    use air7::{
        AncLevel as A, Feature as F, Gesture as G, NoiseCycle as C, NoiseMode as N,
        TouchFunction as T,
    };
    let eq = air7::CustomEq::air7(0, "", [0; 6]);
    let options = [
        (
            G::DoubleTap,
            vec![T::PlayPause, T::NextTrack, T::VoiceAssistant, T::Disabled],
        ),
        (
            G::TripleTap,
            vec![
                T::PreviousTrack,
                T::NextTrack,
                T::VolumeUp,
                T::VolumeDown,
                T::VoiceAssistant,
                T::GameMode,
                T::Disabled,
            ],
        ),
        (
            G::Hold,
            vec![T::NoiseControl, T::VoiceAssistant, T::GameMode, T::Disabled],
        ),
    ];
    let choices: serde_json::Map<String, serde_json::Value> = options
        .iter()
        .map(|(g, fs)| {
            (
                (*g as u8).to_string(),
                serde_json::json!(fs.iter().map(|f| *f as u8).collect::<Vec<_>>()),
            )
        })
        .collect();
    let standard: serde_json::Map<String, serde_json::Value> = options
        .iter()
        .map(|(g, fs)| {
            let valid = fs
                .iter()
                .filter(|f| {
                    api::standard_touch_binding(&air7::TouchBinding {
                        ear: 1,
                        button: 1,
                        gesture: *g as u8,
                        function: **f as u8,
                    })
                })
                .map(|f| *f as u8)
                .collect::<Vec<_>>();
            ((*g as u8).to_string(), serde_json::json!(valid))
        })
        .collect();
    serde_json::json!({
        "id":air7::DRIVER_ID,"name":"realme Buds Air7",
        "name_aliases":["realme Buds Air7","realme Buds Air 7"],
        "components":[{"id":air7::Component::Left as u8,"name":"LEFT","icon":"earbud_left"},{"id":air7::Component::Right as u8,"name":"RIGHT","icon":"earbud_right"},{"id":air7::Component::Case as u8,"name":"CASE","icon":"case"}],
        "service_uuid_be":air7::CONTROL_SERVICE.0,
        "noise":{
            "off":N::Off as u8,"transparency":N::Transparency as u8,"default_anc":A::Moderate.mode() as u8,"minimum_worn":1,
            "levels":([A::Mild,A::Moderate,A::Maximum,A::Adaptive].iter().map(|l|serde_json::json!({"id":l.mode() as u8,"wire_name":l.mode(),"name":l.name()})).collect::<Vec<_>>()),
            "modes":([N::Off,N::Transparency].iter().map(|n|serde_json::json!({"id":*n as u8,"wire_name":n})).collect::<Vec<_>>()),
            "wear_rejection":{"status":14,"noise_command":air7::commands::SET_NOISE,"fit_command":air7::commands::FIT_TEST}
        },
        "eq":{"frequencies":eq.bands.iter().map(|b|b.frequency).collect::<Vec<_>>(),"min":eq.min,"max":eq.max,"name_bytes_max":u8::MAX,
            "presets":(0..=3).map(air7::preset_identifier).collect::<Vec<_>>()},
        "dynamic_bands":(1..=3).map(air7::band_identifier).collect::<Vec<_>>(),
        "features":[
            feature("auto_play",F::AutoPlayPause,"Auto play/pause","Pause when an earbud is removed; resume when it is worn again.","gestures"),
            feature("game",F::GameMode,"Game mode","Prefer lower latency for games.","sound"),
            feature("auto_answer",F::AutoAnswer,"Auto-answer calls","Answer a call by taking an earbud from the case and wearing it.","gestures"),
            {"key":"voices","id":9,"name":"Enhance voices","detail":"Diminish ambient sound and enhance voices.","group":"transparency","experimental":true,
                "experimental_reason":"The SDK labels flag 9 as voice enhancement, but its mapping to this model's Enhance voices control is not confirmed. Apply only if you intend to test this experimental preference."},
            feature("dual_device",F::DualDevice,"Dual-device connection","Keep two audio devices connected.","device"),
            feature("hd_audio",F::HighDefinitionSound,"High-definition sound · LHDC 5.0","High-resolution audio requires a compatible phone and codec.","sound"),
            feature("wind",F::WindNoiseReduction,"Wind-noise reduction","When wind picks up, wind reduction may reduce the transparency effect to suppress wind noise.","noise"),
            feature("spatial",F::SpatialAudio,"Spatial Audio","Spatial rendering for compatible music, video and games. Not head tracking.","sound"),
            feature("dynamic",F::DynamicAudio,"Dynamic audio","Adjust Low, Mid and High sound processing.","sound"),
            feature("phone_action",F::FindPhoneGesture,"Four-tap phone action","Four continuous taps on either earbud send a request to this phone.","host")
        ],
        "firmware_kind":2,
        "gestures":{
            "button_ids":[1],"cycle_binding":{"gesture":G::Hold as u8,"function":T::NoiseControl as u8},
            "rows":[{"id":G::DoubleTap as u8,"name":"Double tap","count":2},{"id":G::TripleTap as u8,"name":"Triple tap","count":3},{"id":G::Hold as u8,"name":"Touch and hold","hold":true}],
            "functions":([T::Disabled,T::PlayPause,T::VoiceAssistant,T::PreviousTrack,T::NextTrack,T::NoiseControl,T::VolumeUp,T::VolumeDown,T::GameMode].iter().copied().map(function).collect::<Vec<_>>()),
            "options":choices,"standard":standard,
            "cycles":([C::OffTransparency,C::OffCancellation,C::CancellationTransparency,C::All].iter().map(|c|serde_json::json!({"id":*c as u8,"wire_name":c,"name":air7::cycle_identifier(*c as u8).name})).collect::<Vec<_>>())
        },
        "multipoint":{"states":(0..=2).map(air7::multipoint_state_identifier).collect::<Vec<_>>(),
            "connected":2,"disconnected":0,"connect_action":1,"disconnect_action":0,"remove_action":2},
        "tools":{"fit_test":true,"fit_minimum_worn":2,"find_earbuds":false,"volume_enhancer":true,"multipoint":true},
        "host_events":{"start":5,"stop":6}
    })
}
