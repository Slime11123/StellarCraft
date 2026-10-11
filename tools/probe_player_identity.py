"""Read-only native identity sampling; no game calls or memory writes."""
import ctypes as c
import struct
import sys

k=c.WinDLL('kernel32',use_last_error=True)
k.OpenProcess.argtypes=[c.c_ulong,c.c_int,c.c_ulong]
k.OpenProcess.restype=c.c_void_p
k.ReadProcessMemory.argtypes=[c.c_void_p,c.c_void_p,c.c_void_p,c.c_size_t,c.POINTER(c.c_size_t)]
k.CloseHandle.argtypes=[c.c_void_p]
h=k.OpenProcess(0x10,False,int(sys.argv[1]))
base=int(sys.argv[2])
def read(at,fmt):
    size=struct.calcsize(fmt); buf=c.create_string_buffer(size); got=c.c_size_t()
    if not k.ReadProcessMemory(h,at,buf,size,c.byref(got)) or got.value!=size:
        raise OSError('unreadable identity address')
    return struct.unpack(fmt,buf.raw)[0]
try:
    client=read(base+0x7032570,'<Q')
    holder=read(client+0xc8,'<Q')
    index=read(holder+0x3c,'<i'); count=read(holder+0x30,'<i')
    assert 0<=index<count<=1048576
    array=read(holder+0x28,'<Q'); selected=read(array+8*index,'<Q')
    print(dict(index=index,count=count,eventorGuid=read(client+0x80,'<I'),
        vtableRva=hex(read(selected,'<Q')-base),interfaceRva=hex(read(selected+0x10,'<Q')-base),
        guid=read(selected+0x30,'<I'),playerId=read(selected+0x4a0,'<I'),
        type=read(selected+0x10c,'<I'),hp=read(selected+0x11c,'<f'),maxHp=read(selected+0x120,'<f')))
finally:
    k.CloseHandle(h)
