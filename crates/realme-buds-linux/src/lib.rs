#![cfg(target_os = "linux")]
// SPDX-License-Identifier: GPL-3.0-or-later
//! BlueZ resolves the control UUID through SDP and hands us the RFCOMM stream.
use bluer::{
    Session,
    rfcomm::{Profile, ProfileHandle, Role, Stream},
};
use futures_util::StreamExt;
use realme_buds::{
    devices::air7::{self, Result},
    transport::Transport,
};
use std::{
    io::{self, Read, Write},
    time::Duration,
};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    runtime::{Builder, Runtime},
};
fn runtime() -> io::Result<Runtime> {
    Builder::new_current_thread().enable_all().build()
}
fn error(error: impl std::fmt::Display) -> io::Error {
    io::Error::other(error.to_string())
}
/// Legacy name/address projection. New callers can also obtain passive connection status.
pub fn paired_devices() -> Result<Vec<(String, String)>> {
    Ok(paired_devices_with_status()?
        .into_iter()
        .map(|(name, address, _)| (name, address))
        .collect())
}
pub fn paired_devices_with_status() -> io::Result<Vec<(String, String, bool)>> {
    let rt = runtime()?;
    rt.block_on(async {
        let session = Session::new().await.map_err(error)?;
        let mut devices = Vec::new();
        for name in session.adapter_names().await.map_err(error)? {
            let adapter = session.adapter(&name).map_err(error)?;
            for address in adapter.device_addresses().await.map_err(error)? {
                let device = adapter.device(address).map_err(error)?;
                if device.is_paired().await.map_err(error)? {
                    devices.push((
                        device
                            .name()
                            .await
                            .map_err(error)?
                            .unwrap_or_else(|| "Unnamed device".into()),
                        address.to_string(),
                        device.is_connected().await.map_err(error)?,
                    ));
                }
            }
        }
        Ok::<_, io::Error>(devices)
    })
}
pub fn connect(address: &str) -> Result<Box<dyn Transport>> {
    Ok(connect_service(address, air7::CONTROL_SERVICE)?)
}
pub fn connect_service(
    address: &str,
    service: realme_buds::transport::ServiceUuid,
) -> io::Result<Box<dyn Transport>> {
    let rt = runtime()?;
    let target = address.parse::<bluer::Address>().map_err(error)?;
    let (session, profile, stream) = rt.block_on(async {
        tokio::time::timeout(Duration::from_secs(20), async {
            let session = Session::new().await.map_err(error)?;
            let mut device = None;
            for name in session.adapter_names().await.map_err(error)? {
                let adapter = session.adapter(&name).map_err(error)?;
                if adapter
                    .device_addresses()
                    .await
                    .map_err(error)?
                    .contains(&target)
                {
                    device = Some(adapter.device(target).map_err(error)?);
                    break;
                }
            }
            let device = device
                .ok_or_else(|| error("Pair the device in system Bluetooth settings first"))?;
            let uuid = bluer::Uuid::from_bytes(service.0);
            let mut profile = session
                .register_profile(Profile {
                    uuid,
                    name: Some("OpenBuds device control".into()),
                    role: Some(Role::Client),
                    require_authentication: Some(true),
                    require_authorization: Some(false),
                    auto_connect: Some(false),
                    ..Default::default()
                })
                .await
                .map_err(error)?;
            let connection = async { device.connect_profile(&uuid).await.map_err(error) };
            let acceptance = async {
                loop {
                    let request = profile
                        .next()
                        .await
                        .ok_or_else(|| error("BlueZ profile closed"))?;
                    if request.device() == target {
                        break request.accept().map_err(error);
                    }
                    request.reject(bluer::rfcomm::ReqError::Rejected);
                }
            };
            let (_, stream) = tokio::try_join!(connection, acceptance)?;
            Ok::<_, io::Error>((session, profile, stream))
        })
        .await
        .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "Bluetooth connect deadline"))?
    })?;
    Ok(Box::new(LinuxStream {
        rt,
        _session: session,
        _profile: profile,
        stream,
        timeout: Duration::from_secs(12),
    }))
}
struct LinuxStream {
    rt: Runtime,
    _session: Session,
    _profile: ProfileHandle,
    stream: Stream,
    timeout: Duration,
}
impl Read for LinuxStream {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        self.rt.block_on(async {
            tokio::time::timeout(self.timeout, self.stream.read(buf))
                .await
                .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "Bluetooth read deadline"))?
        })
    }
}
impl Write for LinuxStream {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        self.rt.block_on(async {
            tokio::time::timeout(Duration::from_secs(12), self.stream.write(buf))
                .await
                .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "Bluetooth write deadline"))?
        })
    }
    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}
impl Transport for LinuxStream {
    fn set_read_timeout(&mut self, timeout: Duration) -> io::Result<()> {
        self.timeout = timeout;
        Ok(())
    }
}
