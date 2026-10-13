"""Bounded read-only P0 snapshot of loaded hit-reaction resources (no game calls).

Run only while the user operates the ordinary region. PID/base are explicit;
no process launch, input, write, injection, or automatic repeated sampling.
Layouts are analysis of this exact installed build, not a gameplay API.
"""
import argparse
import ctypes as c
import hashlib
import json
import math
import re
import struct
from pathlib import Path

EXE = Path(r"D:\SteamLibrary\steamapps\common\StellarBlade\SB\Binaries\Win64\SB-Win64-Shipping.exe")
SHA = "573AAFF1C9455F85EA6036EF6F6CCB0774DE4164722A296276FBBFC7FA87545C"
parser = argparse.ArgumentParser()
parser.add_argument("pid", type=int)
parser.add_argument("base", type=lambda s: int(s, 0))
parser.add_argument("--output", type=Path, required=True)
parser.add_argument("--key-tags", action="store_true", help="Include activation/exclusion tags and full direction paths")
parser.add_argument("--animation-notifies", action="store_true", help="Include loaded light-hit and fixed Emil animation notifications")
parser.add_argument("--characters", action="store_true", help="Read current ordinary-character resource paths and native show tags")
parser.add_argument("--skill-levels", action="store_true", help="Read Eve sword SkillTable hit levels and native actor level fields")
args = parser.parse_args()
if hashlib.sha256(EXE.read_bytes()).hexdigest().upper() != SHA:
    raise ValueError("Executable version mismatch")
k = c.WinDLL("kernel32", use_last_error=True)
k.OpenProcess.argtypes = [c.c_ulong, c.c_int, c.c_ulong]
k.OpenProcess.restype = c.c_void_p
k.ReadProcessMemory.argtypes = [c.c_void_p, c.c_void_p, c.c_void_p, c.c_size_t, c.POINTER(c.c_size_t)]
k.CloseHandle.argtypes = [c.c_void_p]
k.QueryFullProcessImageNameW.argtypes = [c.c_void_p, c.c_ulong, c.c_wchar_p, c.POINTER(c.c_ulong)]
h = k.OpenProcess(0x1010, False, args.pid)  # VM_READ + QUERY_LIMITED_INFORMATION only
if not h:
    raise OSError(c.get_last_error(), "Cannot open selected process for read")

def read(at, size):
    if not 0x10000 <= at < 0x7FFFFFFFFFFF or not 0 < size <= 0x200000:
        raise ValueError("Read outside diagnostic bounds")
    out, got = c.create_string_buffer(size), c.c_size_t()
    if not k.ReadProcessMemory(h, at, out, size, c.byref(got)) or got.value != size:
        raise ValueError("Object unavailable during read")
    return out.raw

def value(at, fmt="<Q"):
    return struct.unpack(fmt, read(at, struct.calcsize(fmt)))[0]

def fname(at):
    idx, number = struct.unpack("<II", read(at, 8))
    block, offset = idx >> 16, idx & 0xFFFF
    if block > 8191 or number > 1000000:
        raise ValueError("Invalid name index")
    # LEA at 0x293788D: next instruction 0x2937894 + displacement 0x46C73AC.
    entry = value(args.base + 0x2937894 + 0x46C73AC + 0x10 + block * 8) + offset * 2
    header = value(entry, "<H")
    length, wide = header >> 6, header & 1
    if not 0 < length <= 1023:
        raise ValueError("Invalid name length")
    name = read(entry + 2, length * (2 if wide else 1)).decode("utf-16le" if wide else "utf-8")
    return name + (f"_{number - 1}" if number else "")

def fstring(at):
    ptr, count, capacity = struct.unpack("<Qii", read(at, 16))
    if count == 0:
        return ""
    if not 1 <= count <= capacity <= 4096:
        raise ValueError("Invalid FString")
    raw = read(ptr, count * 2)
    if raw[-2:] != b"\0\0":
        raise ValueError("Unterminated FString")
    return raw[:-2].decode("utf-16le")

classes = {}
def classname(obj):
    cls = value(obj + 0x10)
    if cls not in classes:
        classes[cls] = fname(cls + 0x18)
    return classes[cls]

