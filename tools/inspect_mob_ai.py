"""P0 offline evidence from the local mapped MC JAR, SDK and SB PE; no process access."""
import argparse
import bisect
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess

import capstone
import pefile

SB = Path(r"D:\SteamLibrary\steamapps\common\StellarBlade\SB\Binaries\Win64")
EXPECTED_EXE = "573AAFF1C9455F85EA6036EF6F6CCB0774DE4164722A296276FBBFC7FA87545C"


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest().upper()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--exe", type=Path, default=SB / "SB-Win64-Shipping.exe")
    parser.add_argument("--sdk", type=Path, default=SB / "ue4ss/CXXHeaderDump/SB.hpp")
    parser.add_argument("--jar", type=Path)
    parser.add_argument("--javap", type=Path, default=Path.home() / "jdk-21.0.12.1+1/bin/javap.exe")
    args = parser.parse_args()
    if sha(args.exe) != EXPECTED_EXE:
        parser.error("SB exe hash mismatch; refusing version-specific analysis")
    if args.jar is None:
        jars = list((Path.home() / ".gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged").rglob("*.jar"))
        jars = [p for p in jars if "1.20.1-" in p.name and not p.name.endswith("-sources.jar")]
        if len(jars) != 1:
            parser.error("Choose the exact mapped 1.20.1 JAR with --jar")
        args.jar = jars[0]
    args.output.mkdir(parents=True, exist_ok=True)
    manifest = {"inputs": {str(p): sha(p) for p in (args.exe, args.sdk, args.jar)},
                "nativeTargetInvocationCertified": False,
                "nativeMobLifecycleCertified": False,
                "note": "Static evidence only; references and SDK names do not certify a callable ABI."}
    classes = {
        "net.minecraft.world.entity.Mob": ("serverAiStep", "isEffectiveAi", "setNoAi", "isNoAi", "aiStep"),
        "net.minecraft.world.entity.LivingEntity": ("aiStep", "travel"),
        "net.minecraft.world.entity.Entity": ("move", "collide", "isEffectiveAi"),
        "net.minecraft.world.entity.ai.goal.GoalSelector": ("tick", "tickRunningGoals"),
        "net.minecraft.world.entity.ai.navigation.PathNavigation": ("createPath", "tick", "moveTo"),
        "net.minecraft.world.level.PathNavigationRegion": ("getBlockState", "getFluidState", "getChunk", "getChunkForCollisions", "getEntityCollisions", "getWorldBorder"),
        "net.minecraft.world.level.pathfinder.WalkNodeEvaluator": ("getNeighbors", "findAcceptedNode", "getBlockPathType", "getBlockPathTypeStatic", "getBlockPathTypeRaw", "checkNeighbourBlocks", "getFloorLevel", "hasCollisions", "method_29305"),
        "net.minecraft.world.entity.ai.sensing.Sensing": ("hasLineOfSight",),
        "net.minecraft.world.entity.monster.Zombie": ("registerGoals", "addBehaviourGoals", "customServerAiStep"),
        "net.minecraft.world.entity.animal.Cow": ("registerGoals",),
        "net.minecraft.world.entity.animal.IronGolem": ("registerGoals", "doHurtTarget"),
        "net.minecraft.world.entity.monster.piglin.Piglin": ("customServerAiStep",),
        "net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal": ("canUse", "findTarget", "start"),
        "net.minecraft.world.entity.ai.goal.target.TargetGoal": ("canContinueToUse", "canAttack", "stop"),
        "net.minecraft.world.entity.ai.goal.MeleeAttackGoal": ("canUse", "canContinueToUse", "tick", "checkAndPerformAttack"),
    }
    for cls, methods in classes.items():
        result = subprocess.run([str(args.javap), "-classpath", str(args.jar), "-c", "-p", cls],
                                capture_output=True, text=True, check=True)
        # javap method headings have exactly two leading spaces; bytecode has more.
        chunks = re.split(r"(?=^  \S.*\([^\n]*\);\s*$)", result.stdout, flags=re.M)
        selected = [c for c in chunks if any(re.search(r"\b" + re.escape(m) + r"\(", c.splitlines()[0]) for m in methods)]
        if not selected:
            raise RuntimeError(f"No expected methods found in mapped class {cls}")
        (args.output / (cls.rsplit(".", 1)[1] + ".javap.txt")).write_text(
            f"{cls}\nInput SHA256: {sha(args.jar)}\n" + "\n".join(selected), encoding="utf-8")
    sdk = args.sdk.read_text(encoding="utf-8-sig")
    names = ("FSBCharacterDetectAITableProperty", "FSBSkillTargetFilterTableProperty", "ASBAIController",
             "USBBTTask_SbDetectTarget", "USBBTTask_SbMoveToTarget", "USBBTDecorator_SbCheckTarget")
    excerpts = []
    for name in names:
        match = re.search(r"^(?:class|struct) " + name + r"\b.*?^};[^\n]*", sdk, re.M | re.S)
        if match is None:
            raise RuntimeError(f"SDK declaration missing: {name}")
        line = sdk.count("\n", 0, match.start()) + 1
        excerpts.append(f"SB.hpp:{line}\n{match.group()}")
    for name in ("SBCreateCharacter", "ServerRequest_ActorLockOnTarget", "ClientBroadcast_SkillHitResult"):
        for match in re.finditer(r"^.*\b" + name + r"\([^\n]*", sdk, re.M):
            excerpts.append(f"SB.hpp:{sdk.count(chr(10), 0, match.start()) + 1}\n{match.group()}")
    (args.output / "sdk-excerpts.txt").write_text("\n\n".join(excerpts), encoding="utf-8")

    pe = pefile.PE(str(args.exe), fast_load=True)
    pe.parse_data_directories(directories=[pefile.DIRECTORY_ENTRY["IMAGE_DIRECTORY_ENTRY_EXCEPTION"]])
    ranges = sorted((x.struct.BeginAddress, x.struct.EndAddress) for x in pe.DIRECTORY_ENTRY_EXCEPTION)
    starts = [r[0] for r in ranges]
    md = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_64)
    strings = {}
    tokens = ("SBBTTask_SbDetectTarget", "SBBTTask_SbMoveToTarget", "SBCreateCharacter",
              "ServerRequest_ActorLockOnTarget", "CharacterDetectAI", "AISightSenseNearDistance",
              "ClientBroadcast_SkillHitResult", "bValidEngineHandle")
    for section in pe.sections:
        data = section.get_data()
        for token in tokens:
            for encoding in ("ascii", "utf-16le"):
                needle = (token + "\0").encode(encoding)
                pos = 0
                while (pos := data.find(needle, pos)) >= 0:
                    strings[section.VirtualAddress + pos] = token
                    pos += len(needle)
    refs = []
    for section in pe.sections:
        data = section.get_data()
        if section.Characteristics & 0x20000000:
            for match in re.finditer(rb"[\x48\x4c]\x8d[\x05\x0d\x15\x1d\x25\x2d\x35\x3d]", data):
                pos = match.start()
                if pos + 7 > len(data):
                    continue
                rva = section.VirtualAddress + pos
                target = rva + 7 + struct.unpack_from("<i", data, pos + 3)[0]
                if target in strings:
                    index = bisect.bisect_right(starts, rva) - 1
                    span = ranges[index] if index >= 0 and rva < ranges[index][1] else None
                    refs.append({"token": strings[target], "stringRva": hex(target), "leaRva": hex(rva),
                                 "unwindRange": [hex(v) for v in span] if span else None})
        else:
            for target, name in strings.items():
                needle = struct.pack("<Q", pe.OPTIONAL_HEADER.ImageBase + target)
                pos = 0
                while (pos := data.find(needle, pos)) >= 0:
                    if pos + 16 <= len(data):
                        raw = struct.unpack_from("<Q", data, pos + 8)[0]
                        value = raw - pe.OPTIONAL_HEADER.ImageBase
                        ref = {"token": name, "stringRva": hex(target),
                               "pointerRva": hex(section.VirtualAddress + pos), "followingRawValue": hex(raw)}
                        if 0 <= value < pe.OPTIONAL_HEADER.SizeOfImage:
                            ref["followingValueRva"] = hex(value)
                        refs.append(ref)
                    pos += 8
    (args.output / "native-references.json").write_text(json.dumps(refs, indent=2), encoding="utf-8")
    lines = ["Static xrefs only. Unwind fragments are not proof of function ABI or runtime behavior."]
    spans = set()
    for ref in refs:
        if ref.get("unwindRange"):
            start, end = (int(v, 16) for v in ref["unwindRange"])
        elif "followingValueRva" in ref:
            value = int(ref["followingValueRva"], 16)
            index = bisect.bisect_right(starts, value) - 1
            if index < 0 or not ranges[index][0] <= value < ranges[index][1]:
                continue
            start, end = ranges[index]
        else:
            continue
        if (start, end) in spans:
            continue
        spans.add((start, end))
        lines.append(f"\n{ref['token']} reference/function fragment {start:08X}..{end:08X}")
        for ins in md.disasm(pe.get_data(start, min(end - start, 4096)), start):
            lines.append(f"{ins.address:08X} {ins.mnemonic} {ins.op_str}")
    # Bounded follow-ups traced from the exact version's registration/constructor.
    # These are analysis labels, never approved production entry points.
    regions = (
        ("DetectTarget UObject construction (vtable 5C52268)", 0x26749E0, 0x152),
        ("DetectTarget task slot +2B8 including unwind fragments", 0x200E210, 0x26A),
        ("DetectTarget parameter serialization", 0x1A4CA30, 0x1B9),
        ("Queued task allocation (vtable 5C51678)", 0x201B670, 0x180),
        ("Queue type ID and owner lookup callback", 0x1ABF6F0, 0x4D),
        ("ControlGUID lookup in server manager", 0x1AACFB0, 0xFF),
        ("Server target operation dispatch (18 operation cases)", 0x1A7FC30, 0x3C9B),
        ("Detect operation selects from server actor table", 0x1A36190, 0x17F0),
        ("Debug SBCreateCharacter exec parses arguments then returns", 0x26A3B60, 0x2BD),
        ("ActorLockOnTarget RPC exec validation and dispatch", 0x275E010, 0x19D),
    )
    for label, start, size in regions:
        lines.append(f"\n{label}: {start:08X}..{start+size:08X}")
        for ins in md.disasm(pe.get_data(start, size), start):
            lines.append(f"{ins.address:08X} {ins.mnemonic} {ins.op_str}")
    manifest["boundedRegions"] = [{"label": label, "rva": hex(start), "size": size,
                                    "sha256": hashlib.sha256(pe.get_data(start, size)).hexdigest().upper()}
                                   for label, start, size in regions]
    manifest["vtableLinks"] = {hex(table): [hex(v - pe.OPTIONAL_HEADER.ImageBase)
        for v in struct.unpack("<" + "Q" * (size // 8), pe.get_data(table, size))]
        for table, size in ((0x5C52268, 0x2D8), (0x5C51678, 0x28))}
    manifest["runAiTaskDetectOperation"] = {"taskOperation": 2, "jumpTableRva": "0x1a838cc",
        "caseRva": hex(struct.unpack("<I", pe.get_data(0x1A838CC + 4, 4))[0]),
        "queueTypeNameRva": "0x5c4b410", "queueTypeIdStorageRva": "0x703287c"}
    (args.output / "native-disassembly.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    manifest["nativeReferenceCount"] = len(refs)
    manifest["mappedClasses"] = list(classes)
    (args.output / "inputs.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(f"Saved {len(classes)} mapped class excerpts, SDK excerpts and {len(refs)} static references to {args.output}")
    print("Native target invocation/lifecycle remain uncertified; no game process opened or code invoked.")


if __name__ == "__main__":
    main()
