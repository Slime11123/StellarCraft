"""Extract the REAL damage-conversion block out of main.lua and exercise it.

Copies are worthless for a test like this, so we splice the actual source region
(actor_num + TIER_BUDGET + ENEMY_HP_TABLE + resolve_enemy) into a temp file and
run it under the freshly built lua.exe.
"""
import os
import re
import subprocess
import sys

_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))  # stellarcraft/
SRC = os.path.join(_ROOT, 'sb-mod', 'lua', 'main.lua')
LUA = os.path.join(_ROOT, 'sb-mod', 'build', 'manual', 'lua.exe')

src = open(SRC, encoding='utf-8').read()

start = src.index('-- Read a float/number field defensively')
marker = 'return tier, maxHp\nend\n'
end = src.index(marker, start) + len(marker)
block = src[start:end]

# Guard: make sure we grabbed the table too, not just actor_num.
for needed in ('TIER_BUDGET', 'ENEMY_HP_TABLE', 'normalize_class_name', 'resolve_enemy'):
    assert needed in block, 'extraction missed ' + needed

harness = block + r'''

-- ---- harness --------------------------------------------------------------
-- A fake actor: no MaxHP property, so resolve_enemy must fall back to the table.
local FakeActor = {}
FakeActor.__index = FakeActor
function FakeActor:IsValid() return true end

local function fake(clsName)
    return setmetatable({ cls = clsName }, FakeActor)
end

-- Build a GetClass():GetFName():ToString() chain like UE4SS exposes.
FakeActor.GetClass = function(self)
    return { GetFName = function() return { ToString = function() return self.cls end } end }
end

local cases = {
    { 'CH_M_NA_11_Blueprint_C',      'Creeper (Minion)',        'Minion'  },
    { 'CH_M_NA_52_Blueprint_C',      'Thornhead (Minion)',      'Minion'  },
    { 'CH_M_NA_19_Blueprint_C',      'Skull Gunner (Warrior)',  'Warrior' },
    { 'CH_M_NA_40_Type_C_Blueprint_C', 'Cannon Guardian',       'Warrior' },
    { 'CH_M_NA_15_Blueprint_C',      'Juggernaut (Elite)',      'Elite'   },
    { 'CH_M_NA_22_Blueprint_C',      'Democrawler (Elite)',     'Elite'   },
    { 'CH_NPC_Raven_Blueprint_C',    'Raven (Alpha)',           'Alpha'   },
    { 'CH_M_NA_42_C',                'Unidentified (no _Blueprint_C suffix)', 'Alpha' },
    { 'CH_M_NA_56_Blueprint_C',      'Elder Naytiba (true form)', 'Elder' },
    { 'CH_M_NA_99_Blueprint_C',      'UNKNOWN class',           'Warrior' },
}

print(string.format('%-38s %-9s %9s %9s %8s', 'class', 'tier', 'maxHp', 'hit@7mc', 'hits'))
print(string.rep('-', 80))
local fails = 0
for _, c in ipairs(cases) do
    local cls, label, wantTier = c[1], c[2], c[3]
    local tier, maxHp = resolve_enemy(fake(cls), cls)
    local d = maxHp * (7 / TIER_BUDGET[tier])
    if d < 1.0 then d = 1.0 end
    if d > maxHp then d = maxHp end
    local ok = (tier == wantTier)
    if not ok then fails = fails + 1 end
    print(string.format('%-38s %-9s %9d %9.0f %8.2f  %s',
        label, tier, maxHp, d, maxHp / d, ok and '' or ('<<< want ' .. wantTier)))
end

-- Explosion sanity: TNT centre damage is ~56 raw.
print()
print('TNT (56 raw MC damage) on each tier:')
for _, t in ipairs({ 'Minion', 'Warrior', 'Elite', 'Alpha', 'Elder' }) do
    local maxHp = TIER_FALLBACK_HP[t]
    local d = maxHp * (56 / TIER_BUDGET[t])
    if d < 1.0 then d = 1.0 end
    if d > maxHp then d = maxHp end
    print(string.format('  %-8s maxHp=%-8d native=%-9.0f = %5.1f%% of the bar',
        t, maxHp, d, 100 * d / maxHp))
end

print()
print(fails == 0 and 'ALL TIER LOOKUPS OK' or ('FAILURES: ' .. fails))
'''

out = '/tmp/sctest.lua'
with open(out, 'w', encoding='utf-8') as f:
    f.write(harness)

r = subprocess.run([LUA, out], capture_output=True, text=True, encoding='utf-8')
sys.stdout.write(r.stdout)
sys.stderr.write(r.stderr or '')
sys.exit(r.returncode)
