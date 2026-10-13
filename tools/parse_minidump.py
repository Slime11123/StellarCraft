"""Minimal x64 minidump reader.

Extracts what matters for "which module crashed and from where":
  * the exception record (code + faulting address),
  * the faulting thread's registers,
  * the loaded module list (name / base / size),
  * a scan-based pseudo callstack (return addresses that land inside a module).

No PDBs are available, so addresses are reported as `module+0xoffset`, which is enough to tell
apart e.g. "the game's renderer" from "UE4SS" from "our own DLL", and to compare two crashes.
"""
import struct
import sys
import os

MODULE_FLAG = {}


def rva_to_off(rva, streams):
    return rva


class Dump:
    def __init__(self, path):
        self.data = open(path, 'rb').read()
        sig, ver, nstreams, dirofs, checksum, tds, flags = struct.unpack_from('<4sIIIIII', self.data, 0)
        assert sig == b'MDMP', 'not a minidump'
        self.streams = {}
        for i in range(nstreams):
            stype, size, rva = struct.unpack_from('<III', self.data, dirofs + i * 12)
            self.streams.setdefault(stype, []).append((size, rva))

    def stream(self, stype):
        got = self.streams.get(stype)
        return got[0] if got else None

    # ---- streams -------------------------------------------------------------------
    def modules(self):
        size, rva = self.stream(4)
        n, = struct.unpack_from('<I', self.data, rva)
        out = []
        for i in range(n):
            off = rva + 4 + i * 108
            base, size_of_image, checksum, tds, name_rva = struct.unpack_from('<QIIII', self.data, off)
            ln, = struct.unpack_from('<I', self.data, name_rva)
            name = self.data[name_rva + 4: name_rva + 4 + ln].decode('utf-16-le', 'replace')
            out.append({'name': name, 'base': base, 'size': size_of_image})
        return out

    def threads(self):
        size, rva = self.stream(3)
        n, = struct.unpack_from('<I', self.data, rva)
        out = []
        for i in range(n):
            off = rva + 4 + i * 48
            tid, susp, prio_class, prio, teb = struct.unpack_from('<IIIIQ', self.data, off)
            soff = off + 24
            stack_start, stack_size, stack_rva = struct.unpack_from('<QII', self.data, soff)
            ctx_size, ctx_rva = struct.unpack_from('<II', self.data, off + 40)
            out.append({'tid': tid, 'stack_start': stack_start, 'stack_size': stack_size,
                        'stack_rva': stack_rva, 'ctx_size': ctx_size, 'ctx_rva': ctx_rva})
        return out

    def exception(self):
        got = self.stream(6)
        if not got:
            return None
        size, rva = got
        tid, unused = struct.unpack_from('<II', self.data, rva)
        eoff = rva + 8
        code, eflags, nested, addr = struct.unpack_from('<IIQQ', self.data, eoff)
        nparams, align = struct.unpack_from('<II', self.data, eoff + 24)
        params = struct.unpack_from('<15Q', self.data, eoff + 32)
        ctx_size, ctx_rva = struct.unpack_from('<II', self.data, eoff + 152)  # after MINIDUMP_EXCEPTION
        return {'tid': tid, 'code': code, 'flags': eflags, 'addr': addr,
                'nparams': nparams, 'params': params, 'ctx_rva': ctx_rva, 'ctx_size': ctx_size}

    def context(self, rva):
        c = self.data[rva: rva + 0x4D0]
        ctx = {}
        ctx['ContextFlags'] = struct.unpack_from('<I', c, 0x30)[0]
        ctx['Rax'] = struct.unpack_from('<Q', c, 0x78)[0]
        ctx['Rcx'] = struct.unpack_from('<Q', c, 0x80)[0]
        ctx['Rdx'] = struct.unpack_from('<Q', c, 0x88)[0]
        ctx['Rbx'] = struct.unpack_from('<Q', c, 0x90)[0]
        ctx['Rsp'] = struct.unpack_from('<Q', c, 0x98)[0]
        ctx['Rbp'] = struct.unpack_from('<Q', c, 0xA0)[0]
        ctx['Rsi'] = struct.unpack_from('<Q', c, 0xA8)[0]
        ctx['Rdi'] = struct.unpack_from('<Q', c, 0xB0)[0]
        ctx['R8'] = struct.unpack_from('<Q', c, 0xB8)[0]
        ctx['R9'] = struct.unpack_from('<Q', c, 0xC0)[0]
        ctx['R10'] = struct.unpack_from('<Q', c, 0xC8)[0]
        ctx['R11'] = struct.unpack_from('<Q', c, 0xD0)[0]
        ctx['R12'] = struct.unpack_from('<Q', c, 0xD8)[0]
        ctx['R13'] = struct.unpack_from('<Q', c, 0xE0)[0]
        ctx['R14'] = struct.unpack_from('<Q', c, 0xE8)[0]
        ctx['R15'] = struct.unpack_from('<Q', c, 0xF0)[0]
        ctx['Rip'] = struct.unpack_from('<Q', c, 0xF8)[0]
        return ctx


