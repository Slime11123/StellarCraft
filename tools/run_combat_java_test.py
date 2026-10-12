from pathlib import Path
import subprocess
root=Path(__file__).resolve().parents[1]
patch=sorted((root/'mc-mod/build').glob('gameplay-patch-*'))[-1]
args=(patch/'javac.args').read_text().splitlines();cp=str(patch/'classes')+';'+args[args.index('-classpath')+1].strip('"')
out=root/'mc-mod/build/test-combat';out.mkdir(exist_ok=True)
jdk=Path(r'C:/Users/StickySlime/jdk-21.0.12.1+1/bin')
def quoted(x):return '"'+str(x).replace('\\','/')+'"'
compile=['--enable-preview','--release','21','-proc:none','-classpath',quoted(cp),'-d',quoted(out),quoted(root/'mc-mod/tests/CombatProtocolRegression.java')]
(out/'compile.args').write_text('\n'.join(compile));subprocess.run([str(jdk/'javac.exe'),'@'+str(out/'compile.args')],check=True)
run=['--enable-preview','--enable-native-access=ALL-UNNAMED','-classpath',quoted(str(out)+';'+cp),'CombatProtocolRegression']
(out/'run.args').write_text('\n'.join(run));subprocess.run([str(jdk/'java.exe'),'@'+str(out/'run.args')],check=True)
