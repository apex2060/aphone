#!/usr/bin/env python3
"""Fail if an APK's arm64 native libraries are not 16 KB aligned."""

import struct
import sys
import zipfile

PAGE = 16384
PT_LOAD = 1


def elf_load_aligns(blob: bytes) -> list[int]:
    if blob[:4] != b"\x7fELF":
        raise SystemExit("not an ELF file")
    if blob[4] != 2:
        raise SystemExit("expected ELF64")
    little = blob[5] == 1
    endian = "<" if little else ">"
    # ELF64: e_phoff at 32, e_phentsize at 54, e_phnum at 56.
    e_phoff = struct.unpack_from(endian + "Q", blob, 32)[0]
    e_phentsize, e_phnum = struct.unpack_from(endian + "HH", blob, 54)
    aligns = []
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        p_type = struct.unpack_from(endian + "I", blob, off)[0]
        if p_type != PT_LOAD:
            continue
        p_align = struct.unpack_from(endian + "Q", blob, off + 48)[0]
        aligns.append(p_align)
    return aligns


def zip_data_offset(zf: zipfile.ZipFile, info: zipfile.ZipInfo) -> int:
    with zf.open(info, "r") as raw:
        # ZipExtFile has the underlying header already consumed. Read the local header ourselves.
        pass
    src = zf.fp
    if src is None:
        raise SystemExit("zip has no file pointer")
    src.seek(info.header_offset)
    local = src.read(30)
    if local[:4] != b"PK\x03\x04":
        raise SystemExit(f"bad local header for {info.filename}")
    name_len, extra_len = struct.unpack_from("<HH", local, 26)
    return info.header_offset + 30 + name_len + extra_len


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit(f"usage: {sys.argv[0]} app.apk")
    apk = sys.argv[1]
    failed = False
    with zipfile.ZipFile(apk) as zf:
        sos = [i for i in zf.infolist() if i.filename.endswith(".so")]
        if not sos:
            raise SystemExit(f"no native libraries in {apk}")
        for info in sos:
            blob = zf.read(info)
            aligns = elf_load_aligns(blob)
            method = "stored" if info.compress_type == zipfile.ZIP_STORED else f"compressed({info.compress_type})"
            data_off = zip_data_offset(zf, info) if info.compress_type == zipfile.ZIP_STORED else None
            zip_ok = data_off is not None and data_off % PAGE == 0
            elf_ok = bool(aligns) and all(a % PAGE == 0 for a in aligns)
            print(
                f"{info.filename}: {method} elf_align={aligns} "
                f"zip_data_off={data_off} elf_ok={elf_ok} zip_ok={zip_ok}"
            )
            if not elf_ok or not zip_ok:
                failed = True
    if failed:
        raise SystemExit("16 KB alignment check failed")


if __name__ == "__main__":
    main()
