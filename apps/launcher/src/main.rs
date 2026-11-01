// SPDX-License-Identifier: GPL-3.0-or-later
//! Small, offline launcher for the external-Java distribution. No download or installation.
#![cfg_attr(windows, windows_subsystem = "windows")]
use std::{
    path::{Path, PathBuf},
    process::{Command, Stdio},
};

fn property<'a>(properties: &'a str, name: &str) -> Option<&'a str> {
    properties.lines().find_map(|line| {
        let (key, value) = line.trim().split_once('=')?;
        (key.trim() == name).then_some(value.trim())
    })
}
fn validate_java(properties: &str) -> Result<(), String> {
    let version = property(properties, "java.specification.version").unwrap_or("");
    let major = version.parse::<u32>().unwrap_or(0);
    if major < 21 {
        return Err(format!(
            "OpenBuds needs Java 21 or newer; found {version:?}. Set JAVA_HOME to a compatible Java installation, or use the bundled-runtime download."
        ));
    }
    let arch = property(properties, "os.arch").unwrap_or("");
    let matches = match std::env::consts::ARCH {
        "x86_64" => matches!(arch, "amd64" | "x86_64"),
        "aarch64" => matches!(arch, "aarch64" | "arm64"),
        expected => arch == expected,
    };
    if !matches {
        return Err(format!(
            "This OpenBuds download is for {}, but Java reports {arch:?}. Use matching Java and OpenBuds architectures.",
            std::env::consts::ARCH
        ));
    }
    Ok(())
}
fn executable(home: &Path, gui: bool) -> PathBuf {
    home.join("bin").join(if cfg!(windows) {
        if gui { "javaw.exe" } else { "java.exe" }
    } else {
        "java"
    })
}
fn hidden(command: &mut Command) -> &mut Command {
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        command.creation_flags(0x08000000); // CREATE_NO_WINDOW: never flash a console.
    }
    command
}
fn java_home() -> Result<PathBuf, String> {
    let selected = std::env::var_os("JAVA_HOME").map(PathBuf::from);
    let probe = selected
        .as_ref()
        .map(|home| executable(home, false))
        .unwrap_or_else(|| PathBuf::from("java"));
    let result = hidden(Command::new(&probe).args(["-XshowSettings:properties", "-version"]))
        .stdin(Stdio::null()).output().map_err(|error| format!(
            "Could not start Java ({}): {error}. Install a matching Java 21+ runtime and set JAVA_HOME, or choose the bundled-runtime OpenBuds download.", probe.display()))?;
    if !result.status.success() {
        return Err(
            "Java could not start. Check JAVA_HOME, or use the bundled-runtime download.".into(),
        );
    }
    let properties = format!(
        "{}\n{}",
        String::from_utf8_lossy(&result.stderr),
        String::from_utf8_lossy(&result.stdout)
    );
    validate_java(&properties)?;
    property(&properties, "java.home").filter(|s| !s.is_empty()).map(PathBuf::from)
        .ok_or_else(|| "Java did not report its installation directory. Set JAVA_HOME to a complete Java 21+ runtime.".into())
}
fn ui_command(program: &Path) -> Command {
    let mut command = Command::new(program);
    // Compose Desktop uses Swing/AWT, which manages the macOS AppKit thread.
    // -XstartOnFirstThread is for other windowing stacks and deadlocks AWT.
    command.args([
        "-XX:+UseSerialGC",
        "-Xms16m",
        "-Xmx256m",
        "--enable-native-access=ALL-UNNAMED",
        "-Dapple.awt.application.appearance=NSAppearanceNameDarkAqua",
    ]);
    command
}
fn run() -> Result<(), String> {
    let exe = std::env::current_exe().map_err(|e| e.to_string())?;
    let directory = exe.parent().ok_or("Missing application directory")?;
    let root = if directory.file_name().is_some_and(|n| n == "bin") {
        directory.parent().ok_or("Missing application directory")?
    } else {
        directory
    };
    let app = root.join("app");
    if !app.is_dir()
        || !std::fs::read_dir(&app)
            .map_err(|e| e.to_string())?
            .flatten()
            .any(|file| file.path().extension().is_some_and(|e| e == "jar"))
    {
        return Err("The OpenBuds app directory is missing. Extract the whole archive, not only the launcher.".into());
    }
    let entries = std::fs::read_to_string(app.join("classpath.txt"))
        .map_err(|e| format!("Release classpath missing: {e}. Extract the whole archive."))?;
    let mut jars = Vec::new();
    for name in entries.lines() {
        if name.is_empty()
            || name.contains(['/', '\\', ':'])
            || !name.ends_with(".jar")
            || !app.join(name).is_file()
        {
            return Err(format!("Invalid or missing release JAR: {name:?}"));
        }
        jars.push(app.join(name));
    }
    if jars.is_empty() {
        return Err("Release classpath is empty".into());
    }
    let classpath = std::env::join_paths(jars).map_err(|e| e.to_string())?;
    let home = java_home()?;
    let args: Vec<_> = std::env::args_os().skip(1).collect();
    if args.iter().any(|a| a == "--launcher-check") {
        println!(
            "OpenBuds external-Java launcher: compatible Java at {}",
            home.display()
        );
        return Ok(());
    }
    let program = executable(&home, true);
    let mut command = ui_command(&program);
    command.arg(format!("-Dskiko.library.path={}", app.display()));
    command
        .arg(format!(
            "-Dcompose.application.resources.dir={}",
            app.join("resources").display()
        ))
        .arg("-Dcompose.application.configure.swing.globals=true")
        .arg("-cp")
        .arg(classpath)
        .arg("org.openbuds.desktop.MainKt")
        .args(args);
    let status = hidden(&mut command)
        .stdin(Stdio::null())
        .status()
        .map_err(|e| format!("Unable to launch OpenBuds: {e}"))?;
    if status.success() {
        Ok(())
    } else {
        Err(format!(
            "OpenBuds exited with {status}. Extract the complete archive, including native application resources, and use a matching desktop Java 21+ runtime (including java.desktop), not a headless/server-only runtime. The bundled-runtime download includes the required Java modules."
        ))
    }
}
fn main() {
    if let Err(error) = run() {
        eprintln!("{error}");
        #[cfg(windows)]
        unsafe {
            use windows_sys::Win32::UI::WindowsAndMessaging::{MB_ICONERROR, MB_OK, MessageBoxW};
            // Checks are noninteractive, including a deliberately missing/old Java in CI.
            if !std::env::args_os().any(|a| a == "--launcher-check" || a == "--smoke-test") {
                let text: Vec<u16> = error.encode_utf16().chain(Some(0)).collect();
                let title: Vec<u16> = "OpenBuds — Java runtime"
                    .encode_utf16()
                    .chain(Some(0))
                    .collect();
                MessageBoxW(
                    std::ptr::null_mut(),
                    text.as_ptr(),
                    title.as_ptr(),
                    MB_OK | MB_ICONERROR,
                );
            }
        }
        std::process::exit(1);
    }
}
#[cfg(test)]
mod tests {
    #[test]
    fn swing_startup_does_not_force_the_macos_first_thread() {
        let command = super::ui_command(std::path::Path::new("java"));
        let args = command.get_args().collect::<Vec<_>>();
        assert!(!args.iter().any(|arg| *arg == "-XstartOnFirstThread"));
        assert!(
            args.iter()
                .any(|arg| *arg == "--enable-native-access=ALL-UNNAMED")
        );
    }
    #[test]
    fn properties_accept_spaces_in_install_paths() {
        assert_eq!(
            super::property(
                "    java.home = C:\\Program Files\\Java\\jdk-21\n",
                "java.home"
            ),
            Some("C:\\Program Files\\Java\\jdk-21")
        );
    }
    #[test]
    fn missing_and_old_java_are_not_accepted() {
        for version in ["", "1.8", "17", "nonsense"] {
            assert!(
                super::validate_java(&format!(
                    "java.specification.version = {version}\nos.arch = amd64"
                ))
                .is_err()
            );
        }
    }
    #[test]
    fn matching_current_java_is_accepted_and_other_architectures_rejected() {
        let arch = match std::env::consts::ARCH {
            "x86_64" => "amd64",
            "aarch64" => "aarch64",
            other => other,
        };
        assert!(
            super::validate_java(&format!(
                "java.specification.version = 21\nos.arch = {arch}"
            ))
            .is_ok()
        );
        assert!(
            super::validate_java("java.specification.version = 25\nos.arch = unknown").is_err()
        );
    }
}
