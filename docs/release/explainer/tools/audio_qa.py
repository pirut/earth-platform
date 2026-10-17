#!/usr/bin/env python3
"""Measure WAV and AAC round-trip loudness, peaks, channel layout and loop seam.
Usage: python3 audio_qa.py PATH.wav [--duration 54] [--loop]
AAC files are temporary QA media, not distribution masters.
"""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile
import numpy as np
from scipy.io import wavfile
from audio import SR, meter


def inspect(path, expected_duration=None, looping=False):
    path = Path(path)
    sr, data = wavfile.read(path)
    if np.issubdtype(data.dtype, np.integer):
        data = data.astype(np.float64)/(2**(np.iinfo(data.dtype).bits-1))
    duration = len(data)/sr
    measured = meter(path)
    with tempfile.TemporaryDirectory(prefix='earth-audio-qa-') as temp:
        aac = Path(temp)/'encoded.m4a'
        subprocess.run(['ffmpeg','-v','error','-y','-i',str(path),'-c:a','aac',
                        '-b:a','256k','-ar',str(SR),str(aac)],check=True)
        encoded = meter(aac)
    seam = float(np.max(np.abs(data[0]-data[-1])))
    adjacent = np.abs(np.diff(data,axis=0))
    normal_step = float(np.quantile(adjacent, .999))
    report = {'sample_rate':sr, 'channels':data.shape[1], 'duration_seconds':duration,
              'sample_peak_dbfs':float(20*np.log10(np.max(np.abs(data))+1e-12)),
              'clipped_samples':int(np.sum(np.abs(data)>=1)),
              'integrated_lufs':float(measured['input_i']), 'true_peak_dbtp':float(measured['input_tp']),
              'aac_integrated_lufs':float(encoded['input_i']), 'aac_true_peak_dbtp':float(encoded['input_tp']),
              'boundary_sample_delta':seam, 'adjacent_delta_99_9_percentile':normal_step,
              'loop_boundary_pass':bool(seam < max(.002,normal_step)) if looping else None,
              'ending_sample_peak':float(np.max(np.abs(data[-480:])))}
    assert sr==SR and data.shape[1]==2, report
    assert expected_duration is None or abs(duration-expected_duration)<=1/SR, report
    assert report['clipped_samples']==0, report
    assert abs(report['integrated_lufs']+14)<.4, report
    assert report['aac_true_peak_dbtp'] <= -1.0, report
    if looping: assert report['loop_boundary_pass'], report
    return report


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('path',type=Path)
    parser.add_argument('--duration',type=float)
    parser.add_argument('--loop',action='store_true')
    args=parser.parse_args()
    print(json.dumps(inspect(args.path,args.duration,args.loop),indent=2))
