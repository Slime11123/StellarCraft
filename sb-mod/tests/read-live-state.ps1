# Read-only inspection of bridge state; no game process memory or input is modified.
$ErrorActionPreference='Stop'
Add-Type -TypeDefinition @'
using System; using System.Runtime.InteropServices;
public static class StellarCraftStateReader {
 [DllImport("kernel32.dll",CharSet=CharSet.Unicode)] public static extern IntPtr OpenFileMapping(uint access,bool inherit,string name);
 [DllImport("kernel32.dll")] public static extern IntPtr MapViewOfFile(IntPtr map,uint access,uint hi,uint lo,UIntPtr size);
 [DllImport("kernel32.dll")] public static extern bool UnmapViewOfFile(IntPtr p);
 [DllImport("kernel32.dll")] public static extern bool CloseHandle(IntPtr h);
}
'@
$handle=[StellarCraftStateReader]::OpenFileMapping(4,$false,'Local\StellarCraft_v1')
if($handle -eq [IntPtr]::Zero){throw 'Mapping unavailable'}
$view=[StellarCraftStateReader]::MapViewOfFile($handle,4,0,0,[UIntPtr]::Zero)
try {
 $b=[byte[]]::new(768)
 [Runtime.InteropServices.Marshal]::Copy($view,$b,0,$b.Length)
 [pscustomobject]@{
 SbSeq=[BitConverter]::ToUInt32($b,256); SbFlags=[BitConverter]::ToUInt32($b,260)
 NativeSerial=[BitConverter]::ToUInt32($b,268)
 McSeq=[BitConverter]::ToUInt32($b,512); McFlags=[BitConverter]::ToUInt32($b,516)
 McX=[BitConverter]::ToDouble($b,520); McY=[BitConverter]::ToDouble($b,528); McZ=[BitConverter]::ToDouble($b,536)
 McYaw=[BitConverter]::ToSingle($b,544); McPitch=[BitConverter]::ToSingle($b,548)
 McEye=[BitConverter]::ToSingle($b,552); McAck=[BitConverter]::ToUInt32($b,556)
 }
} finally {
 [StellarCraftStateReader]::UnmapViewOfFile($view) | Out-Null
 [StellarCraftStateReader]::CloseHandle($handle) | Out-Null
}
