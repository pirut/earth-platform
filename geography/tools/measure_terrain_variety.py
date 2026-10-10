#!/usr/bin/env python3
"""Offline, read-only measurement of accepted Earth proof packs.

One sample per projected metre reproduces the Java bilinear block-centre contract;
it does NOT upgrade 1-arcsecond/15-arcsecond source data to measured 1 m terrain.
Writes only the explicit output directory (default geography/variety-results).
"""
from __future__ import annotations
import argparse, hashlib, json, math, re, struct
from dataclasses import dataclass
from pathlib import Path
import numpy as np
from scipy.ndimage import maximum_filter, minimum_filter, uniform_filter

ROOT = Path(__file__).resolve().parents[2]
R = 6378137.0


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def properties(path):
    # Accepted proof manifests use one key=value per line with Java unicode escapes.
    result = {}
    for line in path.read_text().splitlines():
        line = line.lstrip()
        if not line or line[0] in '#!':
            continue
        if '=' not in line or line.endswith('\\'):
            raise ValueError('Unsupported properties syntax; use Java canonical verification')
        key, value = line.split('=', 1)
        value = re.sub(r'\\u([0-9a-fA-F]{4})', lambda m: chr(int(m[1], 16)), value.lstrip())
        result[key.rstrip()] = value
    return result


def fingerprint(p):
    canonical = bytearray()
    for key in sorted(p):
        for value in [key, p[key]]:
            b = value.encode('utf-8'); canonical.extend(struct.pack('>i', len(b))); canonical.extend(b)
    return hashlib.sha256(canonical).hexdigest()


@dataclass
class Projection:
    name: str
    lat: float
    lon: float
    parallel: float
    @property
    def xfactor(self): return R * math.cos(math.radians(self.parallel)) * math.pi / 180.0
    @property
    def zfactor(self): return R * math.pi / 180.0
    def project(self, lat, lon): return (lon-self.lon)*self.xfactor, (self.lat-lat)*self.zfactor
    def geographic(self, x, z): return self.lat-z/self.zfactor, self.lon+x/self.xfactor
    def record(self): return {'id':'equirectangular-v1','name':self.name,'originLatitude':self.lat,'originLongitude':self.lon,'standardParallel':self.parallel,'radiusMeters':R}


class Pack:
    def __init__(self, manifest):
        self.manifest = manifest; self.p = properties(manifest); self.tiles = []
        for i in range(int(self.p['tile.count'])):
            k = f'tile.{i}.'; path = manifest.parent/self.p[k+'path']
            assert sha(path) == self.p[k+'sha256'], path
            blob = path.read_bytes(); magic,w,h,west,north,dx,dy = struct.unpack('>4sii4d',blob[:44])
            assert magic == b'EGP1' and len(blob)==44+4*w*h
            assert (w,h,west,north,dx,dy)==(int(self.p[k+'width']),int(self.p[k+'height']),float(self.p[k+'west']),float(self.p[k+'north']),float(self.p[k+'stepLon']),float(self.p[k+'stepLat']))
            a = np.frombuffer(blob[44:],dtype='>f4').astype(np.float64).reshape(h,w)
            assert np.all(np.isfinite(a))
            self.tiles.append(dict(path=path,sha256=sha(path),width=w,height=h,west=west,north=north,dx=dx,dy=dy,a=a))
    def record(self):
        return {'worldId':self.p['world.id'],'manifest':str(self.manifest.relative_to(ROOT)),'manifestFileSha256':sha(self.manifest),'javaWorldManifestFingerprint':fingerprint(self.p),'datasetId':self.p['dataset.id'],'nativeResolutionArcseconds':float(self.p['dataset.resolutionArcSeconds']),'verticalDatum':self.p['dataset.datum'],'tileHashes':{str(t['path'].relative_to(ROOT)):t['sha256'] for t in self.tiles}}
    def sample(self, lat, lon):
        lat,lon=np.broadcast_arrays(np.asarray(lat),np.asarray(lon)); out=np.full(lat.shape,np.nan)
        for t in self.tiles:
            sx=(lon-t['west'])/t['dx']; sy=(t['north']-lat)/t['dy']
            mask=(sx>=0)&(sx<=t['width']-1)&(sy>=0)&(sy<=t['height']-1)&np.isnan(out)
            x=sx[mask]; y=sy[mask]; x0=np.floor(x).astype(int); y0=np.floor(y).astype(int)
            x1=np.minimum(x0+1,t['width']-1); y1=np.minimum(y0+1,t['height']-1); dx=x-x0;dy=y-y0;a=t['a']
            out[mask]=(1-dx)*(1-dy)*a[y0,x0]+dx*(1-dy)*a[y0,x1]+(1-dx)*dy*a[y1,x0]+dx*dy*a[y1,x1]
        if np.any(~np.isfinite(out)): raise ValueError('Out of accepted interpolation-safe coverage')
        return out
    def grid(self, pr, x, z, size, spacing=1):
        xx=x+np.arange(0,size,spacing)+0.5;zz=z+np.arange(0,size,spacing)+0.5
        lat,lon=pr.geographic(xx[None,:],zz[:,None]); return self.sample(lat,lon)


