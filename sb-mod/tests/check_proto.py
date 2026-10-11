import os
import subprocess
import sys

MSVC = r'C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Tools\MSVC\14.44.35207'
KITS = r'C:\Program Files (x86)\Windows Kits\10'
SDK = KITS + r'\Include\10.0.22621.0'
SDKLIB = KITS + r'\Lib\10.0.22621.0'

HERE = os.path.dirname(os.path.abspath(__file__))
SB = os.path.dirname(HERE)                       # sb-mod/
OUT = os.path.join(SB, 'build', 'manual')

bat = '\r\n'.join([
    '@echo off',
    'set "PATH=%s\\bin\\Hostx64\\x64;%%PATH%%"' % MSVC,
    'set "INCLUDE=%s\\include;%s\\ucrt;%s\\shared;%s\\um"' % (MSVC, SDK, SDK, SDK),
    'set "LIB=%s\\lib\\x64;%s\\ucrt\\x64;%s\\um\\x64"' % (MSVC, SDKLIB, SDKLIB),
    'cd /d "%~dp0"',
    # NOTE: no quotes around /Fo — a trailing backslash before a closing quote escapes it
    # and cl then sees no source file ("D8003: missing source file name").
    'cl /nologo /std:c++17 /EHsc /W4 /Fe:build\\manual\\proto_layout_check.exe '
    '/Fo:build\\manual\\ tests\\proto_layout_check.cpp || exit /b 1',
    'echo COMPILE_OK',
    'build\\manual\\proto_layout_check.exe',
]) + '\r\n'

path = os.path.join(SB, '_check_proto.bat')
with open(path, 'wb') as f:
    f.write(bat.encode('ascii'))

r = subprocess.run(['cmd', '/c', '_check_proto.bat'], cwd=SB,
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
print(r.stdout)
if r.stderr.strip():
    print('STDERR:', r.stderr)
sys.exit(r.returncode)