# ---------------------------------------------------------------------------------------
def main(path):
    d = Dump(path)
    mods = d.modules()
    exc = d.exception()

    def where(addr):
        for m in mods:
            if m['base'] <= addr < m['base'] + m['size']:
                return '%s+0x%X' % (os.path.basename(m['name']), addr - m['base'])
        return None

    print('=' * 78)
    print('dump        :', os.path.basename(path))
    print('streams     :', sorted(d.streams.keys()))
    print('modules     : %d' % len(mods))
    print('=' * 78)

    if exc:
        print('EXCEPTION')
        print('  thread id : %d' % exc['tid'])
        print('  code      : 0x%08X %s' % (exc['code'], {
            0xC0000005: 'ACCESS_VIOLATION',
            0xC000001D: 'ILLEGAL_INSTRUCTION',
            0xC0000094: 'INT_DIVIDE_BY_ZERO',
            0xC0000096: 'PRIVILEGED_INSTRUCTION',
            0xC00000FD: 'STACK_OVERFLOW',
            0xC0000374: 'HEAP_CORRUPTION',
            0xC0000409: 'STACK_BUFFER_OVERRUN',
            0x80000003: 'BREAKPOINT',
            0x80000004: 'SINGLE_STEP',
            0xE06D7363: 'CPP_EXCEPTION',
        }.get(exc['code'], '')))
        print('  address   : 0x%016X  -> %s' % (exc['addr'], where(exc['addr']) or '(not in a module)'))
        if exc['code'] == 0xC0000005 and exc['nparams'] >= 2:
            kind = {0: 'read', 1: 'write', 8: 'execute'}.get(exc['params'][0], str(exc['params'][0]))
            print('  access    : %s at 0x%016X' % (kind, exc['params'][1]))
        ctx = d.context(exc['ctx_rva'])
        print('  RIP       : 0x%016X  -> %s' % (ctx['Rip'], where(ctx['Rip']) or '(not in a module)'))
        print('  RSP       : 0x%016X   RBP: 0x%016X' % (ctx['Rsp'], ctx['Rbp']))
        for k in ('Rcx', 'Rdx', 'R8', 'R9', 'Rax', 'Rdi', 'Rsi', 'Rbx'):
            print('  %-9s : 0x%016X  %s' % (k, ctx[k], where(ctx[k]) or ''))
        fault_tid = exc['tid']
        fault_rsp = ctx['Rsp']
    else:
        print('no exception stream')
        fault_tid, fault_rsp = None, None

    # ---- pseudo callstack: scan the faulting thread's own stack for return addresses ----
    thr = None
    for t in d.threads():
        if t['tid'] == fault_tid:
            thr = t
            break
    if thr and thr['stack_size']:
        print()
        print('STACK SCAN (thread %d, %d KB) - return addresses inside loaded modules:' % (thr['tid'], thr['stack_size'] // 1024))
        base = thr['stack_start']
        raw = d.data[thr['stack_rva']: thr['stack_rva'] + thr['stack_size']]
        seen = 0
        for off in range(0, len(raw) - 8, 8):
            v, = struct.unpack_from('<Q', raw, off)
            if v < 0x10000:
                continue
            w = where(v)
            if w:
                print('  [rsp+0x%04X]  0x%016X  %s' % (off, v, w))
                seen += 1
                if seen >= 40:
                    break
    else:
        print('(faulting thread stack not present in the dump)')

    print()
    print('MODULES (filtered)')
    for m in mods:
        n = os.path.basename(m['name']).lower()
        if any(k in n for k in ('sb-win64', 'ue4ss', 'stellarcraft', 'd3d12', 'dxgi', 'nvwgf',
                                'amdxc', 'fidelityfx')):
            print('  0x%016X  %10d  %s' % (m['base'], m['size'], m['name']))


if __name__ == '__main__':
    main(sys.argv[1])
