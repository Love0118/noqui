#!/usr/bin/env python3
"""Deterministic resource pack build/check. Python standard library only."""
from pathlib import Path
import argparse
import hashlib
import io
import json
import struct
import uuid
import zipfile
import zlib

ROOT=Path(__file__).resolve().parent
PACK=ROOT/'pack'
DEFAULT_OUTPUT=ROOT.parents[1]/'.runtime/noqui-lobby-pack'
IDS=('pirate_hat','backpack','glasses','crown','cap','bunny','triple_boots','quad_boots','hammer','snow_bomb','gravity_suit')
EVENTS=('0click2','0select2','0miss','0win','0heal')
PACK_UUID='7d5243d5-c30e-4bdd-a039-71ec1ad10001'
ORIGIN='https://noqui.danta.qzz.io/packs/'

def digest(data,algorithm='sha256'):
    return hashlib.new(algorithm,data).hexdigest()

def png(path, pixels=False):
    data=path.read_bytes();assert data[:8]==b'\x89PNG\r\n\x1a\n',path
    pos=8;compressed=b'';header=None
    while pos<len(data):
        length=struct.unpack('>I',data[pos:pos+4])[0];kind=data[pos+4:pos+8];chunk=data[pos+8:pos+8+length]
        assert zlib.crc32(kind+chunk)&0xffffffff==struct.unpack('>I',data[pos+8+length:pos+12+length])[0],path
        if kind==b'IHDR':header=struct.unpack('>IIBBBBB',chunk)
        if kind==b'IDAT':compressed+=chunk
        pos+=length+12
        if kind==b'IEND':break
    assert pos==len(data) and header,path
    w,h,depth,color,_,_,interlace=header
    assert 0<w<=256 and 0<h<=256,path
    if not pixels:return w,h
    assert (depth,color,interlace)==(8,6,0), 'Skin must be noninterlaced RGBA8'
    raw=zlib.decompress(compressed);stride=w*4;rows=[];previous=[0]*stride
    for y in range(h):
        filter_type=raw[y*(stride+1)];row=list(raw[y*(stride+1)+1:(y+1)*(stride+1)])
        for x in range(stride):
            a=row[x-4] if x>=4 else 0;b=previous[x];c=previous[x-4] if x>=4 else 0
            if filter_type==0:p=0
            elif filter_type==1:p=a
            elif filter_type==2:p=b
            elif filter_type==3:p=(a+b)//2
            elif filter_type==4:
                p0=a+b-c;values=[abs(p0-a),abs(p0-b),abs(p0-c)];p=(a,b,c)[values.index(min(values))]
            else:raise AssertionError('Invalid PNG filter')
            row[x]=(row[x]+p)%256
        rows.append(row);previous=row
    return w,h,rows

