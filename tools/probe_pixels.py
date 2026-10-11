import ctypes, struct
import ctypes.wintypes as w
kMappingName = "Local\StellarCraft_v1"
kOffOverlayCtl = 0x20000 + (32 << 20)
kOffOverlayPixels = kOffOverlayCtl + 0x1000
FILE_MAP_ALL_ACCESS = 0x000F001F
k32 = ctypes.WinDLL("kernel32", use_last_error=True)
k32.OpenFileMappingW.restype = w.HANDLE
k32.OpenFileMappingW.argtypes = [w.DWORD, w.BOOL, w.LPCWSTR]
k32.MapViewOfFile.restype = ctypes.c_void_p
h = k32.OpenFileMappingW(FILE_MAP_ALL_ACCESS, False, kMappingName)
base = k32.MapViewOfFile(h, FILE_MAP_ALL_ACCESS, 0, 0, 0)
kTotal = kOffOverlayPixels + 854*480*4
b = bytes((ctypes.c_ubyte * kTotal).from_address(base))
off = kOffOverlayPixels
w_, h_ = 854, 480
# histogram of RGB (ignore alpha) and alpha values
from collections import Counter
rgbc = Counter(); ac = Counter()
for i in range(0, w_*h_, 37):
    p = b[off+i*4: off+i*4+4]
    rgbc[(p[0],p[1],p[2])] += 1
    ac[p[3]] += 1
print("Top 12 RGB colors (sampled):")
for c,n in rgbc.most_common(12):
    print("   RGB%s  x%d" % (c, n))
print("Alpha values seen:", dict(ac))
# Sample a few rows to see structure: row 5 (top), row 240 (mid), row 460 (bottom hotbar)
for row in (5, 240, 430, 460):
    vals = []
    for x in range(0, w_, 60):
        p = b[off + (row*w_+x)*4: off + (row*w_+x)*4+4]
        vals.append("%02X%02X%02X" % (p[0],p[1],p[2]))
    print("row %3d: %s" % (row, " ".join(vals)))
