"""Exercise the real DLL/Lua event reader offline, including raw NaN-shaped timestamps."""
import ctypes
from pathlib import Path
import struct
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    processes = subprocess.run(['tasklist', '/FO', 'CSV', '/NH'], capture_output=True, text=True).stdout.lower()
    if any('"' + name + '"' in processes for name in ('sb-win64-shipping.exe', 'java.exe', 'javaw.exe')):
        raise RuntimeError('Close games before using the offline mapping test')
    path = ROOT / 'sb-mod/build/manual/stellarcraft_shm.dll'
    dll = ctypes.WinDLL(str(path))
    dll.sc_create_mapping.restype = ctypes.c_void_p
    base = dll.sc_create_mapping()
    assert base and struct.unpack('<I', ctypes.string_at(base + 4, 4))[0] == 6
    child = subprocess.Popen([str(ROOT / 'sb-mod/build/manual/lua.exe'),
        str(ROOT / 'tools/_hit_event_protocol_probe.lua'), path.as_posix()],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    try:
        assert child.stdout.readline().strip() == 'READY'
        ctypes.memmove(base + 0x104, struct.pack('<I', 0x50), 4)
        for command, expected, flags in (('map-a', 0x12345678, 0x51), ('map-b', 0x87654321, 0x51),
                                        ('map-invalid', 0, 0x50), ('map-missing', 0, 0x51)):
            child.stdin.write(command + '\n'); child.stdin.flush()
            assert child.stdout.readline().strip() == 'PASS'
            seq, actual_flags, level = struct.unpack('<III', ctypes.string_at(base + 0x100, 12))
            assert seq % 2 == 0 and actual_flags == flags and level == expected
            expected_map={'map-a':0x11223344,'map-b':0x55667788}.get(command,0)
            assert struct.unpack('<I',ctypes.string_at(base+0x100+0x3C,4))[0]==expected_map
        ring, capacity = base + 0x10000, 0xFF80
        records = (struct.pack('<IIfffIII', 9, 42, 1, 2, 3, 0x7FC12345, 22, 5)
            + struct.pack('<IIfffIII', 10, 42, .6, .8, 0, 0x7FC12345, 22, 5)
            + struct.pack('<IIffffII', 1, 42, 7, 0, 0, .3, 22, 5))
        for start in (0, capacity - 32):
            ctypes.memmove(ring + 0x40, struct.pack('<Q', start), 8)
            for i, byte in enumerate(records):
                ctypes.memmove(ring + 0x80 + (start + i) % capacity, bytes([byte]), 1)
            ctypes.memmove(ring, struct.pack('<Q', start + len(records)), 8)
            for command in ('batch', 'empty'):
                child.stdin.write(command + '\n'); child.stdin.flush()
                assert child.stdout.readline().strip() == 'PASS'
            assert struct.unpack('<Q', ctypes.string_at(ring + 0x40, 8))[0] == start + len(records)
        print('PASS: actual v6 DLL/Lua map publication/change/invalidation and preserved control bits; event decoder, raw timestamp bits, wrap and single consumption')
    finally:
        child.stdin.close(); child.wait(timeout=5)
        assert child.returncode == 0, child.stderr.read()


if __name__ == '__main__':
    main()
