"""Offline P0 version/ABI evidence checks. Never attach to or invoke the game.

Passing these checks proves only the recorded instruction/layout evidence,
not that the show graph is safe to execute for an arbitrary MC target.
"""
import argparse
import copy
import json
from pathlib import Path

import pefile


ANCHORS = {
    "facade_prologue": (0x22857E0, "f30f115c242048894c240855535657415541564157488d6c24f9"),
    "context_virtual_type": (0x2285AB7, "488b5d6f4885db0f84ff020000488b03488bcbff500885c0"),
    "original_nine_argument_caller": (0x1C50373, "c644244001488bc8c644243801488d4590c644243000498bd448894424284488742420e845546300"),
    "context_constructor": (0x1C49290, "33d2488d05e712f703488901"),
    "context_constructor_tail": (0x1C4934C, "488bc148895178889180000000488991840000004889919000000089918c000000c3"),
    "key_enabled_hit_level_filter": (0x24681BC, "807a52007467807a510074054584e4745c"),
    "anim_key_class_check": (0x243405F, "e8ecfd3500488b57104c8d4030486340383b42380f8fc14d0000488bc8488b42304c3904c80f85b04d0000"),
}


def certified(timestamp, image_size, blocks):
    return timestamp == 0x6A6A3B74 and image_size == 0x15981000 and all(
        blocks.get(label) == bytes.fromhex(expected)
        for label, (_, expected) in ANCHORS.items()
    )


def root_evidence(snapshot):
    """Verify the captured root uses separate full/additive native anim keys."""
    if snapshot.get("rejectedReads") != 0:
        return False
    roots = [r for r in snapshot.get("resources", [])
             if r.get("outerNames") == ["Result_Hit_Stand_Sword_LightAttack",
                 "/Game/Art/Show/Hit/Result_Hit_Stand_Sword_LightAttack"]]
    if len(roots) != 1:
        return False
    keys = [k for k in roots[0]["keys"] if k["class"] == "SBShowAnimKey"]
    if len(keys) != 4:
        return False
    for key in keys:
        flags = bytes.fromhex(key["baseConditionBytes"])
        if len(flags) != 16 or flags[10] != 1 or key["priority"] != 100:
            return False
    full = [k for k in keys if k["usesCustomAnimation"] == 1]
    additive = [k for k in keys if k["usesCustomAnimation"] == 0]
    return (len(full) == len(additive) == 2
            and all(bytes.fromhex(k["baseConditionBytes"])[9] == 1 for k in full)
            and all(bytes.fromhex(k["baseConditionBytes"])[9] == 0 for k in additive))


def additive_graph_evidence(snapshot):
    """Structural candidate only: original NoAnim root with HitLevel=false.

    Do not infer that a class name certifies callback or notify purity.
    Unknown/missing active child resources fail this structural check.
    """
    if snapshot.get("rejectedReads") != 0:
        return False
    resources = snapshot.get("resources", [])
    roots = [r for r in resources if r.get("outerNames") == [
        "Result_Hit_Stand_Sword_LightAttack_NoAnim",
        "/Game/Art/Show/Hit/Result_Hit_Stand_Sword_LightAttack_NoAnim"]]
    if len(roots) != 1:
        return False
    animations = [k for k in roots[0]["keys"] if k["class"] == "SBShowAnimKey"]
    if len(animations) != 2 or any(k["usesCustomAnimation"] != 0 for k in animations):
        return False
    by_address = {r["address"]: r for r in resources}
    if len(by_address) != len(resources):
        return False
    allowed = {"SBShowAnimKey", "SBShowPlayShowKey", "SBShowParticleKey",
               "SBShowSoundKey", "SBShowCharSESoundKey", "SBShowCamShakeKey",
               "SBShowMeshVertexShakeKey", "SBShowVibrationKey", "null"}
    pending, visited = [roots[0]], set()
    while pending:
        resource = pending.pop()
        if resource["address"] in visited:
            continue
        visited.add(resource["address"])
        for key in resource["keys"]:
            if key["class"] == "null":
                continue
            flags = bytes.fromhex(key["baseConditionBytes"])
            if len(flags) != 16:
                return False
            if flags[10] == 0 or flags[9] != 0:
                continue  # Match the verified native HitLevel=false filter.
            if key["class"] not in allowed:
                return False
            if key["class"] == "SBShowPlayShowKey":
                child = by_address.get(key.get("showPointer"))
                if child is None:
                    return False
                pending.append(child)
    return True


