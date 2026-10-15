"""Read the installed IoStore directory index; never load or modify game assets."""
import argparse
import struct
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("--utoc", type=Path, default=Path(r"D:\SteamLibrary\steamapps\common\StellarBlade\SB\Content\Paks\pakchunk0-WindowsNoEditor.utoc"))
parser.add_argument("--output", type=Path, required=True)
parser.add_argument("--match", action="append", help="Case-insensitive resource path substring; repeat for multiple matches")
args = parser.parse_args()
match_terms = tuple(s.lower() for s in (args.match or ("hitreact", "skillresulttable")))
b = args.utoc.read_bytes()
if b[:16] != b"-==--==--==--==-" or b[16] != 3:
    raise ValueError("Only the observed version-3 IoStore header is supported")
header, count, blocks, block_entry, methods, method_length, block_size, index_size = struct.unpack_from("<8I", b, 20)
if header != 144 or block_entry != 12 or b[80] & 2:
    raise ValueError("Unsupported header/block size or encrypted container")
method_start = header + count * 22 + blocks * block_entry
index_start = method_start + methods * method_length
index_end = index_start + index_size
if index_end > len(b):
    raise ValueError("Directory index outside file")
pos = index_start

def u32():
    global pos
    if pos + 4 > index_end:
        raise ValueError("Truncated directory index")
    v = struct.unpack_from("<I", b, pos)[0]
    pos += 4
    return v

def string():
    global pos
    size = u32()
    if size < 1 or size > 4096 or pos + size > index_end:
        raise ValueError("Unsupported string length")
    raw = b[pos:pos + size]
    pos += size
    if raw[-1] != 0:
        raise ValueError("Unterminated string")
    return raw[:-1].decode("utf-8")

mount = string()
dirs = [tuple(u32() for _ in range(4)) for _ in range(u32())]
files = [tuple(u32() for _ in range(3)) for _ in range(u32())]
names = [string() for _ in range(u32())]
if pos != index_end:
    raise ValueError("Unexpected directory index remainder")
records = [f"IoStore: {args.utoc}", f"Mount: {mount}", f"Directories: {len(dirs)} Files: {len(files)}", "Read-only directory/chunk metadata; resource contents have not been decoded.",
           "Compression methods: " + repr([b[method_start + i * method_length:method_start + (i + 1) * method_length].split(b"\0")[0].decode("ascii") for i in range(methods)])]
seen_dirs, seen_files = set(), set()

def visit(di, parent):
    while di != 0xFFFFFFFF:
        if di >= len(dirs) or di in seen_dirs:
            raise ValueError("Invalid/repeated directory")
        seen_dirs.add(di)
        name, child, sibling, fi = dirs[di]
        path = parent + (names[name] + "/" if name != 0xFFFFFFFF else "")
        while fi != 0xFFFFFFFF:
            if fi >= len(files) or fi in seen_files:
                raise ValueError("Invalid/repeated file")
            seen_files.add(fi)
            fname, next_file, chunk = files[fi]
            full = path + names[fname]
            if any(s in full.lower() for s in match_terms):
                if chunk >= count:
                    raise ValueError("Invalid chunk index")
                entry = header + count * 12 + chunk * 10
                offset = int.from_bytes(b[entry:entry + 5], "big")
                size = int.from_bytes(b[entry + 5:entry + 10], "big")
                records.append(f"{full} | chunk={chunk} id={b[header + chunk * 12:header + (chunk + 1) * 12].hex()} offset={offset:X} length={size:X}")
            fi = next_file
        visit(child, path)
        di = sibling

visit(0, mount)
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text("\n".join(records) + "\n", encoding="utf-8")
print(f"Saved {len(records) - 5} matching resource paths: {args.output}")
