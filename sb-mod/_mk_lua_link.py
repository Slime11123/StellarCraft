import os

MSVC = r'C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Tools\MSVC\14.44.35207'
KITS = r'C:\Program Files (x86)\Windows Kits\10'
SDK = KITS + r'\Include\10.0.22621.0'
SDKLIB = KITS + r'\Lib\10.0.22621.0'

for p in (MSVC, SDK + r'\ucrt', SDKLIB + r'\um\x64'):
    assert os.path.isdir(p), 'missing: ' + p

objs = sorted(f for f in os.listdir('.') if f.endswith('.obj') and f != 'luac.obj')

env = [
    'set "PATH=%s\\bin\\Hostx64\\x64;%%PATH%%"' % MSVC,
    'set "INCLUDE=%s\\include;%s\\ucrt;%s\\shared;%s\\um"' % (MSVC, SDK, SDK, SDK),
    'set "LIB=%s\\lib\\x64;%s\\ucrt\\x64;%s\\um\\x64"' % (MSVC, SDKLIB, SDKLIB),
]

lines = ['@echo off'] + env + [
    'cd /d "%~dp0"',
    'link /nologo /OUT:build\\manual\\lua.exe ' + ' '.join(objs),
    'echo LINK_EXITCODE=%ERRORLEVEL%',
]
with open('_link_lua.bat', 'wb') as f:
    f.write(('\r\n'.join(lines) + '\r\n').encode('ascii'))
print('objects:', len(objs), '| msvc+sdk env written (no vcvars, no reg.exe)')
