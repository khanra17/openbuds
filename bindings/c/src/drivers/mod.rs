// SPDX-License-Identifier: GPL-3.0-or-later
//! Register a model once: this declares its module and pairs its factory with
//! its capabilities and transport metadata. No application or platform switch.
macro_rules! models {
    ($($module:ident => { id: $id:expr, service: $service:expr }),* $(,)?) => {
        $(pub mod $module;)*
        pub static REGISTRY: &[crate::driver::Registration] = &[
            $(crate::driver::Registration { id:$id, service:$service, profile:$module::profile, create:$module::create }),*
        ];
    };
}
models! {
    air7 => { id:realme_buds::devices::air7::DRIVER_ID, service:realme_buds::devices::air7::CONTROL_SERVICE },
}
