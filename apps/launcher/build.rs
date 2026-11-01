// SPDX-License-Identifier: GPL-3.0-or-later
// Emit standard Win32 RT_ICON/RT_GROUP_ICON resources directly. No extra resource
// compiler or build dependency is needed; the MSVC linker accepts the .res file.
fn resource(output: &mut Vec<u8>, kind: u16, id: u16, data: &[u8]) {
    output.extend_from_slice(&(data.len() as u32).to_le_bytes());
    output.extend_from_slice(&32u32.to_le_bytes());
    for value in [0xffff, kind, 0xffff, id] {
        output.extend_from_slice(&value.to_le_bytes());
    }
    output.extend_from_slice(&0u32.to_le_bytes()); // data version
    output.extend_from_slice(&(if kind == 0 { 0u16 } else { 0x1030u16 }).to_le_bytes());
    output.extend_from_slice(&0u16.to_le_bytes()); // language neutral
    output.extend_from_slice(&0u32.to_le_bytes()); // version
    output.extend_from_slice(&0u32.to_le_bytes()); // characteristics
    output.extend_from_slice(data);
    while !output.len().is_multiple_of(4) {
        output.push(0);
    }
}
fn main() {
    let icon = "../compose/desktopApp/packaging/openbuds.ico";
    println!("cargo:rerun-if-changed={icon}");
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() != Ok("windows") {
        return;
    }
    let ico = std::fs::read(icon).expect("Original OpenBuds icon missing");
    assert_eq!(&ico[..4], &[0, 0, 1, 0]);
    let count = u16::from_le_bytes([ico[4], ico[5]]);
    let mut group = ico[..6].to_vec();
    let mut res = Vec::new();
    resource(&mut res, 0, 0, &[]); // required null resource header
    for index in 0..count {
        let entry = &ico[6 + index as usize * 16..6 + (index as usize + 1) * 16];
        let length = u32::from_le_bytes(entry[8..12].try_into().unwrap()) as usize;
        let offset = u32::from_le_bytes(entry[12..16].try_into().unwrap()) as usize;
        resource(&mut res, 3, index + 1, &ico[offset..offset + length]);
        group.extend_from_slice(&entry[..12]);
        group.extend_from_slice(&(index + 1).to_le_bytes());
    }
    resource(&mut res, 14, 1, &group);
    let output =
        std::path::PathBuf::from(std::env::var_os("OUT_DIR").unwrap()).join("launcher.res");
    std::fs::write(&output, res).unwrap();
    println!("cargo:rustc-link-arg={}", output.display());
}
