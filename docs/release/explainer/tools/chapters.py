#!/usr/bin/env python3
"""Add chapter metadata without re-encoding the full video."""
from pathlib import Path
import subprocess,tempfile
ROOT=Path(__file__).resolve().parents[4]
p=ROOT/'docs/release/videos/explainer/how-it-works.mp4'
chapters=[(0,12,'How Earth Platform works'),(12,78,'Real data and generated detail'),(78,126,'Room for true height'),(126,186,'Distant voxel terrain'),(186,222,'Servers and caches'),(222,270,'Terra++, Tellus, Distant Horizons and Voxy'),(270,300,'Honest limits and coming soon')]
with tempfile.TemporaryDirectory(prefix='earth-chapters-') as d:
    meta=Path(d)/'chapters.txt';meta.write_text(';FFMETADATA1\n'+''.join(f'[CHAPTER]\nTIMEBASE=1/1000\nSTART={a*1000}\nEND={b*1000}\ntitle={t}\n' for a,b,t in chapters))
    out=p.with_suffix('.chapters.mp4')
    subprocess.run(['ffmpeg','-v','error','-y','-i',str(p),'-i',str(meta),'-map','0','-map_metadata','1','-map_chapters','1','-c','copy','-movflags','+faststart',str(out)],check=True)
    out.replace(p)
