"""Bounded read-only native pointer diagnostics. Never calls or writes the game."""
import ctypes as c
import struct
import sys
k=c.WinDLL('kernel32',use_last_error=True)
k.OpenProcess.argtypes=[c.c_ulong,c.c_int,c.c_ulong]; k.OpenProcess.restype=c.c_void_p
k.ReadProcessMemory.argtypes=[c.c_void_p,c.c_void_p,c.c_void_p,c.c_size_t,c.POINTER(c.c_size_t)]
k.CloseHandle.argtypes=[c.c_void_p]
h=k.OpenProcess(0x10,False,int(sys.argv[1]));base=int(sys.argv[2])
def buf(at,size):
    b=c.create_string_buffer(size);got=c.c_size_t()
    if not k.ReadProcessMemory(h,at,b,size,c.byref(got)) or got.value!=size:return None
    return b.raw
def u(at,fmt='<Q'):
    b=buf(at,struct.calcsize(fmt));return struct.unpack(fmt,b)[0] if b else 0
try:
    client=u(base+0x7032570);holder=u(client+0xc8);array=u(holder+0x28);count=u(holder+0x30,'<i')
    print('holder',hex(holder),'count',count)
    assert 0<count<4096
    manager=u(base+0x7032558);ue_manager=u(manager+0x48)
    print('character-manager',hex(manager),'object-manager',hex(ue_manager))
    elements=u(ue_manager+0x50);slots=u(ue_manager+0x58,'<i')
    print('object-map slots',slots)
    assert 0<=slots<4096
    mapped={}
    for i in range(slots):
        key=buf(elements+32*i,16);obj=u(elements+32*i+16)
        if key and obj and base<u(obj)<base+0x15981000 and u(obj+0xc,'<i')>0:
            mapped[key]=obj
    for j in range(min(count,12)):
        e=u(array+8*j)
        if u(e)!=base+0x5bf6730:continue
        print('entity',hex(e),'guid',u(e+0x30,'<I'),'type',u(e+0x10c,'<I'))
        control=buf(e+0x4a4,16);obj=mapped.get(control)
        print('control',control.hex() if control else '', 'object',hex(obj) if obj else None,
              'broadcast', (u(obj+0x335c,'<I'),u(obj+0x3360,'<I')) if obj else None)
        data=buf(e,0x2d00)
        if not data:continue
        for off in range(0x38,len(data)-8,8):
            ptr=struct.unpack_from('<Q',data,off)[0]
            if not 0x10000<ptr<0x7fffffffffff or ptr%8:continue
            vt=u(ptr)
            if not base<vt<base+0x15981000:continue
            cls=u(ptr+0x10)
            # UObject-shaped pointer only: valid class with an exe vtable.
            if base<u(cls)<base+0x15981000:
                print('uobject-shaped',hex(off),hex(ptr),'vtable',hex(vt-base),'class',hex(cls),'index',u(ptr+0xc,'<i'))
finally:k.CloseHandle(h)
