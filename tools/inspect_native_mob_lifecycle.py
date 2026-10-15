"""Continue mob-AI P0 using files only; never attach, load, or invoke game code."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct

import capstone
import pefile

from inspect_mob_ai import EXPECTED_EXE, SB, sha

# Analysis labels and bounded regions, not approved calling conventions.
REGIONS = (
    ("Network controller class registration", 0x21FBF30, 0xA2),
    ("Network controller constructor thunk", 0x2765F90, 5),
    ("Network controller constructor body", 0xE29A8B0, 0x88),
    ("CreateActor RPC exec", 0x2760E30, 0x638),
    ("CreateActor implementation", 0x23B8550, 0x1C0),
    ("CreateActor request pool", 0x1B43A40, 0x3A8),
    ("Request transport or local queue", 0x1AF3930, 0x332),
    ("CreateActor request execution", 0x1B09750, 0x310),
    ("Server owner creation", 0x1AD1290, 0x678),
    ("Server owner initialization", 0x1BA6D20, 0x1198),
    ("Character creation and registration", 0x1CA9100, 0xE1E),
    ("Client actor creation replay", 0x1B196E0, 0x218),
    ("Actor registration", 0x1AAC240, 0x2BD),
    ("Actor indices insertion", 0x1AAC500, 0x292),
    ("Client despawn RPC exec", 0x27606A0, 0x168),
    ("Client despawn implementation", 0x23B8840, 0x64),
    ("Client despawn pool thunk", 0x1B62C50, 5),
    ("Client despawn pool body", 0xB8E15F0, 0x26D),
    ("Client despawn replay", 0x1B19CC0, 0x1F1),
    ("Enqueue character despawn", 0x1A70A80, 0xBC),
    ("Consume despawn queue", 0x1AAF5D0, 0x23A),
    ("Actor manager update", 0x1AABEA0, 0x59D),
    ("Actor indices removal", 0x1AAC8C0, 0x2C3),
    ("Actor manager world clear", 0x1AAB870, 0xD5),
    ("Server owner manager sweep and prune", 0x1AD57A0, 0xEF2),
    ("Server owner retention predicate", 0x1BA6D00, 0x1B),
    ("Server owner cleanup prefix", 0x1B9F670, 0x160),
    ("Actor interface predicate called by owner retention", 0x1A6DE30, 0x50),
    ("RunAITask callback construction", 0x6E8130, 0x51),
    ("Callback list registration", 0x2267960, 0x99),
    ("Callback registry indexing", 0x2267A00, 0x3CE),
    ("Debug despawn exec parses and frees string only", 0x2697C70, 0x119),
    ("Authoritative stat effect target/source and debit", 0x1BA8720, 0x120),
    ("Client stat replay uses same debit with distinct caller", 0x1B1F7FC, 0x60),
    ("Effect target owner store", 0x1BC609A, 4),
    ("Effect source owner store", 0x1BC64CF, 4),
)

# Instructions/data links observed independently during the static trace.
# Matching these cannot certify runtime ABI.
CALLS = (
    ("request executes server owner creation", 0x1B099ED, 0x1AD1290),
    ("server owner initialization", 0x1AD1438, 0x1BA6D20),
    ("character factory", 0x1BA73BF, 0x1CA9100),
    ("factory registers actor", 0x1CA98F5, 0x1AAC240),
    ("registration inserts indices", 0x1AAC332, 0x1AAC500),
    ("client create uses same registration", 0x1B197C8, 0x1AAC240),
    ("despawn replay queues removal", 0x1B19D95, 0x1A70A80),
    ("queued despawn state transition", 0x1AAF654, 0x1A85000),
    ("queued destroy state transition", 0x1AAF793, 0x1A85000),
    ("manager removes actor", 0x1AAC1FA, 0x1AAC8C0),
    ("manager drains despawn queue", 0x1AAC212, 0x1AAF5D0),
    ("world clear removes actor", 0x1AAB8B0, 0x1AAC8C0),
    ("removal erases ControlGUID", 0x1AAC99A, 0x1ADDEF0),
    ("removal releases actor interface", 0x1AACACC, 0x1C82DA0),
    ("server authoritative stat debit", 0x1BA881E, 0x1A64CE0),
    ("client replay stat debit", 0x1B1F826, 0x1A64CE0),
    ("owner removal broadcasts before release", 0x1AD5E91, 0x1AF3C70),
    ("owner removal releases owner", 0x1AD5EA2, 0x1C82DA0),
)
POINTERS = (
    ("CreateActor native exec registration", 0x5E9C6A8, 0x2760E30),
    ("CreateActor request executor", 0x5C06330, 0x1B09750),
    ("Client create packet executor", 0x5BF9E38, 0x1B196E0),
    ("Client despawn packet executor", 0x5BF9DC0, 0x1B19CC0),
    ("Network controller create implementation", 0x5CC6798 + 0x15B8, 0x23B8550),
    ("Network controller despawn implementation", 0x5CC6798 + 0x1598, 0x23B8840),
    ("RunAITask callback type ID getter", 0x5BF6D70, 0x1ABF6F0),
    ("RunAITask callback handler", 0x5BF6D78, 0x1ABF700),
    ("Server owner cleanup virtual slot", 0x5C0BEE0 + 0x10, 0x1B9F670),
    ("Server owner retention virtual slot", 0x5C0BEE0 + 0xB8, 0x1BA6D00),
)

QUALIFICATION_REGIONS = (
    ("Tribe relation table lookup", 0x1C77590, 0x85),
    ("Directional ten-slot tribe relation resolver", 0x2620CD0, 0x7C),
    ("DetectTarget candidate selection", 0x1A36190, 0x17F0),
    ("DetectTarget geometry and optional obstruction", 0x1C77E20, 0x779),
    ("Obstruction bridge resolves both engine actors", 0x226EDD0, 0x16D),
    ("Skill target filtering fragment including engine handle and hidden", 0x1C73396, 0x21F),
    ("Character RefAppearance to asset path", 0x1CA92EB, 0x71),
    ("Engine actor creation followed by native registration", 0x1CA9774, 0x18C),
    ("Engine character creation and initialization", 0x226DC70, 0xA7C),
    ("Owner retention delegates to actor interface", 0x1BA6D00, 0x1B),
    ("Actor interface retention predicate", 0x1A6DE30, 0x44),
    ("Additional retention conditions and related actors", 0x1A6DE90, 0x138),
    ("SetActorHiddenInGame native exec", 0x46B3E70, 0xD8),
    ("SetActorHiddenInGame sets actor byte 58 bit 5", 0x3F68FB0, 0x26),
)
QUALIFICATION_CALLS = (
    ("Tribe row lookup", 0x1C775D0, 0x1A9EBE0),
    ("DetectTarget relation", 0x1A365AF, 0x1C77590),
    ("DetectTarget geometry", 0x1A36697, 0x1C77E20),
    ("DetectTarget obstruction bridge", 0x1C784F7, 0x226EDD0),
    ("Obstruction source handle lookup", 0x226EE06, 0x201B1E0),
    ("Obstruction target handle lookup", 0x226EE38, 0x201B1E0),
    ("Skill engine handle lookup", 0x1C73534, 0x201B1E0),
    ("Skill target relation", 0x1C73593, 0x1C77590),
    ("Character appearance row", 0x1CA92FA, 0x1A9A7E0),
    ("Character engine construction", 0x1CA97C6, 0x226DC70),
    ("Engine spawn", 0x226DD52, 0x22ADBB0),
    ("Actor retention additional state", 0x1A6DE61, 0x1A854D0),
)
QUALIFICATION_POINTERS = (
    ("SetActorHiddenInGame exec registration", 0x64CAA98, 0x46B3E70),
    ("SetActorHiddenInGame implementation slot", 0x5CC6798 + 0x338, 0x3F68FB0),
    ("Owner retention slot", 0x5C0BEE0 + 0xB8, 0x1BA6D00),
    ("Actor interface retention slot", 0x5BF67D0 + 0xB8, 0x1A6DE30),
    ("Actor interface additional retention slot", 0x5BF67D0 + 0xC8, 0x1A6DE90),
)

COMPONENT_REGIONS = (
    ("Actor-state presence query", 0x1A64B40, 0xC5),
    ("Actor-state integer count query", 0x1A64C10, 0xC5),
    ("HP debit respects Immortal lower bound", 0x1A64E79, 0x5D),
    ("Additional damage path rejects Immortal state", 0x1BB13AC, 0x15),
    ("AI positioning state selects manager actor index", 0x1BE60AD, 0x51),
)
COMPONENT_CALLS = (
    ("HP debit checks Immortal state", 0x1A64E8A, 0x1A64B40),
    ("HP write converts stat value", 0x1A64EC9, 0x1C77620),
    ("Additional damage path state check", 0x1BB13B4, 0x1A64B40),
    ("AI positioning reads state count", 0x1BE60CF, 0x1A64C10),
    ("AI positioning resolves actor manager", 0x1BE60D8, 0x1A97C40),
)
COMPONENT_BYTES = (
    ("Immortal state ID 27", 0x1A64E82, bytes.fromhex("ba1b000000")),
    ("UsePlayerAIPositioning state ID 104", 0x1BE60C7, bytes.fromhex("ba68000000")),
    ("Immortal HP lower bound 1.0f", 0x6749128, struct.pack("<f", 1.0)),
)

BINDING_REGIONS = (
    ("Create RPC implementation copies all user parameters and internal defaults", 0x23B8550, 0xE2),
    ("Create request chooses event spawn or full owner factory", 0x1B097F5, 0x1FD),
    ("Full owner factory forwards extended initialization arguments", 0x1AD13B1, 0x8C),
    ("Create request publishes boolean completion only", 0x1B09A15, 0x3F),
    ("Effect iterates multiple target stat records with same object ID", 0x1BCA980, 0xF0),
    ("Effect stat execution arguments fragment", 0x1BCD318, 0x73),
    ("Authoritative effect object to raw source key and stat debit", 0x1BA8720, 0x120),
    ("Client stat replay has separate caller", 0x1B1F7FC, 0x60),
    ("Owner stat debit can recurse to linked owner", 0x1BA8A28, 0x79),
    ("Effect ID getter used in target loop", 0xE4D660, 4),
)
BINDING_CALLS = (
    ("RPC allocates request", 0x23B8598, 0x1B43A40),
    ("RPC hands request to transport", 0x23B862D, 0x1AF3930),
    ("Normal request dispatches event spawn", 0x1B09854, 0x1C1C9A0),
    ("Full request dispatches owner factory", 0x1B099ED, 0x1AD1290),
    ("Owner initialization extended arguments", 0x1AD1438, 0x1BA6D20),
    ("Create completion packet pool", 0x1B09A36, 0x1B649D0),
    ("Create completion broadcast", 0x1B09A4F, 0x1AF3C70),
    ("Effect target loop stat debit", 0x1BCAA10, 0x1BA8720),
    ("Effect stat execution debit", 0x1BCD386, 0x1BA8720),
    ("Linked owner recursive debit", 0x1BA8A96, 0x1BA8720),
    ("Authoritative source effect lookup", 0x1BA87C1, 0x1AD6A10),
    ("Authoritative stat call", 0x1BA881E, 0x1A64CE0),
    ("Client replay stat call", 0x1B1F826, 0x1A64CE0),
)
BINDING_POINTERS = (
    ("Effect object ID getter virtual slot", 0x5C0BD18 + 0x38, 0xE4D660),
)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--exe", type=Path, default=SB / "SB-Win64-Shipping.exe")
    parser.add_argument("--output", type=Path, required=True)
    mode_group = parser.add_mutually_exclusive_group()
    mode_group.add_argument("--qualification", action="store_true", help="Trace target eligibility and resource/retention conditions")
    mode_group.add_argument("--components", action="store_true", help="Trace candidate default-effect state consumers")
    mode_group.add_argument("--bindings", action="store_true", help="Trace create parameters and effect/target event identity")
    args = parser.parse_args()
    if sha(args.exe) != EXPECTED_EXE:
        parser.error("SB exe hash mismatch; refusing version-specific evidence")
    pe = pefile.PE(str(args.exe), fast_load=True)
    if pe.OPTIONAL_HEADER.ImageBase != 0x140000000:
        parser.error("Unexpected preferred image base")
    md = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_64)
    mode = "bindings" if args.bindings else "components" if args.components else "qualification" if args.qualification else "lifecycle"
    regions_to_read, calls, pointers = {
        "bindings": (BINDING_REGIONS, BINDING_CALLS, BINDING_POINTERS),
        "components": (COMPONENT_REGIONS, COMPONENT_CALLS, ()),
        "qualification": (QUALIFICATION_REGIONS, QUALIFICATION_CALLS, QUALIFICATION_POINTERS),
        "lifecycle": (REGIONS, CALLS, POINTERS),
    }[mode]
    anchors = [(name, rva, b"\xE8" + struct.pack("<i", target - rva - 5)) for name, rva, target in calls]
    anchors += [(name, rva, struct.pack("<Q", 0x140000000 + target)) for name, rva, target in pointers]
    if args.components:
        anchors += list(COMPONENT_BYTES)
    records = []
    for name, rva, expected in anchors:
        actual = pe.get_data(rva, len(expected))
        if actual != expected:
            parser.error(f"Evidence anchor mismatch: {name} at {rva:X}")
        records.append({"label": name, "rva": hex(rva), "bytes": actual.hex()})
    args.output.mkdir(parents=True, exist_ok=True)
    lines = ["File-only P0 trace. All addresses are RVAs; labels do not certify ABI.",
             "Bounded linear disassembly can contain unreachable bytes after branches.",
             "Do not invoke these functions or create characters from these excerpts."]
    regions = []
    for label, start, size in regions_to_read:
        raw = pe.get_data(start, size)
        regions.append({"label": label, "rva": hex(start), "size": size,
                        "sha256": hashlib.sha256(raw).hexdigest().upper()})
        lines.append(f"\n{label}: {start:08X}..{start+size:08X}")
        lines += [f"{i.address:08X} {i.mnemonic} {i.op_str}" for i in md.disasm(raw, start)]
    (args.output / "lifecycle-disassembly.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    sdk = SB / "ue4ss/CXXHeaderDump/SB.hpp"
    sdk_text = sdk.read_text(encoding="utf-8-sig")
    names = ("ServerRequest_CreateActor", "ClientBroadcast_CreateActor", "ClientBroadcast_DespawnActor", "SBCharacterDespawnFromTag")
    excerpts = []
    for name in names:
        match = re.search(r"^.*\b" + name + r"\([^\n]*", sdk_text, re.M)
        if match is None:
            raise RuntimeError(f"Missing SDK declaration: {name}")
        excerpts.append(f"SB.hpp:{sdk_text.count(chr(10), 0, match.start())+1}\n{match.group()}")
    if args.qualification:
        for name in ("FSBCharacterTableProperty", "FSBCharacterAppearanceTableProperty", "FSBCharacterTribeTableProperty",
                     "FSBCharacterDetectAITableProperty", "FSBSkillTargetFilterTableProperty",
                     "FSBCharacterCollisionShapeInfo", "FSBCharacterAttackedCollisionGroup"):
            match = re.search(r"^struct " + name + r"\b.*?^};[^\n]*", sdk_text, re.M | re.S)
            if match is None:
                raise RuntimeError(f"Missing SDK declaration: {name}")
            excerpts.append(f"SB.hpp:{sdk_text.count(chr(10), 0, match.start())+1}\n{match.group()}")
    enums = SB / "ue4ss/CXXHeaderDump/SB_enums.hpp"
    match = re.search(r"enum ESBActorLifeState \{.*?\};", enums.read_text(encoding="utf-8-sig"), re.S)
    if match is None:
        raise RuntimeError("Missing life-state enum")
    excerpts.append(match.group())
    if args.qualification:
        for name in ("ESBTribeRelation", "ESBActorType", "ESBTargetActor"):
            match = re.search(r"enum " + name + r" \{.*?\};", enums.read_text(encoding="utf-8-sig"), re.S)
            if match is None:
                raise RuntimeError(f"Missing SDK enum: {name}")
            excerpts.append(match.group())
    if args.components:
        for name in ("ESBActorState", "ESBEffectLifeType", "ESBEffectOverlap"):
            match = re.search(r"enum " + name + r" \{.*?\};", enums.read_text(encoding="utf-8-sig"), re.S)
            if match is None:
                raise RuntimeError(f"Missing SDK enum: {name}")
            excerpts.append(match.group())
        for name in ("USBCharacterCollisionData", "USBCollisionGroupComponent"):
            match = re.search(r"^class " + name + r"\b.*?^};[^\n]*", sdk_text, re.M | re.S)
            if match is None:
                raise RuntimeError(f"Missing SDK class: {name}")
            excerpts.append(match.group())
    (args.output / "sdk-lifecycle.txt").write_text("\n\n".join(excerpts), encoding="utf-8")
    report = {"exeSha256": EXPECTED_EXE, "sdkSha256": sha(sdk), "enumSha256": sha(enums),
              "toolSha256": sha(Path(__file__)), "mode": mode, "staticAnchorsPassed": len(records),
              "anchors": records, "regions": regions,
              "nativeMobLifecycleCertified": False, "nativeMobHitCertified": False,
              "runtimeTested": False, "productionModified": False,
              "note": "Static call/pointer evidence only. No runtime lifetime, thread or proxy qualification certification."}
    (args.output / "lifecycle-evidence.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(f"Saved {len(regions_to_read)} bounded regions; {len(records)} static call/pointer anchors matched.")
    print("Lifecycle/hit certification remains false; no game process opened or game function called.")


if __name__ == "__main__":
    main()
