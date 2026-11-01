// SPDX-License-Identifier: GPL-3.0-or-later
//! Persistent, bounded NDJSON transport for applications which do not link an SDK.
use openbuds::session::Failure;
use serde_json::{Value, json};
use std::{
    io::{self, BufRead, Read, Write},
    sync::{
        Arc,
        atomic::{AtomicU64, Ordering},
        mpsc,
    },
};
const MAX_FRAME: usize = 1024 * 1024;
fn emit(value: Value) -> io::Result<()> {
    let mut output = io::stdout().lock();
    serde_json::to_writer(&mut output, &value)?;
    output.write_all(b"\n")?;
    output.flush()
}
pub fn run(handle: u64) -> Result<(), Box<dyn std::error::Error>> {
    let (tx, rx) = mpsc::sync_channel(16);
    let cancelled = Arc::new(AtomicU64::new(0));
    let cancel_reader = cancelled.clone();
    std::thread::spawn(move || {
        let mut input = io::stdin().lock();
        loop {
            let mut line = Vec::new();
            let count = match (&mut input)
                .take((MAX_FRAME + 1) as u64)
                .read_until(b'\n', &mut line)
            {
                Ok(n) => n,
                Err(_) => break,
            };
            if count == 0 {
                break;
            }
            if line.len() > MAX_FRAME {
                let _ = emit(
                    json!({"id":null,"schema_version":1,"ok":false,"error":{"code":"frame_too_large","message":"One MiB maximum"}}),
                );
                break;
            }
            let frame: Value = match serde_json::from_slice(&line) {
                Ok(v) => v,
                Err(e) => {
                    let _ = emit(
                        json!({"id":null,"schema_version":1,"ok":false,"error":{"code":"invalid_json","message":e.to_string()}}),
                    );
                    continue;
                }
            };
            if frame
                .get("request")
                .and_then(|r| r.get("op"))
                .and_then(Value::as_str)
                == Some("cancel")
            {
                cancel_reader.fetch_add(1, Ordering::Relaxed);
                let _ = openbuds::session::cancel(handle);
                let _ = emit(
                    json!({"id":frame.get("id"),"schema_version":1,"ok":true,"data":{"cancel_requested":true}}),
                );
            } else {
                let epoch = cancel_reader.load(Ordering::Relaxed);
                match tx.try_send((frame, epoch)) {
                    Ok(()) => {}
                    Err(mpsc::TrySendError::Full((frame, _))) => {
                        let _ = emit(
                            json!({"id":frame.get("id"),"schema_version":1,"ok":false,"error":{"code":"busy","message":"At most 16 pending requests"}}),
                        );
                    }
                    Err(mpsc::TrySendError::Disconnected(_)) => break,
                }
            }
        }
    });
    loop {
        if crate::CANCELLED.load(Ordering::Relaxed) {
            break;
        }
        let (frame, epoch) = match rx.recv_timeout(std::time::Duration::from_millis(200)) {
            Ok(frame) => frame,
            Err(mpsc::RecvTimeoutError::Timeout) => continue,
            Err(mpsc::RecvTimeoutError::Disconnected) => break,
        };
        let id = frame.get("id").cloned().unwrap_or(Value::Null);
        let result: Result<Value, Failure> = (|| {
            if cancelled.load(Ordering::Relaxed) != epoch {
                return Err(Failure::new(9, "Request cancelled"));
            }
            let request = frame
                .get("request")
                .ok_or_else(|| Failure::new(1, "Expected {id,request:{op,...}}"))?;
            let request =
                serde_json::to_vec(request).map_err(|e| Failure::new(1, e.to_string()))?;
            let response = openbuds::session::call(handle, &request)?;
            let response: Value =
                serde_json::from_slice(&response).map_err(|e| Failure::new(11, e.to_string()))?;
            Ok(response.get("data").cloned().unwrap_or(Value::Null))
        })();
        match result {
            Ok(data) => emit(json!({"id":id,"schema_version":1,"ok":true,"data":data}))?,
            Err(error) => emit(json!({"id":id,"schema_version":1,"ok":false,"error":{
                "code":match error.info.status {1=>"invalid_request",4=>"timeout",6=>"device_rejected",9=>"cancelled",_=>"transport_or_protocol"},
                "status":error.info.status,"command":error.info.command,
                "device_status":if error.info.has_device_status!=0 {Some(error.info.device_status)} else {None},
                "message":error.message
            }}))?,
        }
        if crate::CANCELLED.load(Ordering::Relaxed) {
            break;
        }
    }
    Ok(())
}
