import pefile,capstone
path=r'D:\SteamLibrary\steamapps\common\StellarBlade\SB\Binaries\Win64\SB-Win64-Shipping.exe'
p=pefile.PE(path,fast_load=True);m=capstone.Cs(capstone.CS_ARCH_X86,capstone.CS_MODE_64)
for i in m.disasm(p.get_data(0x1BA8720,0x400),0x1BA8720):print(hex(i.address),i.mnemonic,i.op_str)