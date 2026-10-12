"""Verify the retained INSTALL01 archive/ARM64 ELF layout, without installing an APK."""
import argparse
import hashlib
import json
import struct
import zipfile
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("apk", type=Path)
args = parser.parse_args()
with zipfile.ZipFile(args.apk) as archive:
    bad_entry = archive.testzip()
    if bad_entry:
        raise ValueError(f"APK CRC failed: {bad_entry}")
    libraries = [item for item in archive.infolist() if item.filename.startswith("lib/") and item.filename.endswith(".so")]
    if not libraries:
        raise ValueError("APK has no native library")
    layouts = []
    for item in libraries:
        if not item.filename.startswith("lib/arm64-v8a/") or item.compress_type != zipfile.ZIP_DEFLATED:
            raise ValueError("Expected compressed ARM64 native libraries for INSTALL01 packaging")
        data = archive.read(item)
        if data[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<H", data, 18)[0] != 183:
            raise ValueError("Expected little-endian AArch64 ELF64")
        phoff = struct.unpack_from("<Q", data, 32)[0]
        phentsize, phnum = struct.unpack_from("<HH", data, 54)
        if phentsize < 56:
            raise ValueError("Invalid ELF program header size")
        segments = []
        for index in range(phnum):
            kind, _, offset, vaddr, _, _, _, alignment = struct.unpack_from("<IIQQQQQQ", data, phoff + index * phentsize)
            if kind == 1:
                if alignment < 16384 or offset % 16384 != vaddr % 16384:
                    raise ValueError("ELF LOAD does not meet 16 KiB layout")
                segments.append(hex(alignment))
        if not segments:
            raise ValueError("ELF has no LOAD segments")
        layouts.append({"name": item.filename, "zip_method": item.compress_type, "load_alignment": segments})
print(json.dumps({"apk": str(args.apk.resolve()), "bytes": args.apk.stat().st_size,
    "sha256": hashlib.sha256(args.apk.read_bytes()).hexdigest(), "zip_crc": "PASS", "native": layouts}, indent=2))
