#!/usr/bin/env python3
"""Create compact 1080p repository copies without altering high-quality masters.

Usage:
  python docs/release/explainer/tools/encode_delivery.py \
    --input-dir /path/to/high-quality-masters --output-dir /tmp/earth-github-delivery

Inputs/outputs: how-it-works-short.mp4 and how-it-works.mp4.
Exact original delivery settings: two-pass libx264 fast; Short 2370 kbps video,
full 475 kbps video; both AAC 128 kbps, 48 kHz, original dimensions/30 fps.
No scaling or loudness normalization. Requires ffmpeg and ffprobe. Each output
must be below 24,000,000 bytes. Output paths may not equal either master path.
"""
import argparse
import json
from pathlib import Path
import subprocess

SPECS = {'short':('how-it-works-short.mp4',2370,1080,1920,54),
         'full':('how-it-works.mp4',475,1920,1080,300)}


def run(command):
    return subprocess.run([str(item) for item in command],check=True)


def probe(path):
    return json.loads(subprocess.check_output(['ffprobe','-v','error','-show_streams',
        '-show_format','-of','json',str(path)]))


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input-dir',type=Path,required=True)
    parser.add_argument('--output-dir',type=Path,required=True)
    parser.add_argument('--cut',choices=['short','full','both'],default='both')
    args=parser.parse_args()
    inputs={name:(args.input_dir/spec[0]).resolve() for name,spec in SPECS.items()}
    args.output_dir.mkdir(parents=True,exist_ok=True)
    for cut,(name,bitrate,width,height,seconds) in SPECS.items():
        if args.cut not in ('both',cut):continue
        source=inputs[cut]
        output=(args.output_dir/name).resolve()
        if output in inputs.values():raise ValueError('Output must never overwrite a master input')
        info=probe(source)
        v=next(s for s in info['streams'] if s['codec_type']=='video')
        if (v['width'],v['height'])!=(width,height):raise ValueError(f'Wrong source resolution: {source}')
        if v['avg_frame_rate']!='30/1':raise ValueError(f'Expected 30 fps: {source}')
        if abs(float(info['format']['duration'])-seconds)>.04:raise ValueError(f'Wrong duration: {source}')
        passlog=args.output_dir/f'{cut}-pass'
        video=['-c:v','libx264','-preset','fast','-b:v',f'{bitrate}k']
        common=['ffmpeg','-v','error','-y','-i',source,'-map','0:v:0']
        run(common+['-an']+video+['-pass','1','-passlogfile',passlog,'-f','null','/dev/null'])
        run(common+['-map','0:a:0']+video+['-pass','2','-passlogfile',passlog,
            '-c:a','aac','-b:a','128k','-ar','48000','-movflags','+faststart',output])
        actual=probe(output)
        outv=next(s for s in actual['streams'] if s['codec_type']=='video')
        report={'source':str(source),'output':str(output),'video_bitrate_kbps':bitrate,
                'audio_bitrate_kbps':128,'preset':'fast','passes':2,'bytes':output.stat().st_size,
                'dimensions':[outv['width'],outv['height']], 'duration':float(actual['format']['duration']),
                'fps':outv['avg_frame_rate'],'normalization_applied':False}
        report['passed']=report['bytes']<24_000_000 and report['dimensions']==[width,height] and abs(report['duration']-seconds)<.04
        (args.output_dir/f'{cut}-encode-report.json').write_text(json.dumps(report,indent=2)+'\n')
        print(json.dumps(report),flush=True)
        if not report['passed']:raise RuntimeError('Compact delivery failed requirements; inspect report')


if __name__=='__main__':main()
