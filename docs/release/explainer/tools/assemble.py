#!/usr/bin/env python3
"""Compose source gameplay and Manim overlays; no game audio is retained.
Usage: python tools/assemble.py --cut short|full [--preview]
Manim media must be rendered first. See ../manim/README.md.
"""
import argparse,json,subprocess,os,textwrap,concurrent.futures,hashlib
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
ROOT=Path(__file__).resolve().parents[4]
EX=ROOT/'docs/release/explainer'
FONT=Path(os.environ.get('EARTH_FONT_DIR','/tmp/manim-sysroot/usr/share/fonts/opentype/inter'))
YELLOW='#FFDD00'; WHITE='#FFFFFF'

def run(args): subprocess.run([str(x) for x in args],check=True)
def probe(p): return json.loads(subprocess.check_output(['ffprobe','-v','error','-show_streams','-show_format','-of','json',str(p)]))
def font(size,bold=False):
    p=FONT/('Inter-Bold.otf' if bold else 'Inter-Regular.otf')
    if not p.exists(): raise FileNotFoundError('Install Inter or set EARTH_FONT_DIR to its OTF directory')
    return ImageFont.truetype(str(p),size)
def lines(draw,txt,f,width):
    result=[]
    for para in txt.split('\n'):
        line=''
        for word in para.split():
            trial=(line+' '+word).strip()
            if draw.textlength(trial,font=f)>width and line: result.append(line);line=word
            else: line=trial
        result.append(line)
    return result

def textplate(seg,cut,path):
    w,h=(1080,1920) if cut=='short' else (1920,1080)
    im=Image.new('RGBA',(w,h));d=ImageDraw.Draw(im)
    diagram=seg['kind']=='diagram'
    x=65 if cut=='short' else 90; maxw=835 if cut=='short' else 1700
    side=cut=='full' and seg.get('portrait_source') and not diagram
    if side:x=850;maxw=960
    title=seg.get('title',''); body=seg.get('body',[])
    if isinstance(body,str):body=[body]
    if not diagram:
        y=170 if cut=='short' else (240 if side else 90)
        d.rounded_rectangle((x-22,y-24,x+maxw+22,y+44),radius=10,fill=(8,8,12,180))
        d.text((x,y),'EARTH PLATFORM  /  '+seg.get('chapter','HOW IT WORKS').upper(),font=font(25 if cut=='short' else 24,True),fill=YELLOW)
        y+=75 if cut=='short' else 60
        f=font(72 if cut=='short' else 70,True)
        ls=lines(d,title,f,maxw)
        for ln in ls:
            d.text((x,y),ln,font=f,fill=WHITE,stroke_width=4,stroke_fill=(8,8,12,240));y+=88
    y=1220 if cut=='short' else (650 if side else 910)
    fs=40 if cut=='short' else 36
    if any('Not an official' in t for t in body): fs=31 if cut=='short' else 32
    f=font(fs)
    ls=[]
    for t in body: ls+=lines(d,t,f,maxw)
    lineh=fs+13; total=len(ls)*lineh
    if y+total>(1510 if cut=='short' else 1030):
        fs=max(26,int(fs*(1510-y if cut=='short' else 1030-y)/total));f=font(fs);lineh=fs+10
        ls=[]
        for t in body: ls+=lines(d,t,f,maxw)
    if ls:
        d.rounded_rectangle((x-22,y-19,x+maxw+22,y+len(ls)*lineh+18),radius=16,fill=(8,8,12,220))
        for ln in ls:d.text((x,y),ln,font=f,fill=WHITE);y+=lineh
    im.save(path)

