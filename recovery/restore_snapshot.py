#!/usr/bin/env python3
"""Reconstruct the source-only handoff. Does not build, test, install or run the game."""
import argparse, hashlib, io, json, pathlib, tarfile, tempfile
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('destination',type=pathlib.Path,help='New/empty destination folder')
a=p.parse_args(); base=pathlib.Path(__file__).resolve().parent
info=json.loads((base/'CONTENTS.json').read_text())
if a.destination.exists() and any(a.destination.iterdir()):p.error('Destination must be absent or empty')
with tempfile.TemporaryFile() as combined:
 h=hashlib.sha256()
 archive=base/info['archive']
 inputs=[archive] if archive.is_file() else [base/x['name'] for x in info['parts']]
 for f in inputs:
  with f.open('rb') as src:
   while chunk:=src.read(1024*1024):h.update(chunk);combined.write(chunk)
 if h.hexdigest()!=info['sha256']:raise SystemExit('Archive checksum mismatch; no files restored')
 combined.seek(0)
 with tarfile.open(fileobj=combined,mode='r:gz') as t:
  manifest=json.load(t.extractfile('manifest.json')); files=manifest['files']; seen=set()
  for rec in files:
   path=pathlib.PurePosixPath(rec['path'])
   if path.is_absolute() or '..' in path.parts or rec['path'] in seen:raise SystemExit('Unsafe/duplicate manifest path')
   seen.add(rec['path'])
  # Only explicitly named regular data objects are read. No archive paths, links or modes are extracted.
  cache={}
  for rec in files:
   key=rec['sha256']
   if key not in cache:
    member=t.getmember('objects/'+key)
    if not member.isfile():raise SystemExit('Non-regular content object')
    data=t.extractfile(member).read()
    if len(data)!=rec['bytes'] or hashlib.sha256(data).hexdigest()!=key:raise SystemExit('Content checksum mismatch')
    cache[key]=data
   target=a.destination/pathlib.Path(rec['path']);target.parent.mkdir(parents=True,exist_ok=True)
   target.write_bytes(cache[key]);target.chmod(rec['mode'])
print(f'Restored {len(files)} source/documentation/evidence files to {a.destination}. No game commands were run.')
