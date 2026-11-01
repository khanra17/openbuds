// SPDX-License-Identifier: GPL-3.0-or-later
//! Safe, shared entry points for the CLI and JVM bridge. C ABI remains unchanged.
pub use crate::runtime::Failure;
use crate::{driver, runtime};
use realme_buds::{Transport, devices::air7};
use std::{sync::atomic::Ordering, time::Duration};
#[derive(serde::Serialize)]
pub struct PairedDevice {
    pub name: String,
    pub address: String,
    pub connected: bool,
}
pub fn paired_devices() -> Result<Vec<PairedDevice>, Failure> {
    #[cfg(windows)]
    {
        Ok(realme_buds_windows::paired_devices()?
            .into_iter()
            .map(|d| PairedDevice {
                name: d.name,
                address: d.address,
                connected: d.connected,
            })
            .collect())
    }
    #[cfg(target_os = "linux")]
    {
        Ok(realme_buds_linux::paired_devices_with_status()?
            .into_iter()
            .map(|(name, address, connected)| PairedDevice {
                name,
                address,
                connected,
            })
            .collect())
    }
    #[cfg(not(any(windows, target_os = "linux")))]
    Err(Failure::new(
        runtime::UNSUPPORTED,
        "Use Android's paired-device picker; macOS Bluetooth is not implemented",
    ))
}
pub fn open(address: &str) -> Result<u64, Failure> {
    open_model(air7::DRIVER_ID, address)
}
pub fn open_model(model: &str, address: &str) -> Result<u64, Failure> {
    let registration = driver::registration(model)?;
    #[cfg(windows)]
    {
        open_model_transport(
            model,
            Box::new(realme_buds_windows::RfcommStream::connect_service(
                address,
                registration.service,
            )?),
        )
    }
    #[cfg(target_os = "linux")]
    {
        open_model_transport(
            model,
            realme_buds_linux::connect_service(address, registration.service)?,
        )
    }
    #[cfg(not(any(windows, target_os = "linux")))]
    {
        let _ = (address, registration);
        Err(Failure::new(
            runtime::UNSUPPORTED,
            "Native Bluetooth is unavailable on this host",
        ))
    }
}
pub fn open_transport(transport: Box<dyn Transport>) -> Result<u64, Failure> {
    open_model_transport(air7::DRIVER_ID, transport)
}
pub fn open_model_transport(model: &str, transport: Box<dyn Transport>) -> Result<u64, Failure> {
    runtime::register(driver::create(model, transport)?)
}
pub fn catalog() -> serde_json::Value {
    driver::catalog()
}
pub fn initialize(handle: u64) -> Result<(), Failure> {
    runtime::with_driver(handle, |d, _| d.initialize())
}
pub fn timeout(handle: u64, ms: u32) -> Result<(), Failure> {
    runtime::with_driver(handle, |d, _| {
        d.set_timeout(Duration::from_millis(ms.into()))
    })
}
pub fn call(handle: u64, request: &[u8]) -> Result<Vec<u8>, Failure> {
    if request.len() > 1024 * 1024 {
        return Err(Failure::new(
            runtime::INVALID_ARGUMENT,
            "Request exceeds one MiB",
        ));
    }
    runtime::with_driver(handle, |d, c| d.call(request, c))
}
pub fn cancel(handle: u64) -> Result<(), Failure> {
    runtime::get(handle)?.cancel.store(true, Ordering::Relaxed);
    Ok(())
}
pub fn close(handle: u64) -> Result<(), Failure> {
    runtime::close(handle)
}
