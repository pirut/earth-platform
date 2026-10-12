from pathlib import Path
import json,hashlib,shutil,datetime,xml.etree.ElementTree as E,zipfile,re
r=Path(__file__).resolve().parents[2];b=Path(__file__).resolve().parent;build=Path('/tmp/earthplatform-foundation69-build')
def h(p):
 with p.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def write(name,obj):
 with (b/name).open('x') as f:json.dump(obj,f,indent=2);f.write('\n')
inputs=json.loads((b/'source-inputs.json').read_text());assert all(h(r/p)==s for p,s in inputs.items());code=int((b/'exit-code.txt').read_text());assert code==0;log=(b/'build.log').read_text();assert 'BUILD SUCCESSFUL' in log;tasks=re.findall(r'^> Task :([^\n]+)',log,re.M);assert '15 actionable tasks: 15 executed' in log
for task in ['compileJava','compileTestJava','test','installedDhTest','shaderCompatibilityTest','jar']:
 assert task in tasks,task
parts={}
for part in ['test','installedDhTest','shaderCompatibilityTest']:
 shutil.copytree(build/'test-results'/part,b/'test-results'/part);tot={k:0 for k in ['tests','failures','errors','skipped','suites']}
 for f in (b/'test-results'/part).glob('TEST-*.xml'):
  x=E.parse(f).getroot();tot['suites']+=1
  for k in ['tests','failures','errors','skipped']:tot[k]+=int(x.attrib.get(k,0))
 assert tot['tests']>0 and all(tot[k]==0 for k in ['failures','errors','skipped']);parts[part]=tot
jar=build/'libs/earthplatform-0.1.0-engine-prototype.jar';shutil.copyfile(jar,b/'verified-main.jar');assert h(jar)==h(b/'verified-main.jar')
classes={str(p.relative_to(build/'classes/java/main')):h(p) for p in (build/'classes/java/main').rglob('*.class')};resources={str(p.relative_to(build/'resources/main')):h(p) for p in (build/'resources/main').rglob('*') if p.is_file()};expected=classes|resources
with zipfile.ZipFile(jar) as z:
 actual={n:hashlib.sha256(z.read(n)).hexdigest() for n in z.namelist() if not n.endswith('/')};assert set(actual)==set(expected)|{'META-INF/MANIFEST.MF'}
 for n,s in expected.items():assert actual[n]==s,n
 assert not any(n.endswith('InstalledSessionCallbackProbe$ListState.class') for n in actual)
write('main-class-sha256.json',classes);write('main-resource-sha256.json',resources);write('fresh-jar-complete-set.json',actual)
old=json.loads((r/'review-evidence/survival-foundation-batch-66/unchanged-shared-build-inputs-before.json').read_text());assert all(h(Path(p))==v['sha256'] and Path(p).stat().st_size==v['bytes'] for p,v in old.items());write('shared-artifacts-replay.json',{'reference':'review-evidence/survival-foundation-batch-66/unchanged-shared-build-inputs-before.json','referenceSha256':h(r/'review-evidence/survival-foundation-batch-66/unchanged-shared-build-inputs-before.json'),'count':len(old),'allUnchanged':True})
write('terminal.json',{'status':'PASS_FRESH69_COMPONENT_INSTALLED_HELPER_SHADER_PARTITIONS','finishedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'exitCode':code,'partitions':parts,'lockedInputs':len(inputs),'inputsUnchanged':True,'tasksExecuted':15,'freshBuildDirectory':str(build),'jar':str(b/'verified-main.jar'),'jarSha256':h(jar),'jarBytes':jar.stat().st_size,'classes':len(classes),'resources':len(resources),'jarEntries':len(actual),'qualification':'Reviewed nested Local scope, accepted Packet write lifetime and MAIN source/enqueue/body models compile/test pass. Actual corrected installed scope/payload closure, production adoption, repeatable save and combined scene remain unqualified.'});print((b/'terminal.json').read_text())
