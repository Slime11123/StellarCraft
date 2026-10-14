"""Offline P0 evidence: inspect the installed PE, without attaching to a game."""
import argparse
import hashlib
from pathlib import Path

import capstone
import pefile

parser = argparse.ArgumentParser()
parser.add_argument("--exe", type=Path, default=Path(r"D:\SteamLibrary\steamapps\common\StellarBlade\SB\Binaries\Win64\SB-Win64-Shipping.exe"))
parser.add_argument("--output", type=Path, required=True)
parser.add_argument("--show-runtime", action="store_true", help="Include show creation and hit-reaction runtime disassembly")
parser.add_argument("--timed-effects", action="store_true", help="Inspect original skill-hit effect dispatch without invoking it")
args = parser.parse_args()
pe = pefile.PE(str(args.exe), fast_load=True)
md = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_64)
records = [
    f"File: {args.exe}",
    f"SHA256: {hashlib.sha256(args.exe.read_bytes()).hexdigest().upper()}",
    f"Timestamp: {pe.FILE_HEADER.TimeDateStamp:08X}",
    f"SizeOfImage: {pe.OPTIONAL_HEADER.SizeOfImage:08X}",
    "All addresses are RVAs. This tool never loads or invokes game code.",
    "Names below are analysis labels, not exported function symbols.",
    "A call list alone does not establish purity, ABI, or permission to invoke.",
    "Unwind ranges may split one logical function into multiple fragments.",
]
for rva in (0x5BFDD20, 0x5BFE0F0, 0x5BFE1B0, 0x5BFE3E0):
    raw = pe.get_data(rva, 256)
    end = next(i for i in range(0, len(raw) - 1, 2) if raw[i:i+2] == b"\0\0")
    records.append(f"Diagnostic string {rva:08X}: {raw[:end].decode('utf-16le')}")

regions = [
    ("FSBSkillHitResult job, including unwind fragments", 0x1B171A0, 0x3CF, False),
    ("Client skill result handler, including fragments", 0x1C4FB20, 0x9B0, False),
    ("Target state/resource selection and show requests", 0x1C5010F, 0x378, True),
    ("Actor hit job", 0x1B191D0, 0x274, False),
    ("Actor hit callback including effects", 0x1A66990, 0x9A0, False),
    ("On-hit target callback including effects", 0x1A7F880, 0x330, False),
    ("Generic show facade, including unwind fragments", 0x22857E0, 0x676, False),
    ("USBShowHitReactionKey type checks", 0x2451407, 0x76, True),
    ("USBShowHitReactionKey runtime branch", 0x245157A, 0x85, True),
    ("USBShowHitReactionKey remaining runtime branch", 0x2452091, 0x57, True),
]
if args.timed_effects:
    regions += [
        ("Queued skill-hit result forwarding to client", 0x1B171A0, 0x3CF, True),
        ("Original client skill-hit result dispatch", 0x1C4FB20, 0x9B0, True),
        ("Original target on-hit callback", 0x1A7F880, 0x330, True),
        ("SBApplyEffect native reflection wrapper", 0x269A020, 0x180, True),
        ("SBApplyEffect reflection function getter trampoline", 0x26A8D50, 5, True),
        ("SBApplyEffectMe reflection function getter trampoline", 0x26A8D80, 5, True),
        ("Native effect virtual lifecycle and complete update", 0x1BC3F10, 0x16A0, True),
        ("Native effect virtual update entries", 0x1BC7A10, 0x300, True),
        ("Native effect alias/id resolution", 0x1BDA730, 0x2A0, True),
        ("Native effect list execution candidate", 0x1BDA9D0, 0xC80, True),
        ("Native effect activation and state lifecycle candidates", 0x1BC5740, 0x15C0, True),
        ("Native effect stop candidate", 0x1BCBBC0, 0x480, True),
        ("Native effect initialize candidate", 0x1BDC710, 0x320, True),
        ("Native effect creation manager entry", 0x1AD7880, 0x900, True),
        ("Native effect creation parameter initializer", 0x1ACA4B0, 0x160, True),
        ("Effect owner accepted-effect registration", 0x1BA48A0, 0x280, True),
    ]
if args.show_runtime:
    regions += [
        ("Full show facade argument/control flow inspection", 0x22857E0, 0x676, True),
        ("Original target show caller and argument setup", 0x1C50365, 0x53, True),
        ("Native hit context construction and filling", 0x1C4FC0C, 0x170, True),
        ("Show allocation/creation candidate", 0x242A1F0, 0x600, True),
        ("Show forwarding and runtime access", 0x2428590, 0x250, True),
        ("Show scheduling candidate", 0x2427EE0, 0x600, True),
        ("Hit reaction runtime surrounding logical functions", 0x2451200, 0x1200, True),
        ("Native hit context initializer", 0x1C49290, 0x300, True),
        ("SBShowAnimKey native class getter", 0x2793E50, 0x300, True),
        ("SBShowAnimKey activation/directional/play conditions", 0x2433FE0, 0x4E80, True),
        ("Directional animation play and priority entry", 0x20F4CC0, 0x900, True),
        ("Full-body custom animation play entry", 0x20B2AC0, 0xB00, True),
        ("Full-body animation slot acceptance and priority", 0x20F3A70, 0xB00, True),
        ("Directional animation slot acceptance and priority", 0x20F5170, 0x650, True),
        ("Show animation direction/path helper", 0x2433B30, 0x4B0, True),
        ("Full directional animation hit-vector selection", 0x243E3E0, 0x500, True),
        ("Animation moving-state predicate", 0x20A4A60, 0x120, True),
        ("Animation move-stop exclusion predicate", 0x20A6F40, 0x180, True),
        ("SBShowAnimKey stop/update conditions", 0x2439210, 0x5000, True),
        ("Show context hit-direction field population", 0x1FF33F0, 0x160, True),
        ("Show initialization and hit-level forwarding", 0x2427B10, 0x240, True),
        ("Show instance registration", 0x2429C70, 0xD5, True),
        ("Show key factory hit-level and enabled filtering", 0x2468197, 0xA5, True),
        ("Original target state query", 0x1A64B40, 0xC5, True),
        ("Attachment show key executes reflected character event", 0x2446C90, 0x200, True),
        ("Character attachment-event reflection wrapper", 0x2688A50, 0x42, True),
        ("Child show execution and inherited hit context", 0x2459A30, 0x730, True),
        ("Animation show required/excluded tags and battle-condition gate", 0x243E1E0, 0x200, True),
        ("FName tag map lookup and element stride", 0xE6C4D0, 0xAC, True),
    ]
for label, rva, size, full in regions:
    records.append(f"\n{label}: {rva:08X}, inspection length {size:X}")
    for inst in md.disasm(pe.get_data(rva, size), rva):
        if full or inst.mnemonic in ("call", "ret"):
            records.append(f"{inst.address:08X} {inst.bytes.hex():32s} {inst.mnemonic} {inst.op_str}")

args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text("\n".join(line.rstrip() for line in records) + "\n", encoding="utf-8")
print(f"Saved offline evidence: {args.output}")
