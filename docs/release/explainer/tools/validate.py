#!/usr/bin/env python3
"""Read-only final MP4 validation and timeline contact sheets.

Run from any directory:
  python docs/release/explainer/tools/validate.py --cut both --out /tmp/explainer-final-qa
  python docs/release/explainer/tools/validate.py --cut short --video /path/short.mp4

Dependencies: ffmpeg, ffprobe, Pillow, numpy, scipy (shared audio measurement).
Writes <cut>-report.json and <cut>-contact.jpg, never alters the input MP4.
Nonzero exit means a failed delivery requirement. Contact sheets still require
human review for text legibility, source accuracy, safe areas and unwanted cards.
"""
import argparse
from datetime import datetime, timezone
from fractions import Fraction
import hashlib
import io
import json
import math
from pathlib import Path
import subprocess
from PIL import Image, ImageDraw, ImageFont
from audio import meter

ROOT = Path(__file__).resolve().parents[4]
EX = ROOT/'docs/release/explainer'


def probe(path):
    return json.loads(subprocess.check_output([
        'ffprobe','-v','error','-show_streams','-show_format','-of','json',str(path)]))


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as handle:
        for block in iter(lambda: handle.read(1024*1024), b''):
            digest.update(block)
    return digest.hexdigest()


def contact(path, cut, segments, output, max_frames):
    indices = list(range(len(segments)))
    if len(indices)>max_frames:
        indices = sorted(set(round(i*(len(indices)-1)/(max_frames-1)) for i in range(max_frames)))
    width, height = (270,480) if cut=='short' else (480,270)
    columns = 3 if cut=='short' else 4
    label_height, margin = 55, 12
    rows = math.ceil(len(indices)/columns)
    sheet = Image.new('RGB',(columns*(width+margin)+margin,rows*(height+label_height+margin)+margin),(18,18,24))
    draw = ImageDraw.Draw(sheet)
    font = ImageFont.load_default(size=15)
    samples = []
    cursor = 0
    starts = []
    for seg in segments:
        starts.append(float(seg.get('start',cursor)))
        cursor += float(seg['duration'])
    for cell, index in enumerate(indices):
        seg = segments[index]
        stamp = starts[index]+float(seg['duration'])/2
        raw = subprocess.check_output(['ffmpeg','-v','error','-ss',str(stamp),'-i',str(path),
            '-frames:v','1','-vf',f'scale={width}:{height}:force_original_aspect_ratio=decrease,pad={width}:{height}:(ow-iw)/2:(oh-ih)/2',
            '-f','image2pipe','-vcodec','png','-'])
        frame = Image.open(io.BytesIO(raw)).convert('RGB')
        x, y = margin+(cell%columns)*(width+margin),margin+(cell//columns)*(height+label_height+margin)
        sheet.paste(frame,(x,y))
        label = f'{index+1:02d} | {stamp:.2f}s | {seg.get("chapter",seg.get("kind",""))}'
        while draw.textlength(label,font=font)>width: label=label[:-2]+'…'
        draw.text((x,y+height+5),label,fill='white',font=font)
        title = seg.get('title','').replace('\n',' ')
        short_title=title
        while draw.textlength(short_title,font=font)>width: short_title=short_title[:-2]+'…'
        draw.text((x,y+height+27),short_title,fill='#FFDD00',font=font)
        samples.append({'segment_index':index,'time_seconds':stamp,'title':title})
    sheet.save(output,quality=92)
    return samples


def validate(path, cut, out, timeline, max_frames):
    target = 54 if cut=='short' else 300
    expected_size = (1080,1920) if cut=='short' else (1920,1080)
    report = {'input':str(path.resolve()),'cut':cut,'checked_at_utc':datetime.now(timezone.utc).isoformat(),
              'checks':{},'errors':[], 'human_review_required':['Text legibility and safe areas',
              'Factual claims and source alignment','No unrelated release or end cards','Natural video/audio loop for Short']}
    report_path = out/f'{cut}-report.json'
    try:
        info = probe(path)
        video = next(s for s in info['streams'] if s['codec_type']=='video')
        audio = next(s for s in info['streams'] if s['codec_type']=='audio')
        duration = float(info['format']['duration'])
        size = path.stat().st_size
        measurements = meter(path)  # Same loudnorm measurement as audio.py/audio_qa.py; meters FINAL AAC.
        loudness, peak = float(measurements['input_i']),float(measurements['input_tp'])
        vf = float(Fraction(video['avg_frame_rate']))
        nominal_fps = float(Fraction(video['r_frame_rate']))
        report.update({'sha256':sha256(path),'bytes':size,'decimal_megabytes':size/1e6,
                       'duration_seconds':duration,'dimensions':[video['width'],video['height']],
                       'fps':vf,'nominal_fps':nominal_fps,'video_codec':video['codec_name'],
                       'audio_codec':audio['codec_name'],'audio_sample_rate':audio.get('sample_rate'),
                       'audio_channels':audio.get('channels'),'integrated_lufs':loudness,
                       'true_peak_dbtp':peak,'audio_measurement':measurements})
        report['checks'] = {
            'dimensions':(video['width'],video['height'])==expected_size,
            'fps_30':abs(vf-30)<.0001 and abs(nominal_fps-30)<.0001,
            'duration':abs(duration-target)<=1/30+.001,
            'video_duration':abs(float(video.get('duration',duration))-target)<=1/30+.001,
            'audio_duration':abs(float(audio.get('duration',duration))-target)<=1/30+.001,
            'h264':video['codec_name']=='h264','aac':audio['codec_name']=='aac',
            'under_100_mb':size<100_000_000,'loudness':abs(loudness+14)<=.5,
            'true_peak':peak<=-1.0,'stereo_48khz':audio.get('channels')==2 and int(audio.get('sample_rate',0))==48000,
            'yuv420p':video.get('pix_fmt')=='yuv420p',
            'stream_start_alignment':abs(float(video.get('start_time',0))-float(audio.get('start_time',0)))<=1/30}
        segments = timeline[cut]
        if isinstance(segments,dict):segments=segments['segments']
        image = out/f'{cut}-contact.jpg'
        report['contact_sheet_samples'] = contact(path,cut,segments,image,max_frames)
        report['contact_sheet'] = str(image.resolve())
        # Decode all streams, detecting corrupt packets/truncated media instead of relying on metadata alone.
        decoded = subprocess.run(['ffmpeg','-v','error','-xerror','-i',str(path),'-f','null','-'],capture_output=True,text=True)
        report['checks']['complete_decode'] = decoded.returncode==0
        if decoded.returncode:report['errors'].append(decoded.stderr[-4000:])
    except Exception as exc:
        report['errors'].append(f'{type(exc).__name__}: {exc}')
    report['passed'] = bool(report['checks']) and all(report['checks'].values()) and not report['errors']
    report['failed_checks'] = [key for key,value in report['checks'].items() if not value]
    report_path.write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({'cut':cut,'passed':report['passed'],'failed_checks':report['failed_checks'],
                      'errors':report['errors'],'report':str(report_path)}),flush=True)
    return report['passed']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cut',choices=['short','full','both'],default='both')
    parser.add_argument('--video',type=Path,help='Override one input; requires --cut short or full')
    parser.add_argument('--out',type=Path,default=Path('/tmp/explainer-final-qa'))
    parser.add_argument('--timeline',type=Path,default=EX/'timeline.json')
    parser.add_argument('--max-frames',type=int,default=36,help='Maximum timeline midpoints in each contact sheet')
    args=parser.parse_args()
    if args.video and args.cut=='both':parser.error('--video requires --cut short or full')
    if args.max_frames<2:parser.error('--max-frames must be at least 2')
    args.out.mkdir(parents=True,exist_ok=True)
    timeline=json.loads(args.timeline.read_text())
    cuts=['short','full'] if args.cut=='both' else [args.cut]
    results=[]
    for cut in cuts:
        name='how-it-works-short.mp4' if cut=='short' else 'how-it-works.mp4'
        path=args.video or ROOT/'docs/release/videos/explainer'/name
        results.append(validate(path,cut,args.out,timeline,args.max_frames))
    raise SystemExit(0 if all(results) else 1)


if __name__=='__main__':main()
