// SPDX-License-Identifier: GPL-3.0-or-later
use openbuds::*;
#[test]
fn registry_profiles_transport_and_capabilities_agree() {
    let mut buffer = BufferV1::default();
    // SAFETY: writable output storage, then a borrowed owned buffer until release.
    assert_eq!(unsafe { ob_v1_driver_catalog(&mut buffer) }, 0);
    let data: serde_json::Value =
        serde_json::from_slice(unsafe { std::slice::from_raw_parts(buffer.data, buffer.len) })
            .unwrap();
    assert_eq!(unsafe { ob_v1_buffer_release(&mut buffer) }, 0);
    let profiles = data["profiles"].as_array().unwrap();
    assert_eq!(profiles.len(), data["drivers"].as_array().unwrap().len());
    for profile in profiles {
        let id = profile["id"].as_str().unwrap();
        assert_eq!(
            profile["service_uuid_be"],
            data["connection_metadata"][id]["service_uuid_be"]
        );
        assert!(
            data["drivers"]
                .as_array()
                .unwrap()
                .iter()
                .any(|v| v.as_str() == Some(id))
        );
    }
    let p = profiles
        .iter()
        .find(|p| p["id"] == realme_buds::devices::air7::DRIVER_ID)
        .unwrap();
    assert_eq!(p["tools"]["find_earbuds"], false);
    let bands = realme_buds::devices::air7::CustomEq::air7(0, "", [0; 6])
        .bands
        .into_iter()
        .map(|b| b.frequency)
        .collect::<Vec<_>>();
    assert_eq!(p["eq"]["frequencies"], serde_json::json!(bands));
    assert_eq!(
        p["noise"]["wear_rejection"]["noise_command"],
        realme_buds::devices::air7::commands::SET_NOISE
    );
}
