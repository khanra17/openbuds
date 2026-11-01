// SPDX-License-Identifier: GPL-3.0-or-later
mod args;
mod commands;
mod eq;
mod rpc;
use args::{Args, Command};
use clap::Parser;
use realme_buds::{devices::air7::Client, transport::Transport};
use std::{sync::atomic::AtomicBool, time::Duration};
pub(crate) static CANCELLED: AtomicBool = AtomicBool::new(false);
pub(crate) static SDK_SESSION: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

fn connect(
    address: &str,
    channel: Option<u32>,
    service: realme_buds::transport::ServiceUuid,
) -> Result<Box<dyn Transport>, Box<dyn std::error::Error>> {
    #[cfg(windows)]
    {
        use realme_buds_windows::RfcommStream;
        Ok(Box::new(if let Some(channel) = channel {
            RfcommStream::connect_channel(address, channel)?
        } else {
            RfcommStream::connect_service(address, service)?
        }))
    }
    #[cfg(target_os = "linux")]
    {
        if channel.is_some() {
            return Err("--channel is a Windows-only diagnostic override".into());
        }
        Ok(realme_buds_linux::connect_service(address, service)?)
    }
    #[cfg(not(any(windows, target_os = "linux")))]
    {
        let _ = (address, channel, service);
        Err("Direct Bluetooth is available on Windows and Linux; macOS transport is not implemented".into())
    }
}
fn run(args: Args) -> Result<(), Box<dyn std::error::Error>> {
    commands::compact(args.json);
    if matches!(args.command, Command::Models) {
        commands::print_json(&openbuds::session::catalog())?;
        return Ok(());
    }
    if matches!(args.command, Command::Devices) {
        commands::print_json(&openbuds::session::paired_devices()?)?;
        return Ok(());
    }
    if !(1..=120).contains(&args.timeout) {
        return Err("--timeout must be 1..120 seconds".into());
    }
    let catalog = openbuds::session::catalog();
    let profiles = catalog["profiles"]
        .as_array()
        .ok_or("SDK catalog has no profiles")?;
    let profile = if let Some(model) = args.model.as_deref() {
        profiles
            .iter()
            .find(|p| p["id"].as_str() == Some(model))
            .ok_or("Unknown model; use buds models")?
    } else if profiles.len() == 1 {
        &profiles[0]
    } else {
        return Err("Choose a driver with --model; use buds models".into());
    };
    let model = profile["id"].as_str().ok_or("Driver has no ID")?;
    let address = if let Some(address) = args.address {
        address
    } else {
        let candidates: Vec<_> = openbuds::session::paired_devices()?
            .into_iter()
            .filter(|d| {
                profile["name_aliases"].as_array().is_some_and(|aliases| {
                    aliases
                        .iter()
                        .any(|a| a.as_str().is_some_and(|a| a.eq_ignore_ascii_case(&d.name)))
                })
            })
            .collect();
        if candidates.len() != 1 {
            return Err(
                "Choose a paired device with --address; use buds devices and buds models".into(),
            );
        }
        candidates[0].address.clone()
    };
    let bytes: Vec<u8> = profile["service_uuid_be"]
        .as_array()
        .ok_or("Driver has no Bluetooth service")?
        .iter()
        .map(|v| {
            v.as_u64()
                .and_then(|n| u8::try_from(n).ok())
                .ok_or("Invalid service UUID")
        })
        .collect::<Result<_, _>>()?;
    let service = realme_buds::transport::ServiceUuid(
        bytes
            .try_into()
            .map_err(|_| "Service UUID must have 16 bytes")?,
    );
    if !args.json {
        eprintln!(
            "Connecting to {}…",
            profile["name"].as_str().unwrap_or("earbuds")
        );
    }
    let stream = connect(&address, args.channel, service)?;
    let stream: Box<dyn Transport> = if let Some(path) = args.trace {
        Box::new(realme_buds::transport::trace::Traced::new(
            stream,
            std::fs::File::create(path)?,
        ))
    } else {
        stream
    };
    if matches!(&args.command, Command::Call { .. } | Command::Rpc) {
        let handle = openbuds::session::open_model_transport(model, stream)?;
        SDK_SESSION.store(handle, std::sync::atomic::Ordering::Relaxed);
        let result = (|| -> Result<(), Box<dyn std::error::Error>> {
            openbuds::session::timeout(handle, (args.timeout * 1000) as u32)?;
            openbuds::session::initialize(handle)?;
            match args.command {
                Command::Call { request } => {
                    let response: serde_json::Value = serde_json::from_slice(
                        &openbuds::session::call(handle, request.as_bytes())?,
                    )?;
                    commands::print_json(&response)?;
                }
                Command::Rpc => rpc::run(handle)?,
                _ => unreachable!(),
            }
            Ok(())
        })();
        SDK_SESSION.store(0, std::sync::atomic::Ordering::Relaxed);
        let closed = openbuds::session::close(handle);
        result?;
        closed?;
        return Ok(());
    }
    // Compatibility shortcuts are explicitly model-specific; never apply their
    // typed client to another driver. Every driver can use call/rpc above.
    if model != realme_buds::devices::air7::DRIVER_ID {
        return Err("This model uses the SDK call/rpc interface; legacy named shortcuts are not its protocol".into());
    }
    let mut client = Client::new(stream);
    client.set_timeout(Duration::from_secs(args.timeout))?;
    commands::execute(&mut client, args.command, args.raw)
}
fn main() {
    let json = std::env::args().any(|a| a == "--json");
    let args = match Args::try_parse() {
        Ok(args) => args,
        Err(error) => {
            if json && error.exit_code() != 0 {
                println!(
                    "{}",
                    serde_json::json!({"schema_version":1,"ok":false,"error":{"code":"usage","message":error.to_string()}})
                );
                std::process::exit(2);
            }
            error.exit()
        }
    };
    if let Err(error) = ctrlc::set_handler(|| {
        CANCELLED.store(true, std::sync::atomic::Ordering::Relaxed);
        let handle = SDK_SESSION.load(std::sync::atomic::Ordering::Relaxed);
        if handle != 0 {
            let _ = openbuds::session::cancel(handle);
        }
    }) {
        eprintln!("Unable to install cancellation handler: {error}");
        std::process::exit(1);
    }
    if let Err(error) = run(args) {
        if json {
            println!(
                "{}",
                serde_json::json!({"schema_version":1,"ok":false,"error":{"code":"operation_failed","message":error.to_string()}})
            );
        } else {
            eprintln!("Error: {error}");
        }
        std::process::exit(1);
    }
}
