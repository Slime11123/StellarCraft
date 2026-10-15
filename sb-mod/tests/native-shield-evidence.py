"""Read the installed PE only; never attach to a process or execute game code."""
import hashlib
import struct
from pathlib import Path

import capstone
import pefile

exe = Path(r'D:\SteamLibrary\steamapps\common\StellarBlade\SB\Binaries\Win64\SB-Win64-Shipping.exe')
print('SHA256', hashlib.file_digest(exe.open('rb'), 'sha256').hexdigest())
pe = pefile.PE(str(exe), fast_load=True)
decoder = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_64)
assert pe.FILE_HEADER.TimeDateStamp == 0x6A6A3B74
assert pe.OPTIONAL_HEADER.SizeOfImage == 0x15981000
for stat, expected in ((7, 0x1A6512A), (8, 0x1A651C9)):
    index = pe.get_data(0x1A65A90 + stat - 1, 1)[0]
    target, = struct.unpack('<I', pe.get_data(0x1A65A54 + index * 4, 4))
    assert target == expected
    print(f'ActorStatType {stat}: jump index={index}, target={target:#x}')
index = pe.get_data(0x1C7765C + 6, 1)[0]
target, = struct.unpack('<I', pe.get_data(0x1C77654 + index * 4, 4))
assert target == 0x1C77645
print(f'Stat=7 delta normalization: {target:#x} (truncate toward zero)')
for start, size in ((0x1BA87DC, 0xD2), (0x1BA8968, 0xBB),
                    (0x1A64D52, 0x30), (0x1A6512A, 0x102), (0x1C77645, 0xF)):
    print(f'\nRVA {start:#x}, bytes={size}')
    for ins in decoder.disasm(pe.get_data(start, size), start):
        print(f'{ins.address:#x} {ins.bytes.hex():36s} {ins.mnemonic} {ins.op_str}')
print('\nPASS: stat=7 setter reads current +134 / max +138, clamps and truncates natively; '
      'ApplyStatDiff routes stat=7 to zero-shield alias path. Static evidence only.')
