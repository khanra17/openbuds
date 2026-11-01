// SPDX-License-Identifier: GPL-3.0-or-later
//! One registration per model. Transport metadata, UI capabilities and protocol
//! factory are selected together; unsupported models never fall back to Air7.
pub use crate::drivers::air7::Air7;
use crate::runtime::{self as error, Failure};
use realme_buds::{Transport, transport::ServiceUuid};
use std::{any::Any, sync::atomic::AtomicBool, time::Duration};

pub trait Driver: Send {
    fn initialize(&mut self) -> Result<(), Failure>;
    fn set_timeout(&mut self, duration: Duration) -> Result<(), Failure>;
    fn call(&mut self, request: &[u8], cancel: &AtomicBool) -> Result<Vec<u8>, Failure>;
    fn as_any_mut(&mut self) -> &mut dyn Any;
}
pub struct Registration {
    pub id: &'static str,
    pub service: ServiceUuid,
    pub profile: fn() -> serde_json::Value,
    pub create: fn(Box<dyn Transport>) -> Box<dyn Driver>,
}
pub use crate::drivers::REGISTRY;
pub fn registration(id: &str) -> Result<&'static Registration, Failure> {
    REGISTRY.iter().find(|r| r.id == id).ok_or_else(|| {
        Failure::new(
            error::UNKNOWN_DRIVER,
            "Unknown model; no protocol fallback is permitted",
        )
    })
}
pub fn catalog() -> serde_json::Value {
    let metadata: serde_json::Map<String, serde_json::Value> = REGISTRY
        .iter()
        .map(|r| {
            (
                r.id.into(),
                serde_json::json!({"kind":"rfcomm","service_uuid_be":r.service.0}),
            )
        })
        .collect();
    serde_json::json!({
        "abi_version":1,"sdk_version":env!("CARGO_PKG_VERSION"),
        "drivers":REGISTRY.iter().map(|r|r.id).collect::<Vec<_>>(),
        "connection_metadata":metadata,
        "profiles":REGISTRY.iter().map(|r|(r.profile)()).collect::<Vec<_>>()
    })
}
pub fn validate_model(id: &str) -> Result<(), Failure> {
    registration(id).map(|_| ())
}
#[cfg(windows)]
pub fn windows_service(id: &str) -> Result<ServiceUuid, Failure> {
    Ok(registration(id)?.service)
}
pub fn create(id: &str, transport: Box<dyn Transport>) -> Result<Box<dyn Driver>, Failure> {
    Ok((registration(id)?.create)(transport))
}
pub fn initialized_air7(driver: &mut dyn Driver) -> Result<&mut Air7, Failure> {
    let d = driver.as_any_mut().downcast_mut::<Air7>().ok_or_else(|| {
        Failure::new(
            error::UNKNOWN_DRIVER,
            "This legacy typed function belongs to the Air7 driver",
        )
    })?;
    if !d.initialized {
        return Err(Failure::new(
            error::NOT_INITIALIZED,
            "Initialize the selected driver first",
        ));
    }
    Ok(d)
}
