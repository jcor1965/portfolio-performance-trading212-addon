from pathlib import Path
import shutil, subprocess, hashlib, json, uuid
import argparse
parser=argparse.ArgumentParser()
parser.add_argument('--portfolio',required=True)
parser.add_argument('--java-home',required=True)
args=parser.parse_args()
root=Path(__file__).resolve().parents[1]
package=root/'dist/installer'
base=Path(args.portfolio).resolve()
test=root/'.build'/('installer-validation-'+uuid.uuid4().hex[:8])
test.mkdir(parents=True)
app=test/'portfolio';shutil.copytree(base,app)
bundles=app/'configuration/org.eclipse.equinox.simpleconfigurator/bundles.info'
config=app/'configuration/config.ini'
def hashfiles():return {p.relative_to(app).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in app.rglob('*') if p.is_file() and 'addons' not in p.relative_to(app).parts}
before=hashfiles();originalBundles=bundles.read_bytes();originalConfig=config.read_bytes()
checks=[]
def check(b,label):
 assert b,label
 checks.append(label);print('PASS '+label,flush=True)
def run(mode,expected=0):
 p=subprocess.run(['powershell.exe','-NoProfile','-File',str(package/'Manage-Addon.ps1'),'-Mode',mode,'-PortfolioPath',str(app),'-WorkspacePath',str(test/'gui-workspace'),'-Quiet'],capture_output=True)
 assert p.returncode==expected,(mode,p.returncode,p.stdout.decode(errors='replace'),p.stderr.decode(errors='replace'))
 return p
run('Install')
after=hashfiles(); changed={p for p in before if before[p]!=after[p]}
check(changed=={'configuration/config.ini','configuration/org.eclipse.equinox.simpleconfigurator/bundles.info'},'only two original configuration files modified')
check(before['PortfolioPerformance.exe']==after['PortfolioPerformance.exe'],'official executable unchanged')
run('Install');check(hashfiles()==after,'second install is idempotent')
run('Status')
# Execute the installed fragment through the ORIGINAL configuration, no overlay.
jdk=Path(args.java_home).resolve()
launcher=next((app/'plugins').glob('org.eclipse.equinox.launcher_*.jar'))
args=[str(jdk/'bin/java.exe'),'-Duser.home='+str(test/'home'),'-Dt212.addon.evidence='+str(test/'evidence'),'-jar',str(launcher),'-install',str(app),'-configuration',str(app/'configuration'),'-data',str(test/'workspace'),'-clean','-nosplash','-application','name.abuchen.portfolio.ui.selftest','-consoleLog']
import os
synthetic_env=os.environ.copy()
synthetic_env.pop('PP_TRADING212_PRIVATE_DIR',None)
p=subprocess.run(args,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,env=synthetic_env)
(test/'normal-configuration-test.log').write_bytes(p.stdout)
check(p.returncode==0 and b'ADDON SELFTEST PASS:' in p.stdout,'installed add-on resolves in original configuration and passes selftest')
# Runtime caches are not original shipped files. Verify originals individually.
check(all((app/k).exists() and hashlib.sha256((app/k).read_bytes()).hexdigest()==v for k,v in before.items() if k not in changed),'all other shipped files unchanged after launch')
model=test/'gui-workspace/.metadata/.plugins/org.eclipse.e4.workbench/workbench.xmi'
model.parent.mkdir(parents=True,exist_ok=True)
shutil.copy2(root/'tests/fixtures/workbench.xmi',model)
originalModel=model.read_bytes()
run('Uninstall')
import re
expectedModel=re.sub(rb'<(?:[\w]+:)?(?:children|handlers|commands)\b[^>]*\belementId="org\.trading212\.ppaddon\.import(?:\.handler)?"[^>]*/>',b'',originalModel)
check(model.read_bytes()==expectedModel,'only three add-on UI nodes removed; all other workspace bytes preserved')
check(bundles.read_bytes()==originalBundles and config.read_bytes()==originalConfig,'uninstall restores both configurations byte for byte')
check(not (app/'addons/trading212/org.trading212.ppaddon_0.1.0.jar').exists(),'uninstall removes only owned add-on JAR')
check(list((app/'addons/trading212/backups').rglob('bundles.info')),'backups retained')
run('Install')
# Simulated update: replace host metadata and preserve unrelated new settings.
s=bundles.read_text();s=s.replace('name.abuchen.portfolio.ui,0.89.0,','name.abuchen.portfolio.ui,0.89.1,')
s+='\n# SIMULATED UPDATE - retain this line\n'
bundles.write_text(s)
with config.open('a') as f:f.write('\n# SIMULATED UPDATE\nexample.future.setting=preserve-me\n')
current=(bundles.read_bytes(),config.read_bytes())
run('Install',1)
check(current==(bundles.read_bytes(),config.read_bytes()),'unvalidated 0.89.1 refused without modifying configuration')
run('Uninstall')
check('name.abuchen.portfolio.ui,0.89.1,' in bundles.read_text() and '# SIMULATED UPDATE' in bundles.read_text(),'uninstall after simulated update preserves new bundle metadata')
check('example.future.setting=preserve-me' in config.read_text(),'uninstall after simulated update preserves unrelated settings')
check('org.trading212.ppaddon,' not in bundles.read_text() and '# BEGIN Trading212' not in config.read_text(),'only add-on registration removed after update')
# Restore fixture to original version for normal-start visual testing.
bundles.write_bytes(originalBundles);config.write_bytes(originalConfig)
run('Install')
# Simulate p2 dropping unmanaged registration, as it may regenerate bundles.info.
installed=bundles.read_bytes();bundles.write_bytes(originalBundles)
status=run('Status').stdout
check(b'Registered: False' in status,'status detects registration removed by updater')
bundles.write_bytes(installed)
(test/'checks.json').write_text(json.dumps(checks,indent=2))
print('INSTALLER CHECKS PASS:',len(checks),str(test),flush=True)
