from pathlib import Path
import os,stat,json,hashlib,tarfile,datetime,re,zipfile,subprocess
R=Path('/workspace/scratch/67f3a8ccac99/earth-platform/native-survival-039');E=R/'review-evidence/native-codec-boundary-batch-3';L=R/'review-evidence/native-codec-launch-receipt-3';G=R/'minecraft/run-native-codec-boundary-003';B=Path('/tmp/earthplatform-foundation69-build')
def sha(p):
 with p.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def create(p,v):
 with p.open('x') as f:json.dump(v,f,indent=2);f.write('\n')
java=[]
for p in Path('/proc').iterdir():
 if not p.name.isdigit():continue
 try:
  if (p/'comm').read_text().strip()=='java':java.append(int(p.name))
 except FileNotFoundError:pass
create(E/'post-jvm-census.json',{'utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'javaProcesses':java});assert not java
subprocess.run(['python3',str(L/'lock-complete.py'),'--prepared'],check=True)
subprocess.run(['python3','/workspace/shared/native-network-flush-owner-draft/local-scope-codec3-route-v2/source-lock.py',str(R),str(E/'source-inputs.json')],check=True)
Q=E/'pre-java-inputs';v=json.loads((Q/'receipt.json').read_text());vm=(Q/'survivalGameTestRunVmArgs.txt').read_text().splitlines();cp=(Q/'survivalGameTestLegacyClasspath.txt').read_text().splitlines();artifacts=set();dirs=set();modules=0
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
parent=json.loads((L/'complete-parent-input-lock.json').read_text())['files']
for name,row in reported.items():
 p=Path(name);s=p.stat();assert s.st_size==row['bytes'] and sha(p)==row['sha256']==parent[name] and row['identityUnchanged'] is True
 assert row['fileKey']==f'(dev={s.st_dev:x},ino={s.st_ino})'
dirsets={}
for name in dirs:
 p=Path(name);actual={str(f.resolve()):sha(f) for f in p.rglob('*') if f.is_file()};expected={n:h for n,h in parent.items() if n.startswith(name+'/')};assert actual==expected;dirsets[name]=len(actual)
create(E/'actual-prepared-full-set.json',{'modulePathFlags':modules,'canonicalRegularJarCount':len(artifacts),'allPreparedArtifactRowsExactlyEqual':True,'allPhysicalKeysEqualAfterExit':True,'directorySets':dirsets,'fmlModFolders':mods,'receiptSha256':sha(Q/'receipt.json'),'qualification':'Original JavaExec doFirst argument snapshot and selected artifacts, independently reparsed; no independent OS full-argv claim.'})
def collect():
 files={};directories={};todo=[G];count=0
 while todo:
  d=todo.pop();empty=True
  with os.scandir(d) as it:
   for item in it:
    empty=False;count+=1;assert count<=8192;p=Path(item.path);n=str(p.relative_to(G));assert len(n.encode())<=1024;s=item.stat(follow_symlinks=False)
    if stat.S_ISDIR(s.st_mode):todo.append(p)
    else:assert stat.S_ISREG(s.st_mode);assert len(files)<4096;files[n]={'bytes':s.st_size,'sha256':sha(p)}
  directories[str(d.relative_to(G))]={'mode':stat.S_IMODE(d.stat().st_mode),'empty':empty}
 return dict(sorted(files.items())),dict(sorted(directories.items()))
files,directories=collect();create(E/'game-files.json',files);create(E/'game-directories.json',directories)
A=E/'complete-game.tar.gz'
with A.open('xb') as out:
 with tarfile.open(fileobj=out,mode='w:gz') as tar:
  for n in files:tar.add(G/n,arcname=n,recursive=False)
seen=set()
with tarfile.open(A,'r|gz') as tar:
 for m in tar:
  assert m.isfile() and m.name in files and m.name not in seen;seen.add(m.name);assert m.size==files[m.name]['bytes']
  with tar.extractfile(m) as f:assert hashlib.file_digest(f,'sha256').hexdigest()==files[m.name]['sha256']
assert seen==set(files) and collect()==(files,directories)
jar=R/'review-evidence/survival-foundation-batch-69/verified-main.jar';classes=json.loads((R/'review-evidence/survival-foundation-batch-69/main-class-sha256.json').read_text())
with zipfile.ZipFile(jar) as z:
 assert {n:hashlib.sha256(z.read(n)).hexdigest() for n in z.namelist() if n.endswith('.class')}==classes
 for n,h in classes.items():assert sha(B/'classes/java/main'/n)==h
log=(E/'run.log').read_text();lines=log.splitlines(keepends=True);errors=[]
for i,line in enumerate(lines):
 if '/ERROR]' not in line:continue
 j=i+1
 while j<len(lines) and not re.match(r'^\[\d\d:',lines[j]):j+=1
 block=''.join(lines[i:j]);errors.append({'line':i+1,'sha256':hashlib.sha256(block.encode()).hexdigest(),'text':block})
create(E/'log-error-qualification.json',{'blocks':errors,'qualification':'Original pretest environmental errors remain disclosed; no error-free startup claim.'})
assert len(errors)==2
assert 'All 1 required tests passed' in log and 'required tests failed' not in log and 'All dimensions are saved' in log and 'Game test server shutting down' in log
assert 'NativeOriginalPacketEncoderBoundaryMixin' in log and 'NativeOriginalPacketDecoderBoundaryMixin' in log
assert int((E/'exit-code.txt').read_text())==0 and int((L/'process-exit.txt').read_text())==0
create(E/'terminal.json',{'status':'PASS_ACTUAL_NATIVE_PACKET_MATCHER_PASS_THROUGH_AND_CODEC_BRANCHES','utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'processExit':0,'timeout':False,'nativeTests':1,'nativeFailures':0,'expectedSoleTest':'nativecodecboundarygametests.original_encode_decode_outer_boundaries','authorInputs':1232,'parentInputs':3218,'mainClassesExactly69':len(classes),'retainedJarSha256':sha(jar),'actualPreparedArtifacts':len(artifacts),'actualPreparedDirectorySets':dirsets,'gameFiles':len(files),'gameDirectories':len(directories),'archiveBytes':A.stat().st_size,'archiveSha256':sha(A),'logSha256':sha(E/'run.log'),'argumentReceiptSha256':sha(Q/'receipt.json'),'sourceBoundAssertions':{'exactNativeMatcher':True,'originalNonPacketPassThroughs':1,'samePromiseAndOutboundIdentity':True,'passThroughAllocationEncodeDecodeCountsZero':True,'acceptedRounds':16,'malformedInputRound':17,'originalRetainedCumulationAndOneCloseCleanup':True,'counterfeitRefusal':True},'nativeAllThreeDimensionsSaved':True,'qualifications':['Confined original Minecraft codec/EmbeddedChannel branch regression; no production Local adoption or reusable save authority.','Actual006 five unresolved rows lacked per-flight type identities; this pass does not retrospectively identify those rows.','All charges remain retained to process exit.','Original JavaExec doFirst snapshot is separate from native log evidence; actual module/legacy/task paths and FML directory sets were fully reparsed and joined.','Two pretest environmental errors are preserved.']})
print((E/'terminal.json').read_text())