def object_record(obj):
    header = read(obj, 0x28)
    index = struct.unpack_from("<i", header, 0xC)[0]
    if index < 0 or index >= object_count:
        raise ValueError("Invalid UObject index")
    chunk = value(chunks + (index // 65536) * 8)
    slot = read(chunk + (index % 65536) * 24, 24)
    if struct.unpack_from("<Q", slot)[0] != obj or struct.unpack_from("<I", slot, 8)[0] & 0x30000000:
        raise ValueError("Invalid/deleting UObject")
    if not args.base < struct.unpack_from("<Q", header)[0] < args.base + 0x15981000:
        raise ValueError("Invalid UObject vtable")
    return {"address": hex(obj), "index": index, "serial": struct.unpack_from("<i", slot, 16)[0], "class": classname(obj), "name": fname(obj + 0x18)}

def skill_levels(obj):
    raw=read(obj+0x30,0x50)
    ptr,count,capacity=struct.unpack_from("<Qii",raw)
    if not 0<count<=capacity<=32768 or value(obj+0x58,"<i")!=count:
        raise ValueError("Invalid skill level row map")
    if fname(value(obj+0x28)+0x18)!="SBSkillTableProperty":
        raise ValueError("Unexpected skill level row struct")
    bit_ptr=struct.unpack_from("<Q",raw,0x20)[0]
    bits=read(bit_ptr,((count+31)//32)*4) if bit_ptr else raw[0x10:0x20]
    result=[]
    for i in range(count):
        if not struct.unpack_from("<I",bits,(i//32)*4)[0]&(1<<(i%32)): continue
        name=fname(ptr+i*24)
        if name.startswith("P_Eve_Sword_"):
            row=value(ptr+i*24+8)
            result.append({"name":name,"id":value(row+0x10,"<I"),"hitLevel":value(row+0x70,"<i")})
    if read(obj+0x30,0x50)!=raw: raise ValueError("Skill level map changed")
    return result

def native_levels():
    client=value(args.base+0x7032570)
    holder=value(client+0xC8)
    array=value(holder+0x28);count=value(holder+0x30,"<i")
    if not 0<count<=4096: raise ValueError("Invalid native actor array")
    result=[]
    for i in range(count):
        entity=value(array+i*8)
        if not entity or value(entity)!=args.base+0x5BF6730 or value(entity+0x10)!=args.base+0x5BF67D0: continue
        guid=value(entity+0x30,"<I");kind=value(entity+0x10C,"<I")
        required=value(entity+0x1AC,"<f");additive=value(entity+0x2B4,"<f")
        if not guid or not kind or not math.isfinite(required) or not math.isfinite(additive):
            raise ValueError("Invalid native actor hit level")
        result.append({"guid":guid,"type":kind,"requiredHitLevel":required,"additiveHitLevel":additive})
    if value(holder+0x28)!=array or value(holder+0x30,"<i")!=count: raise ValueError("Native actor array changed")
    return result

def names_array(at):
    raw=read(at,16)
    ptr,count,capacity=struct.unpack("<Qii",raw)
    if not 0<=count<=capacity<=256:
        raise ValueError("Invalid show tag array")
    names=[fname(ptr+i*8) for i in range(count)]
    if read(at,16)!=raw:
        raise ValueError("Show tags changed during snapshot")
    return names

def outer_names(obj):
    result,seen=[],set()
    for _ in range(12):
        if not obj:
            return result
        if obj in seen:
            raise ValueError("Outer cycle")
        seen.add(obj);result.append(fname(obj+0x18));obj=value(obj+0x20)
    raise ValueError("Outer hierarchy too deep")

def animation_notifies(obj):
    raw=read(obj+0x80,16)
    ptr,count,capacity=struct.unpack("<Qii",raw)
    if not 0<=count<=capacity<=256:
        raise ValueError("Invalid animation notify array")
    result=[]
    for i in range(count):
        entry=ptr+i*0xB8
        item={"name":fname(entry+0x40)}
        for label,offset in (("notify",0x48),("state",0x50)):
            notify=value(entry+offset)
            if notify:
                item[label]=object_record(notify)
        result.append(item)
    if read(obj+0x80,16)!=raw:
        raise ValueError("Animation notifications changed during snapshot")
    return result

def character_tags(obj):
    raw=read(obj+0x3128,0x50)
    ptr,count,capacity=struct.unpack_from("<Qii",raw)
    bit_count=struct.unpack_from("<i",raw,0x28)[0]
    if not 0<=count<=capacity<=512 or count!=bit_count:
        raise ValueError("Invalid character tag sparse map")
    bit_ptr=struct.unpack_from("<Q",raw,0x20)[0]
    bits=read(bit_ptr,((count+31)//32)*4) if bit_ptr and count else raw[0x10:0x20]
    result=[]
    for i in range(count):
        if struct.unpack_from("<I",bits,(i//32)*4)[0]&(1<<(i%32)):
            result.append({"tag":fname(ptr+i*20),"count":value(ptr+i*20+8,"<i")})
    if read(obj+0x3128,0x50)!=raw:
        raise ValueError("Character tags changed during snapshot")
    return result

def show_keys(obj):
    raw = read(obj + 0x60, 0x50)
    ptr, slots, capacity = struct.unpack_from("<Qii", raw)
    bits_ptr = struct.unpack_from("<Q", raw, 0x20)[0]
    bit_count = struct.unpack_from("<i", raw, 0x28)[0]
    if not 0 <= slots <= capacity <= 128 or bit_count != slots:
        raise ValueError("Invalid show key sparse map")
    bits = read(bits_ptr, ((slots + 31) // 32) * 4) if bits_ptr and slots else raw[0x10:0x20]
    result = []
    for i in range(slots):
        if not struct.unpack_from("<I", bits, (i // 32) * 4)[0] & (1 << (i % 32)):
            continue
        entry = read(ptr + i * 32, 32)
        kind = struct.unpack_from("<i", entry)[0]
        key_ptr, count, cap = struct.unpack_from("<Qii", entry, 8)
        if not 0 <= count <= cap <= 128:
            raise ValueError("Invalid show key list")
        for j in range(count):
            key = value(key_ptr + j * 8)
            if not key:
                result.append({"kind": kind, "class": "null", "name": ""})
                continue
            item = object_record(key)
            item["kind"] = kind
            if args.key_tags:
                item["baseConditionBytes"]=read(key+0x48,0x10).hex()
                item["checkTags"]=names_array(key+0x28)
                item["excludeTags"]=names_array(key+0x38)
            if item["class"] == "SBShowAnimKey":
                item["activation"]={label:value(key+offset,"<B") for label,offset in (
                    ("checkType",0x58),("inverse",0x59),("swimming",0x64),("underwater",0x65),
                    ("axisUp",0x66),("playType",0x67),("customAnim",0x80),("directionalBlend",0x81),
                    ("directional",0x83),("areaCheck",0x84),("directionType",0x86),("areaType",0x89))}
                item["paths"] = {name: fstring(key + off) for name, off in (("animation", 0x70), ("front", 0xA0), ("back", 0xB0), ("left", 0xC0), ("right", 0xD0))}
                item["customAnimation"] = value(key + 0x80, "<B")
                item["usesCustomAnimation"] = value(key + 0x68, "<B")
                item["priority"] = value(key + 0x160, "<f")
                item["conditionBytes"] = read(key + 0x58, 0x18).hex()
                item["directionBytes"] = read(key + 0x80, 0x20).hex()
                item["playConditionBytes"] = read(key + 0x138, 0x48).hex()
                if args.key_tags:
                    item["paths"]["up"]=fstring(key+0xE0)
                    item["paths"]["down"]=fstring(key+0xF0)
            if item["class"] == "SBShowPlayShowKey":
                item["showPointer"] = hex(value(key + 0x58))
                item["showPath"] = fstring(key + 0x68)
                item["fallbackShowPath"] = fstring(key + 0x78)
                item["target"] = value(key + 0x60, "<B")
            if item["class"] == "SBShowHitReactionKey":
                item["paths"] = {name: fstring(key + off) for name, off in (("front", 0x58), ("back", 0x68), ("left", 0x78), ("right", 0x88))}
                item["additive"] = value(key + 0x98, "<B")
                item["priority"] = value(key + 0xC0, "<i")
                floats = struct.unpack("<5f", read(key + 0x9C, 20))
                if not all(math.isfinite(v) for v in floats):
                    raise ValueError("Invalid hit-reaction timing")
                item["timing"] = dict(zip(("start", "end", "rate", "blendIn", "blendOut"), floats))
            result.append(item)
    if read(obj + 0x60, 0x50) != raw:
        raise ValueError("Show resource changed during snapshot")
    return result

try:
    process_path, length = c.create_unicode_buffer(32768), c.c_ulong(32768)
    if not k.QueryFullProcessImageNameW(h, 0, process_path, c.byref(length)) or Path(process_path.value).resolve() != EXE.resolve():
        raise ValueError("PID is not the installed game")
    if read(args.base, 2) != b"MZ" or value(args.base + 0x293788D, "<Q") != 0x48046C73AC058D48:
        raise ValueError("Image base/name-pool instruction mismatch")
    array = args.base + 0x6DD7230 + 0x10
    array_header = read(array, 32)
    chunks = struct.unpack_from("<Q", array_header)[0]
    object_count = struct.unpack_from("<i", array_header, 20)[0]
    chunk_count = struct.unpack_from("<i", array_header, 28)[0]
    if not 0 < object_count <= 2000000 or not 0 < chunk_count <= 32 or object_count > chunk_count * 65536:
        raise ValueError("Unsupported object array layout")
    records, rejected, errors, show_count, tables, animations, characters = [], 0, [], 0, [], [], []
    for ci in range(chunk_count):
        chunk = value(chunks + ci * 8)
        n = min(65536, object_count - ci * 65536)
        if n <= 0:
            break
        slots = read(chunk, n * 24)
        for i in range(n):
            obj, flags = struct.unpack_from("<QI", slots, i * 24)
            if not obj or flags & 0x30000000:
                continue
            try:
                clsname = classname(obj)
                if args.characters and clsname.startswith("CH_M_NA_") and not fname(obj+0x18).startswith("Default__"):
                    character=object_record(obj)
                    character["resourcePath"]=fstring(obj+0x1DE8)
                    character["showTags"]=character_tags(obj)
                    if object_record(obj)!={key:character[key] for key in ("address","index","serial","class","name")}:
                        raise ValueError("Character identity changed")
                    characters.append(character)
                if args.animation_notifies and clsname=="AnimSequence":
                    name=fname(obj+0x18)
                    if re.match(r"Result_Hit_(?:Stand_Light|add_Light)",name) or name in ("Result_Hit_stumble_Emil","Result_Hit_Stand_Emil"):
                        animation=object_record(obj)
                        animation["outerNames"]=outer_names(obj)
                        animation["notifies"]=animation_notifies(obj)
                        if object_record(obj)!={key:animation[key] for key in ("address","index","serial","class","name")}:
                            raise ValueError("Animation identity changed")
                        animations.append(animation)
                if "Table" in clsname and (fname(obj + 0x18) == "SkillResultTable" or args.skill_levels and fname(obj+0x18)=="SkillTable"):
                    table = object_record(obj)
                    table["rowStruct"] = hex(value(obj + 0x28))
                    table["rowMapHeader"] = read(obj + 0x30, 0x50).hex()
                    if args.skill_levels and table["name"]=="SkillTable": table["eveSwordLevels"]=skill_levels(obj)
                    tables.append(table)
                if clsname != "SBShowData":
                    continue
                show_count += 1
                rec = object_record(obj)
                if args.key_tags:
                    rec["outerNames"]=outer_names(obj)
                rec["keys"] = show_keys(obj)
                if re.search(r"hit|damage|react", rec["name"], re.I) or any(key["class"] == "SBShowHitReactionKey" for key in rec["keys"]):
                    if object_record(obj) != {key: rec[key] for key in ("address", "index", "serial", "class", "name")}:
                        raise ValueError("Resource identity changed")
                    records.append(rec)
            except (ValueError, UnicodeError) as error:
                rejected += 1
                if len(errors) < 24:
                    errors.append({"address": hex(obj), "reason": str(error)})
    args.output.parent.mkdir(parents=True, exist_ok=True)
    levels=native_levels() if args.skill_levels else []
    args.output.write_text(json.dumps({"pid": args.pid, "exeSha256": SHA, "objects": object_count, "showDataCount": show_count, "rejectedReads": rejected, "rejectionExamples": errors, "tables": tables, "resources": records,"animations":animations,"characters":characters,"nativeHitLevels":levels,"keyTagsCaptured":args.key_tags, "limitation": "Read-only loaded-resource snapshot; not proof of which resource an EVE hit uses or a safe callable ABI."}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Saved {len(records)} loaded hit-reaction resources: {args.output}")
finally:
    k.CloseHandle(h)
