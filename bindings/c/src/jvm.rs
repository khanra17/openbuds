// SPDX-License-Identifier: GPL-3.0-or-later
//! JNI shares the C SDK's session registry/JSON dispatcher; no second protocol implementation.
use crate::{
    runtime::{self, Failure},
    session,
};
use jni::{
    Env, EnvUnowned, JavaVM,
    errors::ThrowRuntimeExAndDefault,
    jni_sig, jni_str,
    objects::{JByteArray, JObject, JString, JValue},
    refs::Global,
    sys::{jlong, jstring},
};
use realme_buds::Transport;
use std::{
    io::{self, Read, Write},
    time::Duration,
};
impl From<jni::errors::Error> for Failure {
    fn from(error: jni::errors::Error) -> Self {
        Self::new(runtime::IO, error.to_string())
    }
}
fn failure(error: impl std::fmt::Display) -> Failure {
    Failure::new(runtime::IO, error.to_string())
}
fn invoke<'local, T: Default>(
    env: &mut EnvUnowned<'local>,
    f: impl FnOnce(&mut Env<'local>) -> Result<T, Failure>,
) -> T {
    // JNI 0.22 contains panics and maps Rust failures to Java exceptions at the boundary.
    env.with_env(f).resolve::<ThrowRuntimeExAndDefault>()
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_openbuds_core_NativeBridge_devices(
    mut env: EnvUnowned<'_>,
    _: JObject<'_>,
) -> jstring {
    invoke(&mut env, |env| {
        let value = serde_json::to_string(&session::paired_devices()?).map_err(failure)?;
        Ok(JString::from_str(env, value)?.into_raw())
    })
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_openbuds_core_NativeBridge_catalog(
    mut env: EnvUnowned<'_>,
    _: JObject<'_>,
) -> jstring {
    invoke(&mut env, |env| {
        Ok(JString::from_str(env, session::catalog().to_string())?.into_raw())
    })
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_openbuds_core_NativeBridge_open(
    mut env: EnvUnowned<'_>,
    _: JObject<'_>,
    transport: JObject<'_>,
    address: JString<'_>,
    model: JString<'_>,
) -> jlong {
    invoke(&mut env, |env| {
        let model = model.try_to_string(env)?;
        let handle = if transport.is_null() {
            session::open_model(&model, &address.try_to_string(env)?)?
        } else {
            session::open_model_transport(
                &model,
                Box::new(JvmStream {
                    object: env.new_global_ref(transport)?,
                    timeout: Duration::from_secs(12),
                }),
            )?
        };
        Ok(handle as jlong)
    })
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_openbuds_core_NativeBridge_timeout(
    mut env: EnvUnowned<'_>,
    _: JObject<'_>,
    handle: jlong,
    milliseconds: i32,
) {
    invoke(&mut env, |_| {
        if !(1..=120_000).contains(&milliseconds) {
            return Err(Failure::new(
                runtime::INVALID_ARGUMENT,
                "Timeout must be 1..120000 milliseconds",
            ));
        }
        session::timeout(handle as u64, milliseconds as u32)
    });
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_openbuds_core_NativeBridge_request(
    mut env: EnvUnowned<'_>,
    _: JObject<'_>,
    handle: jlong,
    request: JString<'_>,
) -> jstring {
    invoke(&mut env, |env| {
        let request = request.try_to_string(env)?;
        // Keep the C SDK's structured failure at the UI boundary. Throwing only
        // Display text discarded command/status and leaked “Rust error” into the UI.
        let response = match session::call(handle as u64, request.as_bytes()) {
            Ok(data) => String::from_utf8(data).map_err(failure)?,
            Err(error) => request_failure(error),
        };
        Ok(JString::from_str(env, response)?.into_raw())
    })
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_openbuds_core_NativeBridge_cancel(
    mut env: EnvUnowned<'_>,
    _: JObject<'_>,
    handle: jlong,
) {
    invoke(&mut env, |_| session::cancel(handle as u64));
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_openbuds_core_NativeBridge_close(
    mut env: EnvUnowned<'_>,
    _: JObject<'_>,
    handle: jlong,
) {
    invoke(&mut env, |_| session::close(handle as u64));
}
/// Keep native decorations (Snap, system menu, resizing) while matching the app palette.
/// This JNI-only host helper never opens Bluetooth or changes an earbud setting.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_openbuds_core_NativeBridge_styleWindow(
    _env: EnvUnowned<'_>,
    _: JObject<'_>,
    handle: jlong,
) {
    #[cfg(windows)]
    unsafe {
        use windows_sys::Win32::{
            Graphics::Dwm::{
                DWMWA_CAPTION_COLOR, DWMWA_TEXT_COLOR, DWMWA_USE_IMMERSIVE_DARK_MODE,
                DwmSetWindowAttribute,
            },
            UI::WindowsAndMessaging::{GA_ROOT, GetAncestor},
        };
        // Skiko's handle can identify the child rendering surface; DWM styles the root frame.
        let root = GetAncestor(handle as _, GA_ROOT);
        if !root.is_null() {
            let dark: i32 = 1;
            let caption: u32 = 0x00090a08; // COLORREF: #080A09
            let text: u32 = 0x00969c9a; // COLORREF: muted #9A9C96
            DwmSetWindowAttribute(
                root,
                DWMWA_USE_IMMERSIVE_DARK_MODE as u32,
                (&dark as *const i32).cast(),
                4,
            );
            // Windows 11 supports exact colors. Windows 10 falls back to immersive dark.
            DwmSetWindowAttribute(
                root,
                DWMWA_CAPTION_COLOR as u32,
                (&caption as *const u32).cast(),
                4,
            );
            DwmSetWindowAttribute(
                root,
                DWMWA_TEXT_COLOR as u32,
                (&text as *const u32).cast(),
                4,
            );
        }
    }
    #[cfg(not(windows))]
    let _ = handle;
}
fn request_failure(error: Failure) -> String {
    serde_json::json!({
        "error": {
            "status": error.info.status,
            "command": error.info.command,
            "device_status": (error.info.has_device_status != 0).then_some(error.info.device_status),
            "message": error.message,
        }
    })
    .to_string()
}

struct JvmStream {
    object: Global<JObject<'static>>,
    timeout: Duration,
}
impl JvmStream {
    fn call<T>(
        &self,
        f: impl FnOnce(&mut Env<'_>, &JObject<'_>) -> jni::errors::Result<T>,
    ) -> io::Result<T> {
        // Permanent attachment is reused, and each closure has a bounded JNI reference frame.
        JavaVM::singleton()
            .map_err(io::Error::other)?
            .attach_current_thread(|env| f(env, self.object.as_ref()))
            .map_err(io::Error::other)
    }
}
impl Read for JvmStream {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        if buf.is_empty() {
            return Ok(0);
        }
        let (n, bytes) = self.call(|env, object| {
            let bytes = JByteArray::new(env, buf.len().min(4096))?;
            let n = env
                .call_method(
                    object,
                    jni_str!("read"),
                    jni_sig!((byte[], int) -> int),
                    &[
                        JValue::Object(bytes.as_ref()),
                        JValue::Int(self.timeout.as_millis().clamp(1, i32::MAX as u128) as i32),
                    ],
                )?
                .i()?;
            Ok((n, env.convert_byte_array(bytes)?))
        })?;
        if n < 0 {
            return Err(io::ErrorKind::TimedOut.into());
        }
        let n = n as usize;
        if n > bytes.len() || n > buf.len() {
            return Err(io::Error::other("Invalid native transport read count"));
        }
        buf[..n].copy_from_slice(&bytes[..n]);
        Ok(n)
    }
}
impl Write for JvmStream {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        self.call(|env, object| {
            let bytes = env.byte_array_from_slice(buf)?;
            env.call_method(
                object,
                jni_str!("write"),
                jni_sig!((byte[]) -> void),
                &[JValue::Object(bytes.as_ref())],
            )?;
            Ok(())
        })?;
        Ok(buf.len())
    }
    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}
impl Transport for JvmStream {
    fn set_read_timeout(&mut self, timeout: Duration) -> io::Result<()> {
        self.timeout = timeout;
        Ok(())
    }
}
impl Drop for JvmStream {
    fn drop(&mut self) {
        let _ = self.call(|env, object| {
            env.call_method(object, jni_str!("close"), jni_sig!(() -> void), &[])?;
            Ok(())
        });
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn jni_keeps_sdk_rejection_fields_instead_of_flattening_them() {
        let failure = realme_buds::devices::air7::Error::Rejected {
            command: 0x0404,
            status: 14,
        };
        let response: serde_json::Value =
            serde_json::from_str(&request_failure(failure.into())).unwrap();
        assert_eq!(response["error"]["status"], runtime::REJECTED);
        assert_eq!(response["error"]["command"], 0x0404);
        assert_eq!(response["error"]["device_status"], 14);
        let timeout: serde_json::Value =
            serde_json::from_str(&request_failure(Failure::new(runtime::TIMEOUT, "timeout")))
                .unwrap();
        assert!(timeout["error"]["device_status"].is_null());
    }
}
