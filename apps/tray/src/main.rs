// SPDX-License-Identifier: GPL-3.0-or-later
#![cfg_attr(windows, windows_subsystem = "windows")]
#[cfg(windows)]
mod host {
    use std::{
        mem::size_of,
        process::Child,
        ptr::{null, null_mut},
        sync::{Mutex, OnceLock},
    };
    use windows_sys::Win32::{
        Foundation::HWND,
        System::LibraryLoader::GetModuleHandleW,
        UI::{Shell::*, WindowsAndMessaging::*},
    };
    static UI: Mutex<Option<Child>> = Mutex::new(None);
    const CALLBACK: u32 = WM_APP + 1;
    // Shell headers define NIN_KEYSELECT as NIN_SELECT | NINF_KEY (1).
    const NIN_KEYSELECT: u32 = NIN_SELECT | 1;
    fn wide(s: &str) -> Vec<u16> {
        s.encode_utf16().chain(Some(0)).collect()
    }
    fn taskbar_created() -> u32 {
        static MESSAGE: OnceLock<u32> = OnceLock::new();
        *MESSAGE.get_or_init(|| unsafe { RegisterWindowMessageW(wide("TaskbarCreated").as_ptr()) })
    }
    fn open() {
        unsafe {
            let window = FindWindowW(null(), wide("OpenBuds").as_ptr());
            if !window.is_null() {
                if IsIconic(window) != 0 {
                    ShowWindow(window, SW_RESTORE);
                }
                SetForegroundWindow(window);
                return;
            }
        }
        let mut child = UI.lock().unwrap_or_else(|e| e.into_inner());
        if child
            .as_mut()
            .is_some_and(|process| matches!(process.try_wait(), Ok(None)))
        {
            return;
        }
        let result = std::env::current_exe().and_then(|path| {
            let root = path
                .parent()
                .ok_or_else(|| std::io::Error::other("Missing application directory"))?;
            let nested = root.join("OpenBuds/OpenBuds.exe");
            let launcher = if nested.exists() {
                nested
            } else {
                root.join("OpenBuds.exe")
            };
            std::process::Command::new(launcher).spawn()
        });
        match result {
            Ok(process) => *child = Some(process),
            Err(error) => unsafe {
                MessageBoxW(
                    null_mut(),
                    wide(&format!("Unable to open the interface: {error}")).as_ptr(),
                    wide("OpenBuds").as_ptr(),
                    MB_OK | MB_ICONERROR,
                );
            },
        }
    }
    unsafe fn notification(hwnd: HWND) -> NOTIFYICONDATAW {
        let mut data: NOTIFYICONDATAW = unsafe { std::mem::zeroed() };
        data.cbSize = size_of::<NOTIFYICONDATAW>() as u32;
        data.hWnd = hwnd;
        data.uID = 1;
        data.uFlags = NIF_ICON | NIF_MESSAGE | NIF_TIP | NIF_SHOWTIP;
        data.uCallbackMessage = CALLBACK;
        data.hIcon = unsafe { GetClassLongPtrW(hwnd, GCLP_HICON) as _ };
        let tip = wide("OpenBuds — open earbud controls");
        data.szTip[..tip.len()].copy_from_slice(&tip);
        data
    }
    unsafe fn add_icon(hwnd: HWND) -> bool {
        let mut data = unsafe { notification(hwnd) };
        if unsafe { Shell_NotifyIconW(NIM_ADD, &data) } == 0 {
            return false;
        }
        // Version 4 enables keyboard activation and accessible context-menu events.
        data.Anonymous.uVersion = NOTIFYICON_VERSION_4;
        unsafe { Shell_NotifyIconW(NIM_SETVERSION, &data) };
        true
    }
    unsafe fn menu(hwnd: HWND, anchor: usize) {
        unsafe {
            let menu = CreatePopupMenu();
            if menu.is_null() {
                return;
            }
            AppendMenuW(menu, MF_STRING, 1, wide("Open OpenBuds").as_ptr());
            AppendMenuW(menu, MF_SEPARATOR, 0, null());
            AppendMenuW(menu, MF_STRING, 2, wide("Quit tray").as_ptr());
            // Version 4 supplies an icon-relative anchor, including keyboard invocation.
            let mut point = windows_sys::Win32::Foundation::POINT {
                x: anchor as u16 as i16 as i32,
                y: (anchor >> 16) as u16 as i16 as i32,
            };
            if point.x == -1 && point.y == -1 {
                GetCursorPos(&mut point);
            }
            SetForegroundWindow(hwnd);
            let choice = TrackPopupMenu(
                menu,
                TPM_RETURNCMD | TPM_RIGHTBUTTON,
                point.x,
                point.y,
                0,
                hwnd,
                null(),
            );
            DestroyMenu(menu);
            // Required for the shell to dismiss a subsequent menu on outside click.
            PostMessageW(hwnd, WM_NULL, 0, 0);
            match choice {
                1 => open(),
                2 => {
                    DestroyWindow(hwnd);
                }
                _ => {}
            }
        }
    }
    unsafe extern "system" fn procedure(hwnd: HWND, message: u32, w: usize, l: isize) -> isize {
        unsafe {
            if message == taskbar_created() && message != 0 {
                add_icon(hwnd); // Explorer recreated its notification area; no polling required.
                return 0;
            }
            match message {
                CALLBACK => {
                    match l as u32 & 0xffff {
                        NIN_SELECT | NIN_KEYSELECT => open(),
                        WM_CONTEXTMENU => menu(hwnd, w),
                        _ => {}
                    }
                    0
                }
                WM_DESTROY => {
                    Shell_NotifyIconW(NIM_DELETE, &notification(hwnd));
                    PostQuitMessage(0);
                    0
                }
                _ => DefWindowProcW(hwnd, message, w, l),
            }
        }
    }
    fn icon_pixels(size: usize) -> Vec<u8> {
        let mut pixels = vec![0; size * size * 4];
        for y in 0..size {
            for x in 0..size {
                // Same original ring-and-signal mark as the Android/desktop launcher.
                let px = (x as f32 + 0.5) * 48.0 / size as f32;
                let py = (y as f32 + 0.5) * 48.0 / size as f32;
                let ring = ((px - 24.0).powi(2) + (py - 24.0).powi(2)).sqrt();
                let signal = ((px - 35.0).powi(2) + (py - 13.0).powi(2)).sqrt();
                let color = if signal <= 4.0 {
                    [60, 74, 232, 255]
                } else if (11.5..=16.5).contains(&ring) {
                    [236, 242, 244, 255]
                } else {
                    [9, 10, 8, 255]
                };
                pixels[(y * size + x) * 4..(y * size + x + 1) * 4].copy_from_slice(&color);
            }
        }
        pixels
    }
    pub fn run() {
        unsafe {
            let class = wide("OpenBudsTrayHost");
            let title = wide("OpenBuds tray");
            let existing = FindWindowW(class.as_ptr(), title.as_ptr());
            if !existing.is_null() {
                PostMessageW(existing, CALLBACK, 0, NIN_SELECT as isize);
                return;
            }
            let instance = GetModuleHandleW(null());
            let size = GetSystemMetrics(SM_CXSMICON).max(16) as usize;
            let pixels = icon_pixels(size);
            let mask = vec![0u8; (size.div_ceil(16) * 2) * size];
            let icon = CreateIcon(
                instance,
                size as i32,
                size as i32,
                1,
                32,
                mask.as_ptr(),
                pixels.as_ptr(),
            );
            let definition = WNDCLASSW {
                lpfnWndProc: Some(procedure),
                hInstance: instance,
                hIcon: icon,
                lpszClassName: class.as_ptr(),
                ..std::mem::zeroed()
            };
            RegisterClassW(&definition);
            // A hidden top-level native window receives Explorer restart broadcasts.
            // No JVM, Bluetooth socket, hidden Compose window or periodic timer.
            let hwnd = CreateWindowExW(
                0,
                class.as_ptr(),
                title.as_ptr(),
                0,
                0,
                0,
                0,
                0,
                null_mut(),
                null_mut(),
                instance,
                null(),
            );
            if !hwnd.is_null() {
                if add_icon(hwnd) {
                    let mut message = std::mem::zeroed();
                    while GetMessageW(&mut message, null_mut(), 0, 0) > 0 {
                        TranslateMessage(&message);
                        DispatchMessageW(&message);
                    }
                } else {
                    MessageBoxW(null_mut(), wide("Windows could not add the tray icon. Open OpenBuds directly or try again after Explorer is ready.").as_ptr(), wide("OpenBuds").as_ptr(), MB_OK | MB_ICONERROR);
                    DestroyWindow(hwnd);
                }
            }
            DestroyIcon(icon);
        }
    }
    #[cfg(test)]
    mod tests {
        #[test]
        fn icon_sizes_use_complete_opaque_bgra_pixels() {
            for size in [16, 20, 24, 32, 48, 64] {
                let pixels = super::icon_pixels(size);
                assert_eq!(pixels.len(), size * size * 4);
                assert!(pixels.as_chunks::<4>().0.iter().all(|p| p[3] == 255));
                assert!(pixels.as_chunks::<4>().0.contains(&[60, 74, 232, 255]));
            }
        }
    }
}
fn main() {
    #[cfg(windows)]
    host::run();
    #[cfg(not(windows))]
    eprintln!(
        "The lightweight tray host is Windows-only. Launch the Compose desktop application directly."
    );
}
