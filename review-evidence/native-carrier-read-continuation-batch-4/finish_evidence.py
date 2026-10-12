from pathlib import Path
import json,hashlib,os,re,datetime
R=Path('/workspace/scratch/67f3a8ccac99/earth-platform/native-survival-039');E=R/'review-evidence/native-carrier-read-continuation-batch-4';G=R/'minecraft/run-native-carrier-read-continuation-004';B=R.parent/'native-test-builds/native-carrier-read-build-004'
def sha(p):
 with p.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def create(p,v):
 with p.open('x') as f:json.dump(v,f,indent=2);f.write('\n')
Q=E/'pre-java-inputs';v=json.loads((Q/'receipt.json').read_text());vm=(Q/'survivalGameTestRunVmArgs.txt').read_text().splitlines();cp=(Q/'survivalGameTestLegacyClasspath.txt').read_text().splitlines();artifacts=set();dirs=set();modules=0
for row in v['files']:
 p=Path(row['source']);c=Q/row['copy'];assert p.stat().st_size==c.stat().st_size==row['bytes'] and sha(p)==sha(c)==row['sha256']
def path(value):
 assert value and len(value.encode())<=4096 and value.strip()==value and not any(c in value for c in ['\"',"'",'\n']);p=Path(value);assert p.is_absolute();p=p.resolve(strict=True)
 if p.is_file():assert p.suffix=='.jar';artifacts.add(str(p))
 else:assert p.is_dir();dirs.add(str(p))
i=0
while i<len(vm):
 a=vm[i]
 if a in ['-p','--module-path']:
  modules+=1;i+=1;assert i<len(vm)
  for x in vm[i].split(os.pathsep):path(x)
 else:assert not a.startswith('--module-path=') and not a.startswith('-p=')
 i+=1
assert modules==1
for x in cp+v['classPath']:path(x)
mods=[]
for x in v['allJvmArgs']:
 if x.startswith('-Dfml.modFolders='):
  mods.append(x)
  for item in x.split('=',1)[1].split(os.pathsep):
   name,value=item.split('%%');assert name=='earthplatform';p=Path(value).resolve(strict=True);assert p.is_dir();dirs.add(str(p))
assert len(mods)==1
reported={row['path']:row for row in v['preparedArtifacts']};assert len(reported)==len(v['preparedArtifacts']) and artifacts==set(reported)
parent=json.loads((E/'source-inputs.json').read_text());build=json.loads((E/'isolated-build-files.json').read_text())
for name,row in reported.items():
 p=Path(name);s=p.stat();assert s.st_size==row['bytes'] and sha(p)==row['sha256']==parent[name] and row['identityUnchanged'] is True
 assert row['fileKey']==f'(dev={s.st_dev:x},ino={s.st_ino})'
dirsets={}
for name in dirs:
 p=Path(name);prefix=str(p.relative_to(B))+'/'
 actual={str(B/n):row['sha256'] for n,row in build.items() if n.startswith(prefix)}
 expected={n:h for n,h in parent.items() if n.startswith(name+'/')}
 if p == B/'resources/registeredSessionFixture':
  assert expected == {}
  source=Path('/workspace/shared/native-carrier-read-continuation-v6/src/main/resources/earthplatform.carrier-io-capture.mixins.json')
  expected={str(p/source.name):parent[str(source)]}
  assert ':processRegisteredSessionFixtureResources' in (E/'run.log').read_text()
 assert actual==expected,(name,len(actual),len(expected));dirsets[name]=len(actual)