def assemble_segment(seg,index,cut,work,preview):
    out=work/f'{index:03}.mp4'
    fingerprint=hashlib.sha256((json.dumps(seg,sort_keys=True)+Path(__file__).read_text()).encode()).hexdigest()
    stamp=work/f'{index:03}.sha256'
    if out.exists() and stamp.exists() and stamp.read_text()==fingerprint:return out
    portrait=cut=='short'; W,H=(1080,1920) if portrait else (1920,1080)
    w,h=(W//2,H//2) if preview else (W,H)
    clip=ROOT/seg['clip']; duration=float(seg['duration']); frames=round(duration*30)
    if not clip.exists():raise FileNotFoundError(clip)
    # Only these explicit source windows may be repeated: this prevents an existing
    # trailer's release card or unrelated shots leaking into the new edit.
    start=float(seg.get('source_in',0)); span=seg.get('source_duration')
    if start or span:
        window=work/f'{index:03}-source.mp4'
        command=['ffmpeg','-hide_banner','-loglevel','error','-y','-ss',start,'-i',clip]
        if span:command+=['-t',span]
        run(command+['-map','0:v:0','-an','-c:v','libx264','-crf','17','-preset','fast',window]);clip=window
    p=probe(clip);st=next(x for x in p['streams'] if x['codec_type']=='video');cw,ch=st['width'],st['height']
    plate=work/f'{index:03}.png';textplate(dict(seg,portrait_source=ch>cw),cut,plate)
    # Preserve landscape context; portrait clips in the full cut use a soft, dark backdrop.
    args=['ffmpeg','-hide_banner','-loglevel','error','-y','-stream_loop','-1','-i',clip,'-loop','1','-i',plate]
    rate=float(seg.get('playback_rate',1))
    timing=f'setpts=PTS/{rate},' if rate!=1 else ''
    base=f'[0:v]{timing}fps=30,scale={w}:{h}:force_original_aspect_ratio=increase,crop={w}:{h},setsar=1'
    if not portrait and ch>cw:
        graph=f'color=c=0x0C1830:s={w}x{h}:r=30[bg];[0:v]{timing}fps=30,scale=-2:{h},setsar=1[fg];[bg][fg]overlay={int(150*w/W)}:0[base];'
    else:graph=base+'[base];'
    if seg['kind']=='diagram':
        layout='portrait' if portrait else 'landscape'
        found=list((EX/'manim/media'/layout/'overlay').glob(f'videos/**/{"1920p30" if portrait else "1080p30"}/{seg["scene"]}.mov'))
        if not found:raise FileNotFoundError('Render Manim overlay '+seg['scene']+' '+layout)
        panel=EX/'manim/panel/media/videos/panel_scenes/800p30'/f'{seg["scene"]}.mp4'
        usepanel=not portrait and ch>cw and panel.exists()
        args+=['-i',panel if usepanel else found[0]]
        # Keep all the animation and gently hold its final state; gameplay keeps moving behind it.
        sw,sh=int(w*.85)//2*2,int(h*.85)//2*2
        if usepanel:
            graph+=f'[2:v]fps=30,scale={int(960*w/W)}:{int(800*h/H)},tpad=stop_mode=clone:stop_duration={duration}[diagram];[base][diagram]overlay={int(930*w/W)}:{int(90*h/H)}:shortest=1[composed];'
        else:graph+=f'[base]eq=brightness=-0.12:saturation=0.9[dark];[2:v]fps=30,scale={sw}:{sh},tpad=stop_mode=clone:stop_duration={duration}[diagram];[dark][diagram]overlay=(W-w)/2:0:shortest=1[composed];'
    else: graph+='[base]null[composed];'
    graph+=f'[1:v]scale={w}:{h}[text];[composed][text]overlay=0:0,format=yuv420p[v]'
    args+=['-filter_complex_threads','1','-filter_complex',graph,'-map','[v]','-an','-frames:v',frames,'-c:v','libx264','-preset','veryfast' if preview else 'medium','-crf','25' if preview else '22','-threads','2','-r','30','-pix_fmt','yuv420p',out]
    run(args);stamp.write_text(fingerprint);return out

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--cut',choices=['short','full'],required=True);ap.add_argument('--preview',action='store_true');ap.add_argument('--jobs',type=int,default=3);ap.add_argument('--audio-dir',default='/tmp/earth-explainer-audio');a=ap.parse_args()
    timeline=json.loads((EX/'timeline.json').read_text());segments=timeline[a.cut]
    if isinstance(segments,dict):segments=segments['segments']
    target=54 if a.cut=='short' else 300
    assert abs(sum(float(x['duration']) for x in segments)-target)<.001
    work=Path('/tmp/earth-explainer-edit')/(a.cut+('-preview' if a.preview else '-master'));work.mkdir(parents=True,exist_ok=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=a.jobs) as pool:
        files=list(pool.map(lambda z:assemble_segment(z[1],z[0],a.cut,work,a.preview),enumerate(segments)))
    concat=work/'concat.txt';concat.write_text('\n'.join("file '"+str(f)+"'" for f in files))
    outdir=ROOT/'docs/release/videos/explainer';outdir.mkdir(parents=True,exist_ok=True)
    name='how-it-works-short' if a.cut=='short' else 'how-it-works'
    output=work/(name+'-preview.mp4') if a.preview else outdir/(name+'.mp4')
    args=['ffmpeg','-hide_banner','-loglevel','error','-y','-f','concat','-safe','0','-i',concat,'-i',Path(a.audio_dir)/(a.cut+'.wav'),'-map','0:v','-map','1:a','-c:v','copy','-c:a','aac','-b:a','256k','-ar','48000','-t',target,'-movflags','+faststart',output]
    if a.cut=='short':
        opening=work/'opening.png'
        run(['ffmpeg','-hide_banner','-loglevel','error','-y','-i',files[0],'-frames:v','1',opening])
        args=['ffmpeg','-hide_banner','-loglevel','error','-y','-f','concat','-safe','0','-i',concat,'-i',Path(a.audio_dir)/(a.cut+'.wav'),'-loop','1','-i',opening,
              '-filter_complex',f'[2:v]format=rgba,fade=t=in:st={target-.6}:d=0.5:alpha=1[loop];[0:v][loop]overlay=0:0:shortest=1[v]',
              '-map','[v]','-map','1:a','-c:v','libx264','-crf','22','-preset','medium','-threads','2','-c:a','aac','-b:a','256k','-ar','48000','-t',target,'-movflags','+faststart',output]
    run(args);print(output)
if __name__=='__main__':main()
