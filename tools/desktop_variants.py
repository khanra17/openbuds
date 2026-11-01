# SPDX-License-Identifier: GPL-3.0-or-later
"""Stage the external-Java variant from the same optimized JARs as a bundled app image."""
import argparse
from pathlib import Path
import shutil


def stage_external(image: Path, platform: str, launcher: Path, output: Path):
    if output.resolve().is_relative_to(image.resolve()):
        raise ValueError("External variant output must be outside the bundled app image")
    if not launcher.is_file():
        raise ValueError(f"Native external-Java launcher missing: {launcher}")
    app = image / ("Contents/app" if platform.startswith("macos") else "app")
    jars = sorted(app.glob("*.jar"))
    if not jars:
        raise ValueError(f"No release JARs in {app}")
    config = app / "OpenBuds.cfg"
    contents = config.read_text(encoding="utf-8") if config.is_file() else ""
    if "app.mainclass=org.openbuds.desktop.MainKt" not in contents:
        raise ValueError("Unexpected app entry point; update the launcher before packaging")
    classpath = []
    for line in contents.splitlines():
        if line.startswith("app.classpath="):
            value = line.split("=", 1)[1].replace("\\", "/")
            if not value.startswith("$APPDIR/"):
                raise ValueError(f"Unexpected classpath entry: {value}")
            name = value.removeprefix("$APPDIR/")
            if "/" in name or not name.endswith(".jar") or not (app / name).is_file():
                raise ValueError(f"Missing or non-flat release JAR: {name}")
            classpath.append(name)
    if not classpath:
        raise ValueError("Release classpath is empty")
    if (app / "runtime").exists():
        raise ValueError("Unexpected Java runtime inside application resources")
    # Never mutate the bundled app or delete an existing caller-supplied output tree.
    output.mkdir(parents=True, exist_ok=False)
    (output / "app").mkdir()
    for name in classpath:
        shutil.copy2(app / name, output / "app" / name)
    # Preserve the bundled launcher's exact classpath order, not JVM wildcard ordering.
    (output / "app/classpath.txt").write_text("\n".join(classpath) + "\n", encoding="utf-8")
    # jpackage extracts Skiko native libraries/checksums beside the JARs. They are
    # application resources, NOT Java; dropping them yields a broken small variant.
    for asset in app.iterdir():
        if asset.suffix in {".jar", ".cfg"} or asset.name == ".jpackage.xml":
            continue
        destination = output / "app" / asset.name
        if asset.is_dir():
            shutil.copytree(asset, destination, symlinks=True)
        else:
            shutil.copy2(asset, destination)
    windows = platform.startswith("windows")
    binary = output / "OpenBuds.exe" if windows else output / "bin/OpenBuds"
    binary.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(launcher, binary)
    if not windows:
        binary.chmod(0o755)
    (output / "RUNTIME.txt").write_text(
        "OpenBuds — external Java variant\n\n"
        "This archive deliberately contains NO Java runtime.\n"
        "Install a complete desktop Java 21+ runtime of the same architecture as this download. "
        "A JRE is sufficient; a JDK is not required. Headless-only/server runtimes are not supported.\n"
        "JAVA_HOME takes precedence; otherwise Java is located on PATH. "
        "The launcher checks Java version and architecture before starting the UI. "
        "It does not download Java, change your environment, or open Bluetooth.\n\n"
        "Windows: run OpenBuds.exe (or the sibling OpenBudsTray.exe from the package root).\n"
        "Linux/macOS: run bin/OpenBuds. macOS native Bluetooth is not implemented.\n"
        "For a self-contained installation, choose the bundled-runtime archive instead.\n",
        encoding="utf-8")
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", required=True, type=Path)
    parser.add_argument("--platform", required=True)
    parser.add_argument("--launcher", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    try:
        print(stage_external(args.image, args.platform, args.launcher, args.output))
    except (ValueError, FileExistsError) as error:
        parser.error(str(error))


if __name__ == "__main__":
    main()