create(E/'actual-prepared-full-set.json',{'modulePathFlags':modules,'canonicalRegularJarCount':len(artifacts),'allPreparedArtifactRowsExactlyEqual':True,'allPhysicalKeysEqualAfterExit':True,'directorySets':dirsets,'fmlModFolders':mods,'receiptSha256':sha(Q/'receipt.json'),'generatedFixtureResource':'The one resource was created by original processRegisteredSessionFixtureResources after compile; its exact complete set/hash joins to the locked v5 source and preserved build archive.','qualification':'Original JavaExec doFirst snapshot and selected artifacts; independent reparse with complete FML directory sets, no independent OS full-argv claim.'})
logp=E/'run.log';assert logp.stat().st_size<24*1024**2;log=logp.read_text();lines=log.splitlines(keepends=True)
findings=[]
for i,line in enumerate(lines):
 if '/ERROR]' not in line and 'Failed to select mixin config: earthplatform.carrier-io-capture.mixins.json' not in line:continue
 j=i+1
 while j<len(lines) and not re.match(r'^\[\d\d:',lines[j]):j+=1
 block=''.join(lines[i:j]);findings.append({'line':i+1,'sha256':hashlib.sha256(block.encode()).hexdigest(),'text':block})
create(E/'log-findings.json',{'blocks':findings,'qualification':'Includes primary caught configuration-selection warning and all ERROR blocks; no error-free or successful transformation claim.'})
assert 'Failed to select mixin config: earthplatform.carrier-io-capture.mixins.json' not in log
reports=G/'world/native-excavation-session-evidence';first=(reports/'first-failure.txt').read_text()
assert first.startswith('phase=native-held-transition\nio.earthplatform.minecraft.survival.world.SurvivalRefusal: Earth survival: Resolve the native menu and accepted work before ACTIVE save')
contracts=json.loads((reports/'carrier-read-contracts.json').read_text());assert contracts['passedCases']==7 and len(contracts['names'])==7
final=json.loads((reports/'carrier-read-continuations-original-server-stopped-observed.json').read_text());assert final['admitted']==841
assert all(final[k]==841 for k in ['factoryReturns','dispatches','dispatchReturns','taskRuns','taskReturns','callbacks','callbackSuccesses','physicallyRetired'])
assert final['unresolved']==final['firstFailureCode']==final['callbackFailures']==0
assert final['sourcePayloadAuthority'] is False and final['closeAuthority'] is False
assert int((E/'process-exit.txt').read_text())==1
snapshots={str(p.relative_to(G)):{'bytes':p.stat().st_size,'sha256':sha(p)} for folder in ['carrier-io-intermediate-transform-evidence','carrier-read-late-transform-evidence'] for p in sorted((G/folder).iterdir()) if p.is_file()}
assert sum(n.endswith('.class') for n in snapshots)==10
create(E/'transform-and-roster-bindings.json',snapshots)
create(E/'terminal.json',{'utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'status':'ACTUAL_NATIVE_READ_ROOT_PREFIX_WITH_LIFECYCLE_FAILURE','processExit':1,'timeout':False,'lockedInputs':len(parent),'allInputsUnchanged':True,'firstFailure':first.splitlines()[:2],'expectedSoleTest':'nativeregisteredsessiongametests.actual_registered_carrier_read_continuation_v1','nativeTests':1,'nativeFailures':1,'readContractCasesExecuted':7,'nativeReadCounts':final,'intermediateSnapshots':9,'lateSnapshots':1,'strictPhysicalCloseQualified':False,'preparedArtifacts':len(artifacts),'preparedDirectorySets':dirsets,'argumentReceiptSha256':sha(Q/'receipt.json'),'archives':json.loads((E/'archives.json').read_text())['archives'],'logSha256':sha(logp),'qualifications':['Actual background read factory/dispatch/task/function retirement is the scoped prefix; exact checker and independent source/transform replay remain distinct.','Original ACTIVE-save readiness refusal and physical-close assertion remain failed; no native save/return/restart or whole-session lifecycle pass.','Active69 source leaves saveOutput unbound in this fixture, so output readiness is a known prerequisite; the thrown readiness message does not report an individual predicate trace.','After-late ClassNode snapshots remain intermediate stage evidence, not final loaded bytecode.','Source payload, clean close and whole-server callback termination authority remain false; all charges retained.','All raw outputs remain preserved; build archive is on overlay.']})
print((E/'terminal.json').read_text())
