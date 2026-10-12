#!/usr/bin/env python3
"""Strictly offline expansion of the pinned retained Copernicus COG block for 030.

No downloader, network option or whole-tile fallback exists. Original proof files
are read and verified; only the new sibling pack is written. Decoders are reused
verbatim from the reviewed offline importer (predictor-3 and Pillow/libtiff).
"""
from pathlib import Path
import argparse, json, struct, math, hashlib, xml.etree.ElementTree as ET, io, zlib
import numpy as np
from PIL import Image, TiffImagePlugin
STEP=1/3600
BLOCK_INDEX=3
EXPECTED_HEADER_SHA="0d76d7f76ce86f91ab7ba9edfc5433e25e897223dd6ca76bc69455a8bc865a4c"
EXPECTED_BLOCK_SHA="3ae196de8c0c4f9e2977815e20ca140d4c8fdfceff5a6985f3b8c473e10484af"
NOTICE="produced using Copernicus WorldDEM-30 © DLR e.V. 2010-2014 and © Airbus Defence and Space GmbH 2014-2018 provided under COPERNICUS by the European Union and ESA; all rights reserved"
DISCLAIMER="The organisations in charge of the Copernicus programme by law or by delegation do not incur any liability for any use of the Copernicus WorldDEM-30"
def require(value,message):
    if not value: raise ValueError(message)
def header_tags(raw: bytes) -> dict:
    if len(raw) != 16384:
        raise ValueError("Unexpected pinned header size")
    image = Image.open(io.BytesIO(raw))
    tags = dict(image.tag_v2)
    expected = {256: 3600, 257: 3600, 258: (32,), 259: 8, 317: 3,
                322: 1024, 323: 1024, 339: (3,), 277: 1}
    if any(tags.get(key) != value for key, value in expected.items()):
        raise ValueError("Unexpected scalar COG encoding or dimensions")
    keys = tags[34735]
    directory = {keys[i]: tuple(keys[i+1:i+4]) for i in range(4, len(keys), 4)}
    if directory.get(1025) != (0, 1, 2) or directory.get(2048) != (0, 1, 4326):
        raise ValueError("Expected original PixelIsPoint WGS84 samples")
    if tags[33922] != (0, 0, 0, 86, 28, 0) or not all(math.isclose(v, STEP, rel_tol=0, abs_tol=1e-16) for v in tags[33550][:2]):
        raise ValueError("Original COG tiepoint or spacing changed")
    return tags


def decode_block(header: bytes, compressed: bytes) -> tuple[np.ndarray, dict]:
    tags = header_tags(header)
    if len(compressed) != tags[325][BLOCK_INDEX]:
        raise ValueError("Wrong compressed source block length")
    # Independent predictor-3 decoder: deflate, cumulative-byte reconstruction,
    # reverse byte-plane shuffle, then interpret big-endian float32.
    decoder = zlib.decompressobj()
    unpacked = decoder.decompress(compressed, 1024 * 1024 * 4 + 1)
    if len(unpacked) != 1024 * 1024 * 4 or not decoder.eof or decoder.unused_data:
        raise ValueError("Unexpected decompressed block size/stream")
    rows = np.frombuffer(unpacked, dtype=np.uint8).reshape(1024, 4096)
    unpredicted = np.cumsum(rows, axis=1, dtype=np.uint8)
    independently_decoded = unpredicted.reshape(1024, 4, 1024).transpose(0, 2, 1).copy().view(">f4").reshape(1024, 1024)
    # Cross-check every sample through Pillow/libtiff using unchanged compressed
    # source bytes in a one-block TIFF wrapper. This catches predictor/endian bugs.
    ifd = TiffImagePlugin.ImageFileDirectory_v2()
    for key in (256, 257, 258, 259, 262, 277, 284, 317, 322, 323, 339, 33550, 33922, 34735, 34736, 34737):
        ifd[key] = tags[key]
    ifd[256], ifd[257], ifd[324], ifd[325] = 1024, 1024, (0,), (len(compressed),)
    ifd[33922] = (0., 0., 0., 86 + 3072 * STEP, 28., 0.)
    body = ifd.tobytes(offset=8)
    ifd[324] = (8 + len(body),)
    updated = ifd.tobytes(offset=8)
    if len(body) != len(updated):
        raise ValueError("Unstable TIFF wrapper offsets")
    wrapper = b"II*\x00" + struct.pack("<I", 8) + updated + compressed
    decoded = np.asarray(Image.open(io.BytesIO(wrapper)), dtype=np.float32)
    if not np.array_equal(decoded, independently_decoded):
        raise ValueError("Independent float-predictor decoder differs from Pillow/libtiff")
    return decoded, tags


