# Build and packaging helpers

- `package_release.py` creates release archives with binaries, dependency notices, checksums, runtime metadata and exact committed source. It rejects dirty/untracked source so new binaries cannot be paired with stale HEAD.
- `desktop_variants.py` stages the smaller external-Java distribution from the optimized bundled image. It preserves JAR order and native renderer assets, omitting only the Java runtime and replacing the launcher. It never downloads Java.
- `smoke_desktop.py` checks both real launchers and renderers. The application opens disconnected and exits after rendered frames; it does not enumerate devices, invent readings or open Bluetooth. The bundled check deliberately hides external Java.
- `test_desktop_variants.py` covers classpath/assets, platform layouts, archive contents and staging/source safety. Run `python -m unittest discover -s tools -p 'test_*.py' -v`.
- `make_launcher_icons.py` regenerates the original OpenBuds launcher mark using only Python's standard library. Application status pictograms are original Compose drawing code, not manufacturer/product artwork.

See [application structure and builds](../apps/compose/README.md). Legacy Slint/Android application trees and procedural product-artwork generators are not part of the active workspace.
