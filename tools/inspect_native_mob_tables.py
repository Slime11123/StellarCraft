"""Offline, version-pinned character/tribe/appearance row inspection; never start the game.

Unversioned fragment layout cross-checked against CUE4Parse FFragment/FUnversionedHeader:
https://github.com/FabianFG/CUE4Parse/tree/master/CUE4Parse/UE4/Assets/Objects/Unversioned
Only explicit nonzero properties are exported. Missing fields are not inferred defaults.
"""
from pathlib import Path
import argparse, re, struct, json, subprocess, tempfile
from inspect_mob_ai import SB, sha

SDK = ""

def schema(name):
    text = re.search(r'^struct '+name+r'\b.*?^};', SDK, re.M|re.S).group()
    return [(m[1].strip(), m[2]) for line in text.splitlines() if (m := re.match(r'\s+(.+?)\s+(\w+);\s*//', line))]

class Reader:
    def __init__(self, path):
        self.b=path.read_bytes(); self.p=0; self.names=[]
        start,size=struct.unpack_from('<II',self.b,24)
        self.p=start
        while self.p < start+size:
            a,c=self.read(2); count=((a&127)<<8)|c
            raw=self.read(count*(2 if a&128 else 1))
            self.names.append(raw.decode('utf-16-le' if a&128 else 'utf-8'))
        assert self.p==start+size
        start,size=struct.unpack_from('<II',self.b,52)
        self.p=start+size
    def read(self,n):
        assert 0<=n<=len(self.b)-self.p,(self.p,n)
        v=self.b[self.p:self.p+n];self.p+=n;return v
    def num(self,fmt):return struct.unpack('<'+fmt,self.read(struct.calcsize('<'+fmt)))[0]
    def fname(self):
        index=self.num('I');number=self.num('I')
        assert index>>30==0 and index<len(self.names),(hex(index),number,self.p)
        return self.names[index]+('_'+str(number-1) if number else '')
    def value(self,typ):
        if typ=='FName':return self.fname()
        if typ in ('float','int32','uint32'):return self.num({'float':'f','int32':'i','uint32':'I'}[typ])
        if typ=='bool' or typ.startswith('TEnumAsByte<'):return self.num('B')
        if typ=='FString':
            n=self.num('i');assert abs(n)<1000000
            return self.read(abs(n)*(2 if n<0 else 1)).decode('utf-16-le' if n<0 else 'utf-8').rstrip('\0')
        if typ.startswith('TArray<'):
            n=self.num('i');assert 0<=n<10000
            return [self.value(typ[7:-1]) for _ in range(n)]
        if typ=='FSBJsonEffect':return self.props(schema(typ))
        if typ=='FSBJsonEffectEndCondition':return self.props(schema(typ))
        if typ in ('FVector','FRotator'):return [self.num('f') for _ in range(3)]
        raise ValueError(typ)
    def props(self,fields):
        fragments=[];bits=0
        while True:
            f=self.num('H');fragments.append(f)
            if f&128:bits+=f>>9
            if f&256:break
            assert len(fragments)<100
        mask=int.from_bytes(self.read(0 if bits==0 else 1 if bits<=8 else 2 if bits<=16 else 4*((bits+31)//32)),'little')
        index=0;bit=0;result={}
        for f in fragments:
            index+=f&127
            for _ in range(f>>9):
                assert index<len(fields),(index,len(fields),self.p)
                typ,name=fields[index];zero=bool(mask&(1<<bit)) if f&128 else False
                if f&128:bit+=1
                if not zero:result[name]=self.value(typ)
                index+=1
        return result


def main():
    global SDK
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--decoder', type=Path, required=True, help='Standalone powzix/ooz executable, built outside the project')
    parser.add_argument('--decoder-sha256', required=True, help='Expected SHA256 of the reviewed local decoder')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--components', action='store_true', help='Inspect candidate effects, stances, target filters and blueprint names')
    args = parser.parse_args()
    decoder = args.decoder.resolve()
    if sha(decoder) != args.decoder_sha256.upper():
        raise ValueError('Decoder hash mismatch; refusing to execute')
    sdk = SB / 'ue4ss/CXXHeaderDump/SB.hpp'
    if sha(sdk) != '87B1A4BC07426787C831426267DC92D7378C053A1DC4C6A7A69738DBEE03C532':
        raise ValueError('SDK schema version mismatch')
    SDK = sdk.read_text('utf-8-sig')
    paks = SB.parent.parent / 'Content/Paks'
    utoc = paks / 'pakchunk0-WindowsNoEditor.utoc'
    if sha(utoc) != 'C16153B63CCCABA834CB74402DC56F037D11571494D7A675E95E9E29D01B3E61':
        raise ValueError('IoStore version mismatch')
    b = utoc.read_bytes()
    assert b[:16] == b'-==--==--==--==-' and b[16] == 3 and not b[80] & 2
    h,n,blocks,entry_size,methods,method_length,block_size = struct.unpack_from('<7I',b,20)
    assert h == 144 and entry_size == 12 and block_size == 1048576
    method_start = h+n*22+blocks*entry_size
    assert b[method_start:method_start+method_length].split(b'\0')[0] == b'Oodle'
    specifications = [('CharacterTribeTable',157359,'FSBCharacterTribeTableProperty'),
                      ('CharacterTable',196268,'FSBCharacterTableProperty'),
                      ('CharacterAppearanceTable',157353,'FSBCharacterAppearanceTableProperty')]
    if args.components:
        specifications += [('EffectTable',157372,'FSBEffectTableProperty'),
                           ('CharacterStanceTable',157358,'FSBCharacterStanceTableProperty'),
                           ('TargetFilterTable',157469,'FSBSkillTargetFilterTableProperty'),
                           ('WallLeft',93404,None),('WallCenter',93405,None),
                           ('WallRight',93406,None),('DummyBlueprint',93374,None)]
    all_rows, records, blueprints = {}, [], {}
    with tempfile.TemporaryDirectory(prefix='stellarcraft-table-audit-') as temporary:
        root = Path(temporary)
        with (paks/'pakchunk0-WindowsNoEditor.ucas').open('rb') as container:
            for table,chunk,kind in specifications:
                e = h+n*12+chunk*10
                offset = int.from_bytes(b[e:e+5],'big')
                length = int.from_bytes(b[e+5:e+10],'big')
                assert offset % block_size == 0 and 0 < length <= block_size
                e = h+n*22+(offset//block_size)*entry_size
                physical = int.from_bytes(b[e:e+5],'little')
                compressed = int.from_bytes(b[e+5:e+8],'little')
                raw = int.from_bytes(b[e+8:e+11],'little')
                assert raw == length and 0 < compressed <= block_size and b[e+11] == 1
                container.seek(physical)
                data = container.read(compressed)
                assert len(data) == compressed
                packed, decoded = root/(table+'.ooz'), root/(table+'.uasset')
                packed.write_bytes(struct.pack('<Q',raw)+data)
                subprocess.run([str(decoder),str(packed),str(decoded)],check=True,
                               stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=30)
                assert decoded.stat().st_size == raw
                r = Reader(decoded)
                if kind is None:
                    # Names prove references only, not component property values or active collision.
                    blueprints[table] = [name for name in r.names if any(term in name.lower() for term in
                        ('collision','mesh','capsule','dummy','barricade','sbcharacter','attachparent'))]
                    records.append({'blueprintName':table,'chunk':chunk,'decodedBytes':raw,
                                    'compressedBytes':compressed,'physicalOffset':hex(physical),
                                    'decodedSha256':sha(decoded),'nameMapOnly':True})
                    continue
                assert r.num('H') == 0x300 and r.num('i') < 0 and r.num('I') == 0
                count = r.num('I')
                assert 0 < count < 10000
                rows, fields = {}, schema(kind)
                for _ in range(count):
                    name = r.fname()
                    assert name not in rows
                    rows[name] = r.props(fields)
                assert r.p == len(r.b), (table,hex(r.p),hex(len(r.b)))
                all_rows[table] = rows
                records.append({'table':table,'chunk':chunk,'rows':count,'exactEof':True,
                                'decodedBytes':raw,'compressedBytes':compressed,
                                'physicalOffset':hex(physical),'decodedSha256':sha(decoded)})
    characters = all_rows['CharacterTable']
    # Independent identities already known from current SDK/production data.
    assert characters['Player']['ID'] == 100 and characters['Player']['ActorType'] == 1
    assert characters['N_Dummy']['ID'] == 20000 and characters['M_DummyA']['ID'] == 40010
    selected = {name:row for name,row in characters.items() if 'dummy' in name.lower() or name == 'Player'}
    appearance_names = {row['RefAppearance'] for row in selected.values() if 'RefAppearance' in row}
    appearances = {name:all_rows['CharacterAppearanceTable'][name] for name in sorted(appearance_names)}
    report = {'utocSha256':sha(utoc),'sdkSha256':sha(sdk),'toolSha256':sha(Path(__file__)),
              'decoderSource':'https://github.com/powzix/ooz',
              'decoderCommit':'05038060aa68f9187ae9923b2388ca8db40e58d1',
              'decoderExeSha256':sha(decoder),'tables':records,
              'note':'Only explicitly serialized nonzero fields. Absent fields are not certified defaults. SDK-order schema, bounded reads, identity checks and exact EOF provide offline consistency, not runtime certification.',
              'nativeProxyCertified':False,'runtimeTested':False,'productionModified':False}
    args.output.mkdir(parents=True,exist_ok=True)
    for filename,value in [('table-evidence.json',report),('proxy-candidate-rows.json',
                            {'characters':selected,'appearances':appearances,'tribes':all_rows['CharacterTribeTable']})]:
        (args.output/filename).write_text(json.dumps(value,indent=2,ensure_ascii=True)+'\n',encoding='utf-8')
    if args.components:
        effects = all_rows['EffectTable']
        effect_names = {name for row in selected.values() for name in row.get('DefaultEffectArray',[])}
        effect_index = {name.casefold():name for name in effects}
        assert len(effect_index) == len(effects)
        resolved_effects = {name:effect_index[name.casefold()] for name in effect_names if name.casefold() in effect_index}
        stance_names = {row['DefaultStanceAlias'] for row in selected.values() if 'DefaultStanceAlias' in row}
        stances = all_rows['CharacterStanceTable']
        filters = all_rows['TargetFilterTable']
        evidence = {'effects':{name:effects[name] for name in sorted(set(resolved_effects.values()))},
                    'effectAliasResolution':dict(sorted(resolved_effects.items())),
                    'unresolvedEffectAliases':sorted(effect_names-resolved_effects.keys()),
                    'stances':{name:stances[name] for name in sorted(stance_names) if name in stances},
                    'targetFilters':{name:row for name,row in filters.items()
                                     if name in ('Self','Enemy') or row.get('bValidEngineHandle')},
                    'targetFilterCount':len(filters),
                    'validEngineHandleCount':sum(bool(row.get('bValidEngineHandle')) for row in filters.values()),
                    'blueprintNames':blueprints,'blueprintPropertiesDecoded':False}
        (args.output/'component-candidate-rows.json').write_text(json.dumps(evidence,indent=2)+'\n',encoding='utf-8')
    print('Parsed tables (exact EOF): '+', '.join(f'{name}={len(rows)}' for name,rows in all_rows.items()))
    print('No game opened; proxy certification remains false. TrainingRoom rows are not parsed.')


if __name__ == '__main__':
    main()

