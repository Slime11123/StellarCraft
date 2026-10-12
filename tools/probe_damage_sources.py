"""Read-only source-object layout samples for offline ABI review."""
import sys
import struct
exec(open('tools/probe_enemy_identity.py', encoding='utf-8').read().split('try:\n')[0])
try:
    manager=u(base+0x7032568);entries=u(manager+0x120);count=u(manager+0x128,'<i')
    print('generic logic slots',count)
    assert 0<=count<=16384
    samples=0
    for i in range(count):
        obj=u(entries+i*24+8)
        if not obj:continue
        key=u(obj+0x104,'<I')
        if key not in (1000020,1000016):continue
        data=buf(obj,0x110)
        if not data:continue
        print('source',hex(obj),'slotKey',u(entries+i*24,'<I'),'vtable',hex(u(obj)-base),'key',key,
              'position68',struct.unpack_from('<3f',data,0x68),
              'words80',struct.unpack_from('<8I',data,0x80))
        samples+=1
        if samples>=20:break
finally:k.CloseHandle(h)
