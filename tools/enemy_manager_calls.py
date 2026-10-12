"""Offline native character-manager callers for identity-chain review."""
import struct
import pefile
import capstone
p=pefile.PE(r'D:\SteamLibrary\steamapps\common\StellarBlade\SB\Binaries\Win64\SB-Win64-Shipping.exe',fast_load=True)
m=capstone.Cs(capstone.CS_ARCH_X86,capstone.CS_MODE_64)
s=p.sections[0];d=s.get_data();start=s.VirtualAddress
hits=[]
for j in range(len(d)-5):
    if d[j]==0xe8 and start+j+5+struct.unpack_from('<i',d,j+1)[0]==0x1a97cb0:hits.append(start+j)
print('calls',len(hits))
for r in hits[:100]:
    ins=list(m.disasm(p.get_data(r+5,36),r+5))[:6]
    print(hex(r),'; '.join(i.mnemonic+' '+i.op_str for i in ins))