def validate(original=None):
    expected={'pack.mcmeta','MIT_BACKPACK_LICENSE.txt','ASSET_NOTICES.txt','assets/minecraft/sounds.json',
              'assets/noqui/textures/item/palette.png','assets/noqui/textures/item/backpack.png','assets/noqui/textures/entity/shopkeeper.png'}
    expected.update(f'assets/noqui/items/{name}.json' for name in IDS)
    expected.update(f'assets/noqui/models/{name}.json' for name in IDS)
    expected.update(f'assets/minecraft/sounds/arsf/{name}.ogg' for name in EVENTS)
    actual={p.relative_to(PACK).as_posix() for p in PACK.rglob('*') if p.is_file()}
    assert actual==expected, {'unexpected':sorted(actual-expected),'missing':sorted(expected-actual)}
    assert not any(p.is_symlink() for p in PACK.rglob('*')), 'No symlinks in pack'
    meta=json.loads((PACK/'pack.mcmeta').read_text(encoding='utf-8'))
    assert meta['pack']['min_format']==meta['pack']['max_format']==[97,1]
    assert 'supported_formats' not in meta['pack']
    total_elements=0
    for name in IDS:
        definition=json.loads((PACK/f'assets/noqui/items/{name}.json').read_text(encoding='utf-8'))
        assert definition=={'model':{'type':'minecraft:model','model':f'noqui:{name}'}}
        model=json.loads((PACK/f'assets/noqui/models/{name}.json').read_text(encoding='utf-8'))
        assert model['display']['fixed']=={'rotation':[0,0,0],'translation':[0,0,0],'scale':[1,1,1]}
        assert 5<=len(model['elements'])<=96, name
        total_elements+=len(model['elements'])
        for reference in model['textures'].values():
            namespace,rel=reference.split(':',1)
            assert namespace=='noqui' and png(PACK/f'assets/{namespace}/textures/{rel}.png')
        for e in model['elements']:
            assert all(-16<=a<b<=32 for a,b in zip(e['from'],e['to'])),(name,e)
            assert set(e['faces'])=={'north','south','east','west','up','down'},(name,e)
            assert 'rotation' not in e, 'Right-angle source rotations must be baked'
            for f in e['faces'].values():
                assert f['texture']=='#0' and len(f['uv'])==4 and all(0<=v<=16 for v in f['uv'])
                assert f.get('rotation',0) in (0,90,180,270)
        extents=[max(e['to'][i] for e in model['elements'])-min(e['from'][i] for e in model['elements']) for i in range(3)]
        assert all(v>0 for v in extents) and max(extents)<=28, (name,extents)
    w,h,skin=png(PACK/'assets/noqui/textures/entity/shopkeeper.png',True)
    assert (w,h)==(64,64)
    # Every visible base face in the slim skin is opaque; unused4th arm columns stay clear.
    for x,y,width,height in [(0,8,32,8),(8,0,16,8),(16,20,24,12),(20,16,16,4),
                             (40,20,14,12),(44,16,6,4),(32,52,14,12),(36,48,6,4),
                             (0,20,16,12),(4,16,8,4),(16,52,16,12),(20,48,8,4)]:
        assert all(skin[v][u*4+3]==255 for v in range(y,y+height) for u in range(x,x+width)),(x,y)
    assert all(skin[y][54*4+3]==0 for y in range(20,32)), 'Right arm must be3pixels wide'
    assert all(skin[y][46*4+3]==0 for y in range(52,64)), 'Left arm must be3pixels wide'
    definitions=json.loads((PACK/'assets/minecraft/sounds.json').read_text(encoding='utf-8'))
    assert set(definitions)==set(EVENTS)
    provenance=json.loads((ROOT/'sound-provenance.json').read_text(encoding='utf-8'))
    assert {r['event'] for r in provenance['sounds']}=={f'minecraft:{k}' for k in EVENTS}
    for record in provenance['sounds']:
        key=record['event'].split(':')[1];data=(PACK/f'assets/minecraft/sounds/arsf/{key}.ogg').read_bytes()
        assert data[:4]==b'OggS' and len(data)==record['bytes'] and digest(data)==record['sha256']
        references=[v if isinstance(v,str) else v['name'] for v in definitions[key]['sounds']]
        assert references==[f'arsf/{key}'],(key,references)
    if original:
        assert digest(original.read_bytes())==provenance['archiveSha256']
        with zipfile.ZipFile(original) as z:
            for record in provenance['sounds']:
                assert digest(z.read(record['member']))==record['sha256']
    assert (PACK/'MIT_BACKPACK_LICENSE.txt').read_bytes()==(ROOT/'vendor/jashduck/LICENSE').read_bytes()
    assert 'Copyright (c) 2024 JashDuck' in (PACK/'MIT_BACKPACK_LICENSE.txt').read_text(encoding='utf-8')
    return {'models':len(IDS),'elements':total_elements,'textures':3,'sounds':len(EVENTS),'files':len(actual),'slimSkinUV':'64x64 opaque base faces,3pixel arms'}

def archive_bytes():
    stream=io.BytesIO()
    # PNG/OGG are already compressed; STORED avoids host zlib-version hash drift.
    with zipfile.ZipFile(stream,'w',compression=zipfile.ZIP_STORED) as z:
        for path in sorted((p for p in PACK.rglob('*') if p.is_file()),key=lambda p:p.relative_to(PACK).as_posix()):
            name=path.relative_to(PACK).as_posix()
            info=zipfile.ZipInfo(name,(1980,1,1,0,0,0));info.compress_type=zipfile.ZIP_STORED
            info.create_system=3;info.external_attr=0o100644<<16
            z.writestr(info,path.read_bytes(),compress_type=zipfile.ZIP_STORED)
    data=stream.getvalue();assert len(data)<1_000_000, 'Keep this lobby-only pack small'
    return data

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--check',action='store_true');p.add_argument('--output',type=Path,default=DEFAULT_OUTPUT);p.add_argument('--original-zip',type=Path);args=p.parse_args()
    checks=validate(args.original_zip);data=archive_bytes();assert data==archive_bytes(),'Nondeterministic build'
    sha256=digest(data);sha1=digest(data,'sha1');url=ORIGIN+sha256+'.zip'
    descriptor=f'url={url}\nsha1={sha1}\n'
    target=ROOT/'lobby-pack.properties'
    if args.check:
        assert target.read_text(encoding='utf-8')==descriptor, 'Descriptor differs: run build_pack.py, review and commit updated descriptor'
    else:
        target.write_text(descriptor,encoding='utf-8',newline='\n')
        args.output.mkdir(parents=True,exist_ok=True)
        (args.output/(sha256+'.zip')).write_bytes(data)
        manifest={'packUuid':str(uuid.UUID(PACK_UUID)),'resourceFormat':[97,1],'url':url,'sha1':sha1,'sha256':sha256,'bytes':len(data),
                  'descriptorSource':'noqui/lobby-assets/lobby-pack.properties','classpathResource':'/lobby-pack.properties','checks':checks,
                  'files':[{'path':x.relative_to(PACK).as_posix(),'bytes':x.stat().st_size,'sha256':digest(x.read_bytes())} for x in sorted(PACK.rglob('*'),key=lambda p:p.relative_to(PACK).as_posix()) if x.is_file()]}
        (args.output/'pack-manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'checkOnly':args.check,'sha1':sha1,'sha256':sha256,'bytes':len(data),'descriptor':str(target),'output':str(args.output),**checks},ensure_ascii=False))

if __name__=='__main__':main()
