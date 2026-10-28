"""Verify the built APK's actual native architectures and stripped ELF debug sections."""
import argparse
from pathlib import Path
import struct
import zipfile


def elf_sections(data):
    if data[:4] != b"\x7fELF":
        raise ValueError("Native library is not ELF")
    order = "<" if data[5] == 1 else ">"
    wide = data[4] == 2
    offset = struct.unpack_from(order + ("Q" if wide else "I"), data, 40 if wide else 32)[0]
    stride, count, names_index = struct.unpack_from(order + "HHH", data, 58 if wide else 46)
    if not offset or not count:
        return []
    headers = [offset + i * stride for i in range(count)]
    position, size = struct.unpack_from(order + ("QQ" if wide else "II"), data,
                                      headers[names_index] + (24 if wide else 16))
    names = data[position:position + size]
    sections = []
    for header in headers:
        start = struct.unpack_from(order + "I", data, header)[0]
        end = names.find(b"\0", start)
        sections.append(names[start:end].decode("ascii"))
    return sections


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument("--abi", default="arm64-v8a")
    args = parser.parse_args()
    with zipfile.ZipFile(args.apk) as archive:
        native = [entry for entry in archive.infolist()
                  if entry.filename.startswith("lib/") and entry.filename.endswith(".so")]
        architectures = {entry.filename.split("/")[1] for entry in native}
        if architectures != {args.abi}:
            raise SystemExit(f"Expected only {args.abi}; found {sorted(architectures)}")
        unstripped = []
        for entry in native:
            sections = elf_sections(archive.read(entry))
            if any(name.startswith((".debug_", ".zdebug_")) for name in sections):
                unstripped.append(entry.filename)
        if unstripped:
            raise SystemExit("Debug sections retained: " + ", ".join(unstripped))
        print(f"APK: {args.apk.stat().st_size / 1_000_000:.2f} MB")
        print(f"Native libraries: {len(native)}, architecture: {args.abi}, debug sections: none")
        print(f"Native bytes in APK: {sum(entry.compress_size for entry in native) / 1_000_000:.2f} MB")


if __name__ == "__main__":
    main()