def distribution(a):
    p=np.percentile(a,[0,10,25,50,75,90,95,99,100]);return dict(zip(['minimum','p10','p25','median','p75','p90','p95','p99','maximum'],map(float,p)))


def window_y(a):
    low=int(np.floor(a.min()));high=int(np.floor(a.max()))
    origin=16*math.floor((low-16)/16)
    return {'originY':origin,'heightMeters':256,'earthYInclusive':[origin,origin+255],'minimumTerrainBlockY':low,'maximumTerrainBlockY':high,'minimumGroundClearanceFromBottom':low-origin,'minimumAirBlocksAboveSurface':origin+255-high,'allSurfaceBlocksFit':low>=origin and high<=origin+255,'fitsWith16BelowAnd24Above':low-origin>=16 and origin+255-high>=24}


def measure(pack, pr, x, z, size, label, reference=None):
    a=pack.grid(pr,x,z,size); gz,gx=np.gradient(a,1.0,edge_order=1); slope=np.degrees(np.arctan(np.hypot(gx,gz)))
    zz,xx=np.mgrid[:size,:size];xc=xx-(size-1)/2;zc=zz-(size-1)/2
    bx=float(np.sum(xc*(a-a.mean()))/np.sum(xc*xc));bz=float(np.sum(zc*(a-a.mean()))/np.sum(zc*zc));residual=a-(a.mean()+bx*xc+bz*zc)
    imin=np.unravel_index(np.argmin(a),a.shape);imax=np.unravel_index(np.argmax(a),a.shape)
    lat,lon=pr.geographic(x+size/2,z+size/2)
    def loc(ij):
        px=x+int(ij[1])+0.5;pz=z+int(ij[0])+0.5; la,lo=pr.geographic(px,pz)
        return {'blockX':int(math.floor(px)),'blockZ':int(math.floor(pz)),'latitude':la,'longitude':lo,'elevationMeters':float(a[ij])}
    gradient_std=float(np.sqrt(np.var(gx)+np.var(gz)))
    result={'id':label,'projection':pr.record(),'horizontalExtentProjectedMeters':size,'sampling':'bilinear source elevation at each 1 m projected block centre','originBlockX':int(x),'originBlockZ':int(z),'blockXInclusive':[int(x),int(x+size-1)],'blockZInclusive':[int(z),int(z+size-1)],'centerLatitude':lat,'centerLongitude':lon,'heightMeters':distribution(a),'reliefMeters':float(a.max()-a.min()),'slopeDegrees':distribution(slope),'slopePercentOfColumns':{f'atLeast{cut}Degrees':float(np.mean(slope>=cut)*100) for cut in [5,15,30,45,60]},'planarTrendMetersPerProjectedMeter':{'east':bx,'south':bz},'heightStdAfterBestFitPlaneMeters':float(np.std(residual)),'gradientVectorStd':gradient_std,'nativeVerticalWindow':window_y(a),'lowestColumn':loc(imin),'highestColumn':loc(imax),'source':pack.p['dataset.id'],'distinctSurfaceBlockYCount':int(np.unique(np.floor(a)).size)}
    result['footprintBoundary']={'northWest':dict(zip(['latitude','longitude'],pr.geographic(x,z))),'southEast':dict(zip(['latitude','longitude'],pr.geographic(x+size,z+size)))}
    result['approximateSphericalGroundWidthMeters']=size*math.cos(math.radians(lat))/math.cos(math.radians(pr.parallel))
    center_x=x+size//2;center_z=z+size//2;center_height=float(a[size//2,size//2]);center_ground=int(math.floor(center_height))
    result['safeCenterSpawn']={'x':center_x+0.5,'z':center_z+0.5,'latitude':pr.geographic(center_x+0.5,center_z+0.5)[0],'longitude':pr.geographic(center_x+0.5,center_z+0.5)[1],'baseElevationMeters':center_height,'groundBlockY':center_ground,'earthPlayerFeetY':center_ground+2,'enginePlayerFeetY':center_ground+2-result['nativeVerticalWindow']['originY'],'validOnlyWithMeasuredWindow':True}
    if reference:
        b=reference.grid(pr,x,z,size);bgz,bgx=np.gradient(b);bs=np.degrees(np.arctan(np.hypot(bgx,bgz)))
        result['noaaSameColumns']={'source':reference.p['dataset.id'],'reliefMeters':float(b.max()-b.min()),'slopeDegrees':distribution(bs),'heightStdAfterBestFitPlaneMeters':float(np.std(b-(b.mean()+np.sum(xc*(b-b.mean()))/np.sum(xc*xc)*xc+np.sum(zc*(b-b.mean()))/np.sum(zc*zc)*zc))),'heightDifferenceCopernicusMinusNoaaMeters':distribution(a-b)}
    return result


