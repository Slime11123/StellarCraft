"""Read-only source-object layout samples for offline ABI review."""
import sys
import struct
import time
exec(open('tools/probe_enemy_identity.py', encoding='utf-8').read().split('try:\n')[0])
try:
    manager=u(base+0x7032568);entries=u(manager+0x120);count=u(manager+0x128,'<i')
    print('generic logic slots',count)
    assert 0<=count<=16384
    samples=set();deadline=time.monotonic()+20
    while time.monotonic()<deadline and len(samples)<20:
        entries=u(manager+0x120);count=u(manager+0x128,'<i')
        if not 0<=count<=16384:break
        table=buf(entries,count*24)
        if not table:break
        for i in range(count):
            oid=struct.unpack_from('<I',table,i*24)[0];obj=struct.unpack_from('<Q',table,i*24+8)[0]
            if not obj or oid in samples:continue
            data=buf(obj,0x110)
            if not data:continue
            key=struct.unpack_from('<I',data,0x104)[0]
            if key not in (1000020,1000016,5048005,5048008):continue
            samples.add(oid)
            print('source',hex(obj),'slotKey',oid,'vtable',hex(u(obj)-base),'key',key,
                  'position68',struct.unpack_from('<3f',data,0x68),
                  'words80',struct.unpack_from('<8I',data,0x80),flush=True)
        time.sleep(.02)
finally:k.CloseHandle(h)
