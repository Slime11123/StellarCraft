"""Offline inspection of the certified FSBActor interfaces; no process writes."""
import struct
import pefile
import capstone

exe = r'D:\SteamLibrary\steamapps\common\StellarBlade\SB\Binaries\Win64\SB-Win64-Shipping.exe'
pe = pefile.PE(exe, fast_load=True)
md = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_64)
for table, count in [(0x5BF6730, 20), (0x5BF67D0, 64)]:
    for slot in range(count):
        ptr, = struct.unpack('<Q', pe.get_data(table + 8 * slot, 8))
        rva = ptr - pe.OPTIONAL_HEADER.ImageBase
        if not 0 < rva < pe.OPTIONAL_HEADER.SizeOfImage:
            continue
        ins = list(md.disasm(pe.get_data(rva, 48), rva))
        text = []
        for i in ins[:8]:
            text.append(f'{i.mnemonic} {i.op_str}')
            if i.mnemonic == 'ret':
                break
        print(f'{table:x}+{slot*8:x} -> {rva:x}: ' + '; '.join(text))
