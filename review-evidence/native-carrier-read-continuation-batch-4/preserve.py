from pathlib import Path
import os,stat,json,hashlib,tarfile,datetime,shutil
ROOT=Path('/workspace/scratch/67f3a8ccac99/earth-platform/native-survival-039')
E=ROOT/'review-evidence/native-carrier-read-continuation-batch-4'
G=ROOT/'minecraft/run-native-carrier-read-continuation-004'
B=ROOT.parent/'native-test-builds/native-carrier-read-build-004'
A=E/'isolated-build-archive'
def sha(p):
 with p.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def create(p,v):
 with p.open('x') as f:json.dump(v,f,indent=2);f.write('\n')
def collect(root):
 files={};dirs={};todo=[root];count=0
 while todo:
  d=todo.pop();empty=True
  with os.scandir(d) as it:
   for item in it:
    empty=False;count+=1;assert count<=8192
    p=Path(item.path);n=str(p.relative_to(root));assert len(n.encode())<=1024
    s=item.stat(follow_symlinks=False)
    if stat.S_ISDIR(s.st_mode):todo.append(p)
    else:
     assert stat.S_ISREG(s.st_mode),n;assert len(files)<4096
     files[n]={'bytes':s.st_size,'sha256':sha(p)}
  dirs[str(d.relative_to(root))]={'mode':stat.S_IMODE(d.stat().st_mode),'empty':empty}
 return dict(sorted(files.items())),dict(sorted(dirs.items()))
def preserve(label,root,target):
 files,dirs=collect(root);assert sum(r['bytes'] for r in files.values())<512*1024**2
 create(E/(label+'-files.json'),files);create(E/(label+'-directories.json'),dirs)
 assert shutil.disk_usage(target.parent).free>1024**3
 with target.open('xb') as out:
  with tarfile.open(fileobj=out,mode='w:gz') as tar:
   for n in files:tar.add(root/n,arcname=n,recursive=False)
 seen=set()
 with tarfile.open(target,'r|gz') as tar:
  for member in tar:
   assert member.isfile() and member.name in files and member.name not in seen
   seen.add(member.name);assert member.size==files[member.name]['bytes']
   with tar.extractfile(member) as f:assert hashlib.file_digest(f,'sha256').hexdigest()==files[member.name]['sha256']
 assert seen==set(files);assert collect(root)==(files,dirs)
 return {'path':str(target),'bytes':target.stat().st_size,'sha256':sha(target),'files':len(files),'directories':len(dirs),'manifestSha256':sha(E/(label+'-files.json')),'directoryManifestSha256':sha(E/(label+'-directories.json'))}
assert json.loads((E/'post-jvm-census.json').read_text())['javaProcesses']==[]
A.mkdir(exist_ok=False)
archives={'game':preserve('game',G,E/'complete-game.tar.gz'),'isolated-build':preserve('isolated-build',B,A/'complete-isolated-build.tar.gz')}
create(E/'archives.json',{'utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'archives':archives,'qualification':'Complete file archives plus directory sidecars; build archive is in the workspace and has not been Library persisted.'})
print(json.dumps(archives,indent=2))
