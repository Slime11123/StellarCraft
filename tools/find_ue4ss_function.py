"""Locate a crash address inside UE4SS.dll and identify what the code around it does.

No PDB is available, so this uses the things a stripped x64 PE still leaks:

  * the `.pdata` exception directory gives exact FUNCTION BOUNDARIES, so we know which
    function the faulting instruction belongs to and how big it is;
  * scanning that function for RIP-relative `lea`/`mov` operands recovers the ADDRESSES OF
    STRINGS AND GLOBALS it references -- and UE4SS is verbose, so those strings usually name
    the code path (error messages, UE property names, format strings).

Usage:  python find_ue4ss_function.py <dll> <rva-hex>
"""
import struct
import sys
import re


class PE:
    def __init__(self, path):
        self.data = open(path, 'rb').read()
        d = self.data
        e_lfanew, = struct.unpack_from('<I', d, 0x3C)
        assert d[e_lfanew:e_lfanew + 4] == b'PE\0\0', 'not a PE'
        coff = e_lfanew + 4
        nsec, = struct.unpack_from('<H', d, coff + 2)
        opt_size, = struct.unpack_from('<H', d, coff + 16)
        opt = coff + 20
        magic, = struct.unpack_from('<H', d, opt)
        self.is64 = magic == 0x20B
        self.image_base, = struct.unpack_from('<Q', d, opt + 24)
        # data directories
        ndd_off = opt + (112 if self.is64 else 96)
        self.dirs = []
        for i in range(16):
            rva, size = struct.unpack_from('<II', d, ndd_off + i * 8)
            self.dirs.append((rva, size))
        self.sections = []
        sec = opt + opt_size
        for i in range(nsec):
            off = sec + i * 40
            name = d[off:off + 8].rstrip(b'\0').decode('ascii', 'replace')
            vsize, vaddr, rawsize, rawptr = struct.unpack_from('<IIII', d, off + 8)
            self.sections.append({'name': name, 'vsize': vsize, 'vaddr': vaddr,
                                  'rawsize': rawsize, 'rawptr': rawptr})

    def sec_of(self, rva):
        for s in self.sections:
            if s['vaddr'] <= rva < s['vaddr'] + max(s['vsize'], s['rawsize']):
                return s
        return None

    def read(self, rva, n):
        s = self.sec_of(rva)
        if not s:
            return None
        off = s['rawptr'] + (rva - s['vaddr'])
        if off < 0 or off + n > len(self.data):
            return None
        return self.data[off:off + n]

    def cstr(self, rva, maxlen=200):
        raw = self.read(rva, maxlen)
        if not raw:
            return None
        end = raw.find(b'\0')
        if end <= 0:
            return None
        txt = raw[:end]
        if all(32 <= c < 127 or c in (9, 10, 13) for c in txt):
            return txt.decode('ascii')
        return None

    def function_at(self, rva):
        """Look the address up in .pdata -> (begin, end)."""
        prva, psize = self.dirs[3]           # IMAGE_DIRECTORY_ENTRY_EXCEPTION
        if not prva:
            return None
        n = psize // 12
        lo, hi = 0, n - 1
        while lo <= hi:
            mid = (lo + hi) // 2
            b, e, u = struct.unpack_from('<III', self.data, self._pdata_off(prva) + mid * 12)
            if rva < b:
                hi = mid - 1
            elif rva >= e:
                lo = mid + 1
            else:
                return (b, e, u)
        return None

    def _pdata_off(self, prva):
        s = self.sec_of(prva)
        return s['rawptr'] + (prva - s['vaddr'])


def scan_refs(pe, begin, end, limit=60):
    """Find RIP-relative operands in [begin,end) and resolve them."""
    out = []
    code = pe.read(begin, end - begin)
    if not code:
        return out
    for i in range(len(code) - 7):
        b0, b1 = code[i], code[i + 1]
        kind = None
        if b0 in (0x48, 0x4C) and b1 == 0x8D and (code[i + 2] & 0xC7) == 0x05:
            kind = 'lea'
        elif b0 in (0x48, 0x4C) and b1 == 0x8B and (code[i + 2] & 0xC7) == 0x05:
            kind = 'mov'
        elif b0 == 0xFF and b1 == 0x15:
            kind = 'call'
        if not kind:
            continue
        disp, = struct.unpack_from('<i', code, i + 3)
        target = begin + i + 7 + disp
        val = pe.cstr(target)
        if val and len(val) >= 4:
            out.append((kind, target, repr(val)))
            if len(out) >= limit:
                break
    return out


def main():
    dll = sys.argv[1]
    rva = int(sys.argv[2], 16)
    pe = PE(dll)
    print('image base 0x%X, sections:' % pe.image_base)
    for s in pe.sections:
        print('   %-8s vaddr=0x%08X vsize=0x%08X raw=0x%08X' % (s['name'], s['vaddr'], s['vsize'], s['rawsize']))

    s = pe.sec_of(rva)
    print()
    print('crash rva 0x%X is in section %s' % (rva, s and s['name']))

    fn = pe.function_at(rva)
    if fn:
        b, e, u = fn
        print('function: 0x%X - 0x%X  (size %d bytes, offset into function: +0x%X)'
              % (b, e, e - b, rva - b))
        print('unwind info rva 0x%X' % u)
        raw = pe.read(rva, 24)
        print()
        print('bytes at crash:  ' + ' '.join('%02X' % c for c in raw))
        raw2 = pe.read(rva - 16, 16)
        print('bytes before   : ' + ' '.join('%02X' % c for c in raw2) + '   <- next')
    else:
        print('function: not found in .pdata')

    print()
    print('strings / globals referenced inside this function:')
    for kind, t, txt in scan_refs(pe, fn[0], fn[1] if fn else rva + 0x400):
        print('   %-4s 0x%08X  %s' % (kind, t, txt[:150]))


if __name__ == '__main__':
    main()