def search(pack,pr,size,reference,count=3):
    t=pack.tiles[0];minx,minz=pr.project(t['north'],t['west']);maxx,maxz=pr.project(t['north']-(t['height']-1)*t['dy'],t['west']+(t['width']-1)*t['dx'])
    x0=16*math.ceil(minx/16);z0=16*math.ceil(minz/16)
    x1=16*math.floor((maxx-size)/16);z1=16*math.floor((maxz-size)/16)
    # Coarse 8 m scan finds promising section-aligned windows, followed by exact 1 m checks.
    xs=np.arange(x0,math.floor(maxx)-1,8)+0.5;zs=np.arange(z0,math.floor(maxz)-1,8)+0.5
    lat,lon=pr.geographic(xs[None,:],zs[:,None]);a=pack.sample(lat,lon);gz,gx=np.gradient(a,8)
    n=size//8
    # Filter anchors are aligned with origin=(row,col), range 0..n-1 coarse samples.
    origin=-(n//2) if n%2==0 else -(n//2)
    kwargs=dict(size=n,origin=origin,mode='constant',cval=np.nan)
    lo=minimum_filter(a,**kwargs);hi=maximum_filter(a,**kwargs)
    # uniform_filter is poisoned by NaNs, but only interior windows are used.
    def avg(v): return uniform_filter(v,size=n,origin=origin,mode='nearest')
    rough=np.maximum(0,avg(gx*gx)+avg(gz*gz)-avg(gx)**2-avg(gz)**2)**0.5
    score=rough*(hi-lo)/200
    candidates=[]
    for z in range(z0,z1+1,16):
        iz=(z-z0)//8
        for x in range(x0,x1+1,16):
            ix=(x-x0)//8
            if hi[iz,ix]-lo[iz,ix] <= 215 and hi[iz,ix]-lo[iz,ix] >= 90:
                candidates.append((float(score[iz,ix]),x,z))
    candidates.sort(reverse=True)
    chosen=[]; checked=0
    for _,x,z in candidates:
        if any(math.hypot(x-r['originBlockX'],z-r['originBlockZ'])<size*0.8 for r in chosen):continue
        result=measure(pack,pr,x,z,size,f'{pr.name}-rugged-{size}-{len(chosen)+1}',reference)
        checked+=1
        if result['nativeVerticalWindow']['fitsWith16BelowAnd24Above']:
            chosen.append(result)
            print('ROUTE',json.dumps({k:result[k] for k in ['id','originBlockX','originBlockZ','centerLatitude','centerLongitude','reliefMeters','slopeDegrees','heightStdAfterBestFitPlaneMeters','nativeVerticalWindow']}),flush=True)
            if len(chosen)==count: break
        if checked>=200:break
    return {'coarseCandidateCount':len(candidates),'exactOneMeterCandidatesMeasured':checked,'scanStepMeters':16,'coarseSampleStepProjectedMeters':8,'selection':'Ranked by gradient-vector variation times relief; require 90..215 m coarse relief, then exact full-window 16 m bottom/24 m top clearances. This is a deterministic promising-route search, not a proof of a global optimum.','routes':chosen}


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--output',type=Path,default=ROOT/'geography/variety-results');args=ap.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    cop=Pack(ROOT/'geography/data/copernicus-everest-proof/world.properties');noaa=Pack(ROOT/'geography/data/noaa-etopo2022-proof/world-global.properties')
    local=Projection('copernicus-local',27.9881,86.925,27.9881);globalp=Projection('global-canonical',0,0,0)
    report={'schema':'earth-terrain-variety-measurement-v1','sourcePacks':[cop.record(),noaa.record()],'method':{'elevation':'Exact EGP1 native float32 values promoted to double, bilinear at block centre x+0.5,z+0.5. No correction, smoothing, downloads or source-pack changes.','slope':'atan(hypot(dh/dx,dh/dz)); central differences between adjacent 1 m projected samples; first-order one-sided boundary differences. Slope belongs to interpolated DEM, not independently measured 1 m terrain.','sourceSpacingMeters':{'copernicusLocal':{'east':local.xfactor/3600,'northSouth':local.zfactor/3600},'globalCanonical':{'east':globalp.xfactor/3600,'northSouth':globalp.zfactor/3600}},'heightQuantization':'Native solid top is floor(interpolated metres), not rounded.','footprint':'X east, Z south; footprint boundaries and every sampled column must remain strictly within accepted source sample-centre bounds.','verticalFit':'256 m section-aligned window; reserve at least 16 m under lowest surface and 24 air blocks over highest surface. Context that does not fit must be treated as an offline geography view.'},'searches':[],'context':[]}
    for pr in [globalp,local]:
        for size in [256,512]:report['searches'].append(search(cop,pr,size,noaa))
        x,z=pr.project(27.9881,86.925)
        for size in [256,512,2048]:
            bx=16*math.floor((x-size/2)/16);bz=16*math.floor((z-size/2)/16)
            report['context'].append(measure(cop,pr,bx,bz,size,f'{pr.name}-summit-region-{size}',noaa))
    # Existing native NOAA proof destination windows, measured under their active global manifest.
    for label,lat,lon,origin in [('baseline-coast',37.7749,-122.4194,-64),('baseline-everest',27.9881,86.925,8352),('baseline-trench',11.3733,142.5917,-10656)]:
        x,z=globalp.project(lat,lon);r=measure(noaa,globalp,16*math.floor((x-128)/16),16*math.floor((z-128)/16),256,label)
        r['baselineConfiguredOriginY']=origin
        r['baselineConfiguredAllSurfaceBlocksFit']=r['nativeVerticalWindow']['minimumTerrainBlockY']>=origin and r['nativeVerticalWindow']['maximumTerrainBlockY']<=origin+255
        report['context'].append(r)
    report['measurementScriptSha256']=sha(Path(__file__))
    report['acceptedCopernicusCoverageSampleCenters']={'west':cop.tiles[0]['west'],'east':cop.tiles[0]['west']+(cop.tiles[0]['width']-1)*cop.tiles[0]['dx'],'north':cop.tiles[0]['north'],'south':cop.tiles[0]['north']-(cop.tiles[0]['height']-1)*cop.tiles[0]['dy']}
    # Deterministic independent-Java verification points include each window's corners,
    # centre, extrema, and 200 seeded random block columns, for both providers.
    rows=[];rng=np.random.default_rng(20221009)
    for c in sum([entry['routes'] for entry in report['searches']],[])+report['context']:
        p=c['projection'];pr=Projection(p['name'],p['originLatitude'],p['originLongitude'],p['standardParallel'])
        n=c['horizontalExtentProjectedMeters'];x=c['originBlockX'];z=c['originBlockZ']
        positions=[(x,z),(x+n-1,z),(x,z+n-1),(x+n-1,z+n-1),(x+n//2,z+n//2)]
        positions += [(v['blockX'],v['blockZ']) for v in (c['lowestColumn'],c['highestColumn'])]
        positions += [(x+int(a),z+int(b)) for a,b in rng.integers(0,n,size=(200,2))]
        for ident,pack in [('noaa',noaa)]+([('cop',cop)] if c['source']==cop.p['dataset.id'] else []):
            for bx,bz in positions:
                lat,lon=pr.geographic(bx+0.5,bz+0.5);h=float(pack.sample(lat,lon))
                rows.append('\t'.join(map(str,[ident,'local' if p['name']=='copernicus-local' else 'global',bx,bz,repr(h)])))
    (args.output/'java-verification-points.tsv').write_text('# source\tprojection\tblockX\tblockZ\tpythonElevationMeters\n'+'\n'.join(rows)+'\n')
    output=args.output/'terrain-variety.json';output.write_text(json.dumps(report,indent=2)+'\n')
    print('WROTE',output)

if __name__=='__main__':main()
