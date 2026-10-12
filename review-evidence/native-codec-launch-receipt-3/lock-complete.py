from pathlib import Path
import hashlib,json,sys,datetime
R=Path('/workspace/scratch/67f3a8ccac99/earth-platform/native-survival-039');E=R/'review-evidence/native-codec-launch-receipt-3';D=Path('/workspace/shared/native-network-flush-owner-draft/local-scope-codec3-route-v2');B=Path('/tmp/earthplatform-foundation69-build');TARGET=E/'complete-parent-input-lock.json'
def sha(p):
 with p.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
if '--create' in sys.argv:
 rows={str(R/p):h for p,h in json.loads((R/'review-evidence/survival-foundation-batch-69/source-inputs.json').read_text()).items()}
 previous=json.loads((R/'review-evidence/native-carrier-read-continuation-batch-2/source-inputs.json').read_text())
 rows.update({p:h for p,h in previous.items() if '/toolchain-recovery/' in p or p=='/usr/bin/timeout'})
 for base in [B/'classes/java/main',B/'resources/main',B/'moddev/artifacts',D]:
  for p in base.rglob('*'):
   if p.is_file():rows[str(p.resolve())]=sha(p)
 for name in ['pre-java-receipt.init.gradle','run-parent-receipted.sh','lock-complete.py']:rows[str(E/name)]=sha(E/name)
 for p,h in rows.items():assert sha(Path(p))==h,p
 with TARGET.open('x') as f:json.dump({'createdAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'scope':'Exact focused69 all-source, main classes/resources, reviewed actual route and all known native/toolchain artifacts; fresh actual generated arguments and selected artifacts separately receipted by original JavaExec doFirst','files':dict(sorted(rows.items()))},f,indent=2);f.write('\n')
 print('LOCKED',len(rows))
else:
 rows=json.loads(TARGET.read_text())['files']
 for p,h in rows.items():assert sha(Path(p))==h,p
 print('VERIFIED',len(rows))
 if '--prepared' in sys.argv:
  Q=R/'review-evidence/native-codec-boundary-batch-3/pre-java-inputs';v=json.loads((Q/'receipt.json').read_text())
  for row in v['files']:
   assert sha(Q/row['copy'])==row['sha256'] and sha(Path(row['source']))==row['sha256']
  for row in v['preparedArtifacts']:
   assert rows[row['path']]==row['sha256'],row['path']
   assert sha(Path(row['path']))==row['sha256'] and Path(row['path']).stat().st_size==row['bytes']
  for path in v['classPath']:
   p=Path(path)
   if p.is_dir():
    current={str(f.resolve()):sha(f) for f in p.rglob('*') if f.is_file()}
    expected={n:h for n,h in rows.items() if n.startswith(str(p.resolve())+'/')}
    assert current==expected,path
  print('PREPARED ARTIFACTS JOINED',len(v['preparedArtifacts']))
