"""Check the sampled NoAnim branch for each ordinary monster; never call the game."""
import argparse
import copy
import json
from pathlib import Path

SAFE_KEYS = {"SBShowAnimKey", "SBShowParticleKey", "SBShowCamShakeKey",
             "SBShowMeshVertexShakeKey", "SBShowVibrationKey", "SBShowCharSESoundKey",
             "SBShowSoundKey", "null"}
SAFE_NOTIFIES = {"SBAnimNotify_FootStep", "SBAnimNotify_CharSESound", "AnimNotify_PlaySound"}


def check(snapshot, character):
    if snapshot["rejectedReads"] or not snapshot.get("keyTagsCaptured"):
        return False, "incomplete snapshot"
    prefix = character["resourcePath"]
    if not prefix.startswith("/Game/Art/Character/Monster/CH_M_NA_") or not prefix.endswith("/"):
        return False, "missing ordinary-monster resource path"
    # Native E6C4D0 gate tests key presence, not the map's reference count.
    tags = {v["tag"] for v in character["showTags"]}
    if tags & {"AdditiveNoPlay", "NoNormalAttShow", "EmilShop_Flag_1st", "Check_Dororong"}:
        return False, "outside ordinary additive candidate scope"
    resources = {r["address"]: r for r in snapshot["resources"]}
    roots = [r for r in snapshot["resources"] if r["name"] == "Result_Hit_Stand_Sword_LightAttack_NoAnim"]
    if len(roots) != 1:
        return False, "ambiguous root"
    animations = {a["outerNames"][-1]: a for a in snapshot["animations"]}
    pending, visited, anim_count = [roots[0]], set(), 0
    while pending:
        resource = pending.pop()
        if resource["address"] in visited:
            continue
        visited.add(resource["address"])
        for key in resource["keys"]:
            if key["class"] == "null":
                continue
            flags = bytes.fromhex(key["baseConditionBytes"])
            if not flags[10] or flags[9]:  # Native enabled / HitLevel=false factory filter.
                continue
            if "checkTags" not in key or "excludeTags" not in key:
                return False, "missing tag conditions"
            if not set(key["checkTags"]) <= tags or set(key["excludeTags"]) & tags:
                continue
            if key["class"] == "SBShowPlayShowKey":
                child = resources.get(key["showPointer"])
                if child is None:
                    return False, "unresolved active child"
                pending.append(child)
            elif key["class"] not in SAFE_KEYS:
                return False, "unsupported active key: " + key["class"]
            elif key["class"] == "SBShowAnimKey":
                anim_count += 1
                for path in set(key["paths"].values()) - {""}:
                    path = path.split(".")[0]
                    if path.startswith("/Game/"):
                        return False, "active absolute animation"
                    sequence = animations.get(prefix + path)
                    if sequence is None:
                        return False, "unsampled animation: " + prefix + path
                    for notify in sequence["notifies"]:
                        if notify.get("state") or notify.get("notify", {}).get("class") not in SAFE_NOTIFIES:
                            return False, "unsupported animation notify"
    return (anim_count > 0, "sampled additive branch" if anim_count else "additive animation excluded")


parser = argparse.ArgumentParser()
parser.add_argument("snapshot", type=Path)
args = parser.parse_args()
snapshot = json.loads(args.snapshot.read_text(encoding="utf-8"))
results = [(c, check(snapshot, c)) for c in snapshot["characters"]]
accepted = [c for c, result in results if result[0]]
assert accepted, "No target has complete sampled additive evidence"
target = accepted[0]
for tag in ("AdditiveNoPlay", "NoNormalAttShow", "EmilShop_Flag_1st", "Check_Dororong"):
    bad = copy.deepcopy(target)
    bad["showTags"].append({"tag": tag, "count": 1})
    assert not check(snapshot, bad)[0], "Special branch/disabled animation was accepted"
bad = copy.deepcopy(snapshot)
bad["rejectedReads"] = 1
assert not check(bad, target)[0]
bad = copy.deepcopy(snapshot)
path = target["resourcePath"] + "Animation/Result_Hit_add_Light_Fw_Uw"
sequence = next(a for a in bad["animations"] if a["outerNames"][-1] == path)
sequence["notifies"].append({"notify": {"class": "SBAnimNotify_ApplyDamage"}})
assert not check(bad, target)[0], "Damage notify was accepted"
sequence["notifies"][-1] = {"state": {"class": "UnknownNotifyState"}, "notify": {"class": "AnimNotify_PlaySound"}}
assert not check(bad, target)[0], "Notify state was accepted"
bad = copy.deepcopy(target)
bad["showTags"].append({"tag": "AdditiveNoPlay", "count": 0})
assert not check(snapshot, bad)[0], "Zero-count tag presence was ignored"
print(json.dumps({"sampledCharacters": len(results), "acceptedSampledBranches": len(accepted),
    "rejected": [{"class": c["class"], "reason": r[1]} for c, r in results if not r[0]],
    "negativeChecks": 8, "productionInvocationCertified": False,
    "limitation": "Snapshot-only tag/notify evidence; runtime identity, state and native context remain required."}, indent=2))
