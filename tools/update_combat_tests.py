from pathlib import Path
for name in ['tools/_motion_protocol_probe.lua','sb-mod/tests/native-movement-regression.lua']:
 p=Path(name);s=p.read_text();s=s.replace('protocolVersion==3','protocolVersion==4').replace('protocolVersion=3','protocolVersion=4');p.write_text(s)
p=Path('sb-mod/tests/native-combat-gate.cpp');s=p.read_text().replace('0xE36F9B};','0xE36F9B,0x1BA875F};').replace('{32,32,16,6};','{32,32,16,6,36};').replace('i<4','i<5').replace('86 mutated','122 mutated');s=s.replace('    assert(argc==2);','''    assert(argc==2);
    assert(native_combat::converted_damage(6500,6500,7,40)==1137.5f);
    assert(native_combat::converted_damage(130000,130000,7,200)==4550.f);
    assert(native_combat::converted_damage(6500,6500,56,40)==6500.f);
    assert(native_combat::converted_damage(100,6500,7,40)==100.f);
    assert(native_combat::converted_damage(100,100,.01f,40)<1.f);''');p.write_text(s)
p=Path('sb-mod/tests/native-player-gate.cpp');s=p.read_text().replace('0x1B1F7FC};','0x1B1F7FC,0x1BA875F,0x1BA87B3};').replace('61,20,66};','61,20,66,36,41};').replace('i<14','i<16').replace('389 mutated','466 mutated');p.write_text(s)
p=Path('sb-mod/tests/proto_layout_check.cpp');s=p.read_text();s=s.replace('    PRINT(kVersion);','    PRINT(kVersion);');i=s.index('int main(');s=s[:i]+'''static_assert(offsetof(ActorRecord,nativeGuid)==40);
static_assert(offsetof(ActorRecord,world)==48);
static_assert(offsetof(CombatHit,id)==8);
static_assert(offsetof(CombatHit,x)==48);
static_assert(kOffActorTable>=kOffAppearance+kAppearanceBytes);
static_assert(kOffCombat>=kOffActorTable+sizeof(ActorTable));
'''+s[i:];p.write_text(s)