"""Reject HP or chained-effect changes in the sampled HitStun resource.

This checks data purity only. It grants no permission to call an effect API.
"""
import copy
import json
import math
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
source=ROOT/'docs/jump-heavy-20261014/test-2126/hit-stun-config.json'
data=json.loads(source.read_text(encoding='utf-8'))

def pure_timed_block(effect):
    return (effect['id']==2003000 and effect['actorStates']==[1,2,0,0,0,0,0,0,0,0]
            and effect['statType']==0 and effect['calculationValue']==0
            and effect['lifeType']==2 and math.isfinite(effect['defaultLifeTime'])
            and abs(effect['defaultLifeTime']-0.1)<1e-6
            and not any(effect['shows'].values())
            and not any(effect[key] for key in ('activeEffects','loopEffects','deactiveEffects','chainEffects')))

assert pure_timed_block(data)
fixtures=(('id',1000020),('statType',1),('calculationValue',10),
          ('actorStates',[3,2,0,0,0,0,0,0,0,0]),('lifeType',0),
          ('defaultLifeTime',float('nan')),('chainEffects',['SkillDamage_Eve']),
          ('shows',{'active':'Hit/Unverified','loop':'','deactive':''}))
for field,value in fixtures:
    bad=copy.deepcopy(data);bad[field]=value
    assert not pure_timed_block(bad),field
report={'effectId':2003000,'states':['BlockMove','BlockSkill'],
        'nativeDefaultLifetime':0.1,'requiredStrongSkillOverride':0.3,
        'negativeChecks':len(fixtures),'resourceOnly':True,
        'nativeTimedInvocationCertified':False,'inGameHeavyVerified':False}
output=ROOT/'docs/jump-heavy-20261014/hit-stun-evidence-check.json'
output.write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
print('PASS: sampled pure timed-block data; 8 HP/state/lifecycle/chain/show mutations rejected. Native invocation remains uncertified.')