def missing_execution_evidence(snapshot):
    by_address={r["address"]:r for r in snapshot["resources"]}
    root=next(r for r in snapshot["resources"] if r["name"]=="Result_Hit_Stand_Sword_LightAttack_NoAnim")
    pending,seen,missing_tags,absolute=set(),set(),[],set()
    pending.add(root["address"])
    while pending:
        address=pending.pop()
        if address in seen:
            continue
        seen.add(address)
        for key in by_address[address]["keys"]:
            if key["class"]=="null":
                continue
            flags=bytes.fromhex(key["baseConditionBytes"])
            if not flags[10] or flags[9]:
                continue
            if "checkTags" not in key or "excludeTags" not in key:
                missing_tags.append(key["address"])
            if key["class"]=="SBShowPlayShowKey":
                pending.add(key["showPointer"])
            if key["class"]=="SBShowAnimKey":
                absolute.update(path for path in key["paths"].values() if path.startswith("/Game/"))
    sampled={a["outerNames"][-1] for a in snapshot.get("animations",[])}
    # Both fixed DLC animations are reachable children; light-hit-only sampling
    # cannot prove their notifications or determine whether target tags skip them.
    return {"missingActiveKeyTagRecords":len(missing_tags),
        "unsampledFixedAnimations":sorted(path for path in absolute if path.split(".")[0] not in sampled)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--exe", type=Path, default=Path(
        r"D:\SteamLibrary\steamapps\common\StellarBlade\SB\Binaries\Win64\SB-Win64-Shipping.exe"))
    parser.add_argument("--snapshot", type=Path, default=Path(
        "docs/hit-reaction-20261012/eve-hit-resource-graph.json"))
    args = parser.parse_args()
    pe = pefile.PE(str(args.exe), fast_load=True)
    blocks = {label: pe.get_data(rva, len(bytes.fromhex(expected)))
              for label, (rva, expected) in ANCHORS.items()}
    timestamp, size = pe.FILE_HEADER.TimeDateStamp, pe.OPTIONAL_HEADER.SizeOfImage
    assert certified(timestamp, size, blocks), "Recorded native build/ABI differs"
    mutations = 0
    for label, raw in blocks.items():
        for offset in range(len(raw)):
            changed = dict(blocks)
            changed[label] = raw[:offset] + bytes([raw[offset] ^ 1]) + raw[offset + 1:]
            assert not certified(timestamp, size, changed), (label, offset)
            mutations += 1
    assert not certified(timestamp ^ 1, size, blocks)
    assert not certified(timestamp, size ^ 1, blocks)
    assert not certified(timestamp, size, {})
    snapshot = json.loads(args.snapshot.read_text(encoding="utf-8"))
    assert root_evidence(snapshot), "Original full/additive key separation differs"
    bad = copy.deepcopy(snapshot)
    root = next(r for r in bad["resources"] if r.get("outerNames", [None])[0]
                == "Result_Hit_Stand_Sword_LightAttack")
    key = next(k for k in root["keys"] if k["class"] == "SBShowAnimKey"
               and k["usesCustomAnimation"] == 1)
    flags = bytearray.fromhex(key["baseConditionBytes"])
    flags[9] = 0
    key["baseConditionBytes"] = flags.hex()
    assert not root_evidence(bad), "Lost hard-stun condition was accepted"
    bad = copy.deepcopy(snapshot)
    bad["rejectedReads"] = 1
    assert not root_evidence(bad), "Incomplete snapshot was accepted"
    assert additive_graph_evidence(snapshot), "Original additive graph differs"
    bad = copy.deepcopy(snapshot)
    child = next(r for r in bad["resources"] if r["name"] == "FX_HitImpact_Light")
    child["keys"][0]["class"] = "SBShowSkillResultKey"
    assert not additive_graph_evidence(bad), "Active nested skill result was accepted"
    bad = copy.deepcopy(snapshot)
    bad["resources"] = [r for r in bad["resources"] if r["name"] != "FX_HitImpact_Light"]
    assert not additive_graph_evidence(bad), "Missing active child was accepted"
    print(json.dumps({"instructionAnchors": len(ANCHORS),
        "rejectedByteMutations": mutations, "versionAndMissingEvidenceNegatives": 3,
        "originalFullAdditiveSeparation": "passed", "resourceNegatives": 4,
        "originalNoAnimAdditiveGraphStructure": "passed",
        "missingExecutionEvidence":missing_execution_evidence(snapshot),
        "productionInvocationCertified": False,
        "remaining": ["target-specific child show/attachment event side effects",
                      "native state/resource selection and MC hit-level mapping",
                      "complete MC hit context and runtime identity checks"]}, indent=2))


if __name__ == "__main__":
    main()
