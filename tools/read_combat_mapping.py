"""Read the public bridge protocol only; never writes or attaches to game memory."""
import ctypes,struct
k=ctypes.WinDLL('kernel32',use_last_error=True)
k.OpenFileMappingW.argtypes=[ctypes.c_uint32,ctypes.c_bool,ctypes.c_wchar_p];k.OpenFileMappingW.restype=ctypes.c_void_p
k.MapViewOfFile.argtypes=[ctypes.c_void_p,ctypes.c_uint32,ctypes.c_uint32,ctypes.c_uint32,ctypes.c_size_t];k.MapViewOfFile.restype=ctypes.c_void_p
k.UnmapViewOfFile.argtypes=[ctypes.c_void_p];k.CloseHandle.argtypes=[ctypes.c_void_p]
h=k.OpenFileMappingW(4,False,r'Local\StellarCraft_v1');assert h,'bridge not running'
base=k.MapViewOfFile(h,4,0,0,0);assert base
try:
 def read(at,size):return ctypes.string_at(base+at,size)
 magic,version,sb,mc=struct.unpack('<4I',read(0,16));assert version==4
 pos=struct.unpack('<3d',read(0x110,24));print('version',version,'SB',sb,'MC',mc,'player',pos)
 at=299888768;head=read(at,64);seq,count=struct.unpack_from('<2I',head);world=struct.unpack_from('<I',head,20)[0]
 print('actors',count,'world',world,'sequence',seq)
 rows=read(at+64,count*64)
 for i in range(count):
  uid,flags,x,y,z,yaw,width,height,health=struct.unpack_from('<II7f',rows,i*64)
  guid,kind,w,generation=struct.unpack_from('<4I',rows,i*64+40)
  print('target',uid,'guid/type',guid,kind,'position',tuple(round(v,2) for v in (x,y,z)),'size',width,height,'HP',health,'flags',flags)
 print('hitHead',struct.unpack('<Q',read(299905216,8))[0])
finally:k.UnmapViewOfFile(base);k.CloseHandle(h)
