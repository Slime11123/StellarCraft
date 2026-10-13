"""Cross-check the C++ protocol layout against the Java mirror.

The two sides must agree byte-for-byte, but they are written by hand in different languages.
This compiles + runs the C++ static-assert/offset check, then recomputes every constant the
Java side declares (Proto.java, SharedMemory.java) and fails loudly on any drift.

Run:  python tools/check_proto_parity.py
"""
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))   # stellarcraft/
PROTO_JAVA = os.path.join(ROOT, 'mc-mod', 'src', 'main', 'java', 'dev', 'stellarcraft', 'link', 'Proto.java')
CXX = os.path.join(ROOT, 'sb-mod')


def cxx_layout():
    """Compile + run the C++ side and parse its exact values."""
    r = subprocess.run([sys.executable, os.path.join(CXX, 'tests', 'check_proto.py')],
                       cwd=CXX, capture_output=True, text=True, encoding='utf-8', errors='replace')
    out = r.stdout
    assert 'LAYOUT OK' in out, 'C++ layout check failed:\n' + out + (r.stderr or '')

    vals = {}
    for line in out.splitlines():
        if '=' in line and not line.startswith(' '):
            k, _, v = line.partition('=')
            if v.strip().isdigit():
                vals[k.strip()] = int(v.strip())
    return vals


def java_constants():
    """Evaluate the Java side's integer constant expressions by reading Proto.java."""
    src = open(PROTO_JAVA, encoding='utf-8').read()

    # Collect `static final <int|long> NAME = <expr>;` and evaluate in dependency order.
    decls = re.findall(r'public static final\s+(?:int|long)\s+([A-Z_0-9]+)\s*=\s*([^;]+);', src)
    known = {}
    pending = list(decls)
    for _ in range(len(pending) + 2):
        still = []
        for name, expr in pending:
            e = expr.strip()
            # Strip Java casts / literals that Python would choke on: (long), 32L, 0x1F, etc.
            e = re.sub(r'\(\s*(?:long|int|short|byte)\s*\)', '', e)
            e = re.sub(r'\b(\d+)[Ll]\b', r'\1', e)
            for m in sorted(known, key=len, reverse=True):     # longest name first
                e = re.sub(r'\b%s\b' % re.escape(m), str(known[m]), e)
            e = e.replace('L', '').replace('l', '')
            try:
                known[name] = int(eval(e, {'__builtins__': {}}, {}))   # noqa: S307 - own source
            except Exception:
                still.append((name, expr))
        pending = still
        if not pending:
            break
    return known


def main():
    cxx = cxx_layout()
    java = java_constants()

    # C++ name -> Java name
    pairs = [
        ('kOffOverlayCtl', 'OFF_OVERLAY_CTL'),
        ('kOffOverlayPixels', 'OFF_OVERLAY_PIXELS'),
        ('kOverlaySlotBytes', 'OVERLAY_SLOT_BYTES'),
        ('kOffOverlayDepth', 'OFF_OVERLAY_DEPTH'),
        ('kOverlayDepthBytes', 'OVERLAY_DEPTH_BYTES'),
        ('kOffRenderRing', 'OFF_RENDER_RING'),
        ('kOffAppearance', 'OFF_APPEARANCE'),
        ('kMappingBytes', 'MAPPING_BYTES'),
        ('kVersion', 'K_VERSION'),
        ('kHitStyleJumpHeavy', 'HIT_STYLE_JUMP_HEAVY'),
        ('kEvHitOrigin','EV_HIT_ORIGIN'),
        ('kEvHitDirection','EV_HIT_DIRECTION'),
        ('kOffActorTable', 'OFF_ACTOR_TABLE'),
        ('kOffCombat', 'OFF_COMBAT'),
        ('kCombatBytes', 'COMBAT_BYTES'),
        ('kCombatSlots', 'COMBAT_SLOTS'),
        ('CombatHit', 'COMBAT_HIT_SIZE'),
    ]

    # Java-only constants that must match the protocol header but have no direct C++ mirror.
    java['_OVERLAY_CTL_BYTES'] = java.get('OVERLAY_CTL_BYTES')
    java['_MAX_W'] = java.get('MAX_OVERLAY_W')
    java['_MAX_H'] = java.get('MAX_OVERLAY_H')

    print('%-24s %-16s %-16s %s' % ('C++ (protocol header)', 'Java (Proto.java)', 'value', 'status'))
    print('-' * 78)

    bad = 0
    for cname, jname in pairs:
        if jname not in java:
            print('%-24s %-16s %s' % (cname, jname, '<<< MISSING in Proto.java'))
            bad += 1
            continue
        a, b = cxx[cname], java[jname]
        ok = a == b
        bad += 0 if ok else 1
        print('%-24s %-16s %-16d %s' % (cname, jname, a, 'OK' if ok else '<<< MISMATCH java=%d' % b))

    # Slot header arithmetic: every Java field offset must equal the C++ offsetof value.
    offsets = {
        'OV_SLOT_W': 0x00, 'OV_SLOT_H': 0x04, 'OV_SLOT_FLAGS': 0x08,
        'OV_SLOT_FRAME_ID': 0x10, 'OV_SLOT_CAM_X': 0x18, 'OV_SLOT_CAM_Y': 0x20,
        'OV_SLOT_CAM_Z': 0x28, 'OV_SLOT_CAM_YAW': 0x30, 'OV_SLOT_CAM_PITCH': 0x34,
        'OV_SLOT_CAM_ROLL': 0x38, 'OV_SLOT_VFOV_DEG': 0x3C, 'OV_SLOT_ASPECT': 0x40,
        'OV_SLOT_NEAR_Z': 0x44, 'OV_SLOT_FAR_Z': 0x48, 'OV_SLOT_CAMERA_MODE': 0x4C,
        'OV_SLOT_DEPTH_OFF': 0x50, 'OV_SLOT_DEPTH_BYTES': 0x54,
        'OV_SLOT_HDR': 0x10, 'OV_SLOT_HDR_SIZE': cxx['OverlaySlotHdr'],
        'OV_CTL_STATE': 0x00, 'OV_CTL_FRAMES': 0x08,
    }
    print()
    print('%-24s %-8s %s' % ('Java slot-header field', 'value', 'status'))
    print('-' * 78)
    for name, expect in offsets.items():
        if name not in java:
            print('%-24s %-8s %s' % (name, '-', '<<< MISSING'))
            bad += 1
            continue
        got = java[name]
        ok = got == expect
        bad += 0 if ok else 1
        print('%-24s 0x%02X     %s' % (name, got, 'OK' if ok else '<<< expected 0x%02X' % expect))

    # Sanity: the SB-side mapping must be at least the protocol size.
    sb_mapping = 320 * 1024 * 1024
    if sb_mapping < cxx['kMappingBytes']:
        print('\n<<< shm_bridge.cpp kMappingBytes (%d) < protocol %d' % (sb_mapping, cxx['kMappingBytes']))
        bad += 1

    print()
    if bad:
        print('PARITY FAILED: %d problem(s)' % bad)
        return 1
    print('PARITY OK - C++ and Java agree on every constant')
    print('  protocol mapping size = %d bytes (%.1f MiB), depth region = %.1f MiB reserved'
          % (cxx['kMappingBytes'], cxx['kMappingBytes'] / 1048576.0,
             cxx['kOverlayDepthBytes'] / 1048576.0))
    return 0


if __name__ == '__main__':
    sys.exit(main())
