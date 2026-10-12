#!/usr/bin/env python3
"""Offline census of each original categorical pixel touched by the bounded profile."""
from pathlib import Path
import struct,math,json,csv,collections
P=Path(__file__).resolve().parents[1]
b=(P/'data/worldcover-2021-proof/everest/landcover.elc').read_bytes();magic,w,h,west,north,dx,dz=struct.unpack('>III4d',b[:44]);v=b[44:];M=6378137*math.pi/180
regions={'all_snapped_far_centres_union_plus_source_halo':(9675776-8,-3116544-8,9680384+8,-3111936+8),'travel_512m_box':(604863*16,-194659*16,604895*16,-194627*16),'native_125_union_plus_source_halo':(604863*16-32-8,-194659*16-32-8,604895*16+32+8,-194627*16+32+8),'anchor_edit_neighbourhood':(9678074-8,-3114278-8,9678074+9,-3114278+9)}
report={'schema':'earth-alpine-030-source-cover-census-v1','counts':'Every original pixel whose area intersects the closed conservative region rectangle. No resampling. Pixel-edge touch is included conservatively.','regions':{}}
rows=[]
for name,(x0,z0,x1,z1) in regions.items():
 lon0=x0/M;lon1=x1/M;lat0=-z0/M;lat1=-z1/M
 l=math.floor((lon0-west)/dx);r=math.floor((lon1-west)/dx);t=math.floor((north-lat0)/dz);bt=math.floor((north-lat1)/dz)
 cs=collections.Counter(v[j*w+i] for j in range(t,bt+1) for i in range(l,r+1));un=[]
 for j in range(t,bt+1):
  for i in range(l,r+1):
   c=v[j*w+i]
   if c not in (60,70):
    lon=west+(i+.5)*dx;lat=north-(j+.5)*dz
    if name=='all_snapped_far_centres_union_plus_source_halo':rows.append({'class_code':c,'column':i,'row':j,'latitude':lat,'longitude':lon,'global_x':lon*M,'global_z':-lat*M})
    un.append((lat,lon))
 report['regions'][name]={'projected_half_open_bounds':[x0,z0,x1,z1],'geographic_bounds':{'west':lon0,'east':lon1,'north':lat0,'south':lat1},'source_pixel_columns_inclusive':[l,r],'source_pixel_rows_inclusive':[t,bt],'source_pixel_counts_by_code':dict(sorted(cs.items())),'unresolved_pixel_count':len(un),'unresolved_pixel_centre_bbox':{'south':min(a for a,b in un),'north':max(a for a,b in un),'west':min(b for a,b in un),'east':max(b for a,b in un)} if un else None}
report['policy']='Classes60/70 use reviewed alpine snow/slope/rock policy. Mapped80 is retained as measured WorldCover label and explicitly interpreted as conservative opaque rock because no authoritative water-level surface is available. No water, vegetation, ice-depth or lake is invented. Unknown/outside labels fail preparation.'
(P/'earth-alpine-results/landcover-census.json').write_text(json.dumps(report,indent=2,sort_keys=True)+'\n')
with (P/'earth-alpine-results/unresolved-source-pixels.csv').open('w') as f:
 writer=csv.DictWriter(f,fieldnames=rows[0].keys());writer.writeheader();writer.writerows(rows)
print(json.dumps(report,indent=2))
