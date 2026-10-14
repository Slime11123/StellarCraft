"""Audit the read-only heavy-animation snapshot; never invoke game code."""
import json
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
snapshot=json.loads((ROOT/'docs/jump-heavy-20261013/custom-slots-2228.json').read_text(encoding='utf-8'))
assert snapshot['rejectedReads']==0
root=next(r for r in snapshot['resources'] if r['name']=='Result_Hit_Stand_Sword_StrongAttack')
keys=[k for k in root['keys'] if k['class']=='SBShowAnimKey' and k['usesCustomAnimation']]
assert len(keys)==2
families={'09','11','46','47','48','49'}

def configured(character,key):
    paths=character['customAnimationPaths']
    indices=bytes.fromhex(key['directionBytes'])[0x14:0x18]
    # None (0x33) is an intentionally absent direction, not a missing clip.
    if any(i!=0x33 and (i>=len(paths) or not paths[i]) for i in indices): return False
    index=key['customAnimationIndex']
    return 0<=index<len(character['customAnimationSlots'])

results=[]
for character in snapshot['characters']:
    path=character['resourcePath']
    if not any(path==f'/Game/Art/Character/Monster/CH_M_NA_{f}/' for f in families): continue
    results.append({'class':character['class'],'name':character['name'],
                    'configured':all(configured(character,k) for k in keys),
                    'idleSlotPriorities':[s['priority'] for s in character['customAnimationSlots']]})
tested=[c for c in snapshot['characters'] if c['class']=='CH_M_NA_48_Blueprint_C']
assert tested and all(configured(c,k) for c in tested for k in keys)
bad=dict(tested[0]);bad['customAnimationPaths']=[]
assert not configured(bad,keys[0])
bad=dict(tested[0]);bad['customAnimationSlots']=[]
assert not configured(bad,keys[0])
report={'pid':snapshot['pid'],'rejectedReads':0,'sampledCharacters':len(snapshot['characters']),
        'heavyKeys':[{'name':k['name'],'activation':k['activation'],
                      'customAnimationIndex':k['customAnimationIndex']} for k in keys],
        'characters':results,'negativeChecks':2,
        'limitation':'Idle configuration proves neither animation acceptance during a hit nor visible full-body motion.'}
output=ROOT/'docs/jump-heavy-20261013/custom-animation-audit.json'
output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(f'Checked {len(results)} candidate characters; {sum(r["configured"] for r in results)} configured; 2 negative fixtures rejected.')
