"""Exercise the current DLL/Lua motion readers without either game running."""
import ctypes
import pathlib
import struct
import subprocess
import zlib

ROOT = pathlib.Path(__file__).resolve().parents[1]
DLL = ROOT / 'sb-mod/build/manual/stellarcraft_shm.dll'
LUA = ROOT / 'sb-mod/build/manual/lua.exe'
PROBE = ROOT / 'tools/_motion_protocol_probe.lua'

def main():
    running = subprocess.run(['tasklist', '/FO', 'CSV', '/NH'], capture_output=True, text=True).stdout.lower()
    if any('"'+name+'"' in running for name in ('sb-win64-shipping.exe', 'java.exe', 'javaw.exe')):
        raise RuntimeError('Close games before exercising the shared mapping')
    dll = ctypes.WinDLL(str(DLL))
    dll.sc_create_mapping.restype = ctypes.c_void_p
    base = dll.sc_create_mapping()
    assert base
    clock = ctypes.WinDLL('kernel32').GetTickCount64
    clock.restype = ctypes.c_uint64
    child = subprocess.Popen([str(LUA), str(PROBE), DLL.as_posix()], stdin=subprocess.PIPE,
                             stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    assert child.stdout.readline().strip() == 'READY'
    mc_pid = 12345
    ctypes.memmove(base+12, struct.pack('<I', mc_pid), 4)

    def motion(**changes):
        values = dict(seq=2, epoch=child.pid, time=clock(), mode=3, vx=.2, vy=-.1, vz=.3, world=9)
        values.update(changes)
        block = bytearray(256)
        struct.pack_into('<II', block, 0, values['seq'], 257)
        struct.pack_into('<ddd', block, 8, 10, 200, -30)
        struct.pack_into('<QII', block, 0xB0, values['time'], values['epoch'], values['mode'])
        struct.pack_into('<dddI', block, 0xC0, values['vx'], values['vy'], values['vz'], values['world'])
        ctypes.memmove(base+0x200, bytes(block), 256)

    def pose(**changes):
        values = dict(seq=2, epoch=child.pid, time=clock(), world=9, pid=mc_pid,
                      high=11, low=22, crc=True, size=76, serial=1)
        values.update(changes)
        payload = b'SCPOSE1\n'+struct.pack('<IQI', values['serial'], 100000, 1)+struct.pack('<I10f', 4, *([0]*10))
        payload += struct.pack('<II', values['serial'], zlib.crc32(payload))
        if not values['crc']: payload = payload[:-1]+bytes([payload[-1]^1])
        header = struct.pack('<6I3Q16x', values['seq'], values['size'], values['pid'], values['epoch'],
                             values['world'], 7, values['high'], values['low'], values['time'])
        offset = 232755200+3*0x18000
        ctypes.memmove(base+offset, header+payload, len(header)+len(payload))

    def expect(name, mc, entity):
        child.stdin.write('read\n'); child.stdin.flush()
        actual=child.stdout.readline().strip()
        assert actual == f'{mc} {entity}', (name, actual)
        print('PASS:', name)

    try:
        motion(); pose(); expect('fresh v6 motion and matching UUID', 'table', 'string')
        for name, change in [('odd seqlock', dict(seq=3)), ('wrong epoch', dict(epoch=child.pid+1)),
                             ('stale intent', dict(time=clock()-1000)), ('future intent', dict(time=clock()+1000)),
                             ('NaN velocity', dict(vx=float('nan'))), ('unknown mode', dict(mode=4))]:
            motion(**change); expect(name, 'nil', 'string')
        motion()
        for name, change in [('odd pose', dict(seq=3)), ('wrong producer', dict(pid=mc_pid+1)),
                             ('wrong pose epoch', dict(epoch=child.pid+1)), ('foreign world', dict(world=10)),
                             ('reused entity ID / different UUID', dict(low=23)), ('stale pose', dict(time=clock()-1000)),
                             ('oversized payload', dict(size=0x18000)), ('corrupt CRC', dict(crc=False))]:
            pose(**change); expect(name, 'table', 'nil')
        pose(); expect('valid frame after all rejected frames', 'table', 'string')
        pose(serial=0xfe000123); expect('persistent topology generation / unsigned footer', 'table', 'string')
    finally:
        child.stdin.close(); child.wait(timeout=5)
        assert child.returncode == 0, child.stderr.read()

if __name__ == '__main__':
    main()
