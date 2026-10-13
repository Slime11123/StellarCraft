"""Offline certificate for the limited ordinary StrongAttack branch. No game calls."""
import copy
import json
from pathlib import Path

SNAPSHOT=Path('docs/jump-heavy-20261013/strong-levels-52216.json')
FAMILIES=('09','11','46','47','48','49')
SAFE={'SBShowAnimKey','SBShowParticleKey','SBShowCamShakeKey','SBShowMeshVertexShakeKey',
      'SBShowVibrationKey','SBShowCharSESoundKey','SBShowSoundKey','null'}
NOTIFIES={'SBAnimNotify_FootStep','SBAnimNotify_CharSESound','AnimNotify_PlaySound'}

def certified(snapshot,index):
    if snapshot['rejectedReads'] or not snapshot.get('keyTagsCaptured'): return False
    levels=[r for t in snapshot['tables'] for r in t.get('eveSwordLevels',[])
            if r['name']=='P_Eve_Sword_Normal_StrongAttack2_1']
    if len(levels)!=1 or levels[0]['id']!=1001007 or levels[0]['hitLevel']!=1: return False
    resources={r['address']:r for r in snapshot['resources']}
    roots=[r for r in snapshot['resources'] if r['name']=='Result_Hit_Stand_Sword_StrongAttack']
    if len(roots)!=1: return False
    clips={a['outerNames'][-1]:a for a in snapshot['animations']}
    for family in FAMILIES:
        prefix='/Game/Art/Character/Monster/CH_M_NA_'+family+'/'
        # Current complete base-game IoStore index has no strong child asset in
        # these families. Native 2459E0A..2459E15 declines a null child resource.
        if any(prefix.replace('/Game/','../../../SB/Content/') in line and 'HitReact_StrongAtk.uasset' in line
               for line in index.splitlines()): return False
        pending=[roots[0]];visited=set();full=0
        while pending:
            resource=pending.pop()
            if resource['address'] in visited: continue
            visited.add(resource['address'])
            for key in resource['keys']:
                if key['class']=='null': continue
                flags=bytes.fromhex(key['baseConditionBytes'])
                if not flags[10]: continue
                if 'checkTags' not in key or 'excludeTags' not in key: return False
                if key['checkTags']: continue  # Runtime entry requires empty target/context tags.
                if key['class']=='SBShowPlayShowKey':
                    child=resources.get(key['showPointer'])
                    if child is None:
                        if key['showPath']!='Show/HitReact_StrongAtk.HitReact_StrongAtk': return False
                    else: pending.append(child)
                elif key['class'] not in SAFE: return False
                elif key['class']=='SBShowAnimKey':
                    if key['usesCustomAnimation']:
                        if not flags[9]: return False
                        full+=1
                    for path in set(key['paths'].values())-{''}:
                        if path.startswith('/Game/'): return False
                        animation=clips.get(prefix+path.split('.')[0])
                        if animation is None: return False
                        if any(n.get('state') or n.get('notify',{}).get('class') not in NOTIFIES
                               for n in animation['notifies']): return False
        if full!=2: return False
    return True

snapshot=json.loads(SNAPSHOT.read_text(encoding='utf-8'))
index=Path('docs/hit-reaction-20261012/resource-index-evidence.txt').read_text(encoding='utf-8')
assert certified(snapshot,index)
bad=copy.deepcopy(snapshot);bad['rejectedReads']=1;assert not certified(bad,index)
bad=copy.deepcopy(snapshot)
row=next(r for t in bad['tables'] for r in t.get('eveSwordLevels',[]) if r['id']==1001007)
row['hitLevel']=3;assert not certified(bad,index)
bad=copy.deepcopy(snapshot)
root=next(r for r in bad['resources'] if r['name']=='Result_Hit_Stand_Sword_StrongAttack')
root['keys'][0]['class']='SBShowSkillResultKey';assert not certified(bad,index)
bad=copy.deepcopy(snapshot)
clip=next(a for a in bad['animations'] if '/CH_M_NA_48/Animation/Result_Hit_Stand_Light_Bw' in a['outerNames'][-1])
clip['notifies'].append({'notify':{'class':'ApplyDamageNotify'}});assert not certified(bad,index)
assert not certified(snapshot,index+'\n../../../SB/Content/Art/Character/Monster/CH_M_NA_48/Show/HitReact_StrongAtk.uasset')
print(json.dumps({'sixOrdinaryFamilies':FAMILIES,'nativeOrdinaryStrongSkill':1001007,'hitLevel':1,
    'fullBodyKeys':2,'noRepeatedSkillDamageOrHitStun':True,'negativeChecks':5,
    'missingRelativeChild':'declined by original native null guard',
    'inGameHeavyVerified':False},indent=2))