def rebuild(S,D):
    sha=lambda x:hashlib.sha256(x).hexdigest()
    lock=json.loads((S/'source-lock.json').read_text())
    verified=[]
    for a in lock['artifacts']:
     b=(S/a['path']).read_bytes(); require(len(b)==a['bytes'] and sha(b)==a['sha256'], 'Original artifact checksum/length mismatch: '+a['path']); verified.append(a)
    header=(S/'source-header.bin').read_bytes(); compressed=(S/'source-block-3.bin').read_bytes()
    require(sha(header)==EXPECTED_HEADER_SHA and sha(compressed)==EXPECTED_BLOCK_SHA,'Original source bytes differ from reviewed source')
    block,tags=decode_block(header,compressed); valid=block[:,:528].copy()
    require(valid.shape==(1024,528) and np.isfinite(valid).all() and (valid>0).all(),'Invalid retained block')
    xml=ET.fromstring((S/'provenance/source-metadata.xml').read_bytes()); require(any(n.attrib.get('codeSpace')=='EPSG::3855' for n in xml.iter()),'Original XML lacks EPSG:3855')
    maxes=[float(n.text) for n in xml.iter() if n.tag.endswith('}maxValue') and n.attrib.get('units')=='meter']; require(len(maxes)==1 and abs(maxes[0]-float(valid.max()))<1e-6,'Original XML maximum mismatch')
    old=(S/'everest.egp').read_bytes(); oldvalues=np.frombuffer(old[44:],dtype='>f4').reshape(180,180); require(np.array_equal(oldvalues,valid[:180,188:368]),'Original narrow crop changed')
    step=1/3600; west=86+3072*step; north=28.0
    payload=struct.pack('>III4d',0x45475031,528,1024,west,north,step,step)+valid.astype('>f4').tobytes()
    D.mkdir(exist_ok=True); (D/'everest-expanded.egp').write_bytes(payload)
    props=(S/'world.properties').read_text().replace('world.id=copernicus-everest-proof','world.id=copernicus-everest-expanded-030').replace('bounded-Everest-window','retained-full-valid-block-528x1024').replace('tile.0.id=everest-glo30','tile.0.id=everest-glo30-retained-block-030').replace('tile.0.path=everest.egp','tile.0.path=everest-expanded.egp').replace('tile.0.sha256='+sha(old),'tile.0.sha256='+sha(payload)).replace('tile.0.west=86.90555555555555','tile.0.west='+str(west)).replace('tile.0.width=180','tile.0.width=528').replace('tile.0.height=180','tile.0.height=1024')
    (D/'world.properties').write_text(props)
    report={'schema':'earth-retained-copernicus-expansion-030-v1','networkRequests':0,'sourceProvenanceDirectory':'../copernicus-everest-proof','sourceLockSha256':sha((S/'source-lock.json').read_bytes()),'verifiedOriginalArtifacts':verified,'twoIndependentDecodersAgreeBitExactly':True,'originalCropBitExactlyPreserved':True,'originalXmlMaximumAgrees':True,'sourcePixelIsPoint':True,'sourceHorizontalCrs':'EPSG:4326','sourceVerticalCrs':'EPSG:3855','verticalScale':1,'verticalOffsetMetres':0,'sourceOriginalColumnsInclusive':[3072,3599],'sourceOriginalRowsInclusive':[0,1023],'sourcePostCount':int(valid.size),'width':528,'height':1024,'sampleCentreBounds':{'west':west,'east':west+527*step,'north':north,'south':north-1023*step},'minSourceMetres':float(valid.min()),'maxSourceMetres':float(valid.max()),'expandedEgpSha256':sha(payload),'expandedEgpBytes':len(payload),'attribution':NOTICE,'disclaimer':DISCLAIMER}
    (D/'import-report.json').write_text(json.dumps(report,indent=2,sort_keys=True)+'\n')
    (D/'README.md').write_text('# Retained Copernicus expansion for 030\n\nStrictly offline expansion of the already retained original source block. No new source download, interpolation, height correction, rescaling or source padding is written into this pack. All 528 x 1024 valid posts are retained, with original 1 arcsecond PixelIsPoint registration and EGM2008 metre heights. The original 180 x 180 proof crop is bit-identical.\n\nOriginal source bytes, XML and license files remain in ../copernicus-everest-proof. import-report.json records all seven verified original artifact hashes and the original source-lock hash. See that directory for source and license URLs.\n\n'+NOTICE+'\n\n'+DISCLAIMER+'\n')
    (D/'SHA256SUMS').write_text(''.join(sha(p.read_bytes())+'  '+p.name+'\n' for p in sorted(D.iterdir()) if p.name!='SHA256SUMS'))
    print(json.dumps(report,indent=2))

if __name__=="__main__":
    root=Path(__file__).resolve().parents[1]
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source",type=Path,default=root/"data/copernicus-everest-proof")
    parser.add_argument("--output",type=Path,default=root/"data/copernicus-everest-expanded-030")
    args=parser.parse_args()
    if args.source.resolve()==args.output.resolve(): parser.error("Original proof must not be overwritten")
    rebuild(args.source,args.output)
