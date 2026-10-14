"""Find bounded offline references to the original effect dispatcher; no game calls."""
from pathlib import Path
import hashlib
import struct
import capstone
import pefile

EXE=Path(r'D:\SteamLibrary\steamapps\common\StellarBlade\SB\Binaries\Win64\SB-Win64-Shipping.exe')
assert hashlib.sha256(EXE.read_bytes()).hexdigest().upper()=='573AAFF1C9455F85EA6036EF6F6CCB0774DE4164722A296276FBBFC7FA87545C'
pe=pefile.PE(str(EXE),fast_load=True)
md=capstone.Cs(capstone.CS_ARCH_X86,capstone.CS_MODE_64)
targets={}
for section in pe.sections:
    data=section.get_data()
    for name in ('SBApplyEffect','SBApplyEffectMe','HitStun'):
        for encoding in ('utf-16le','ascii'):
            token=(name+'\0').encode(encoding);start=0
            while (offset:=data.find(token,start))>=0:
                targets[section.VirtualAddress+offset]=name;start=offset+len(token)
records=['Offline string references only; do not imply a callable effect ABI.']
for section in pe.sections:
    if not section.Characteristics&0x20000000: continue
    code=section.get_data()
    import re
    for match in re.finditer(rb'[\x48\x4c]\x8d[\x05\x0d\x15\x1d\x25\x2d\x35\x3d]',code):
        i=match.start()
        rva=section.VirtualAddress+i
        target=rva+7+struct.unpack_from('<i',code,i+3)[0]
        if target not in targets: continue
        records.append(f'\n{targets[target]} string {target:X}, reference {rva:X}')
        for ins in md.disasm(pe.get_data(rva-64,192),rva-64):
            records.append(f'{ins.address:08X} {ins.mnemonic} {ins.op_str}')
for section in pe.sections:
    if section.Characteristics&0x20000000: continue
    data=section.get_data()
    for target,name in targets.items():
        needle=struct.pack('<Q',pe.OPTIONAL_HEADER.ImageBase+target);start=0
        while (offset:=data.find(needle,start))>=0:
            following=struct.unpack_from('<Q',data,offset+8)[0]
            records.append(f'{name} data pointer {section.VirtualAddress+offset:X}, next pointer RVA {following-pe.OPTIONAL_HEADER.ImageBase:X}')
            start=offset+8
output=Path(__file__).resolve().parents[1]/'docs/jump-heavy-20261014/effect-alias-references.txt'
output.write_text('\n'.join(records)+'\n',encoding='utf-8')
print(f'Saved {len(targets)} matching strings and their bounded code references: {output}')
