#!/usr/bin/env python3
"""Deterministic resource pack build/check. Python standard library only."""
from pathlib import Path
import argparse
import hashlib
import io
import json
import math
import re
import struct
from urllib.parse import urlparse
import uuid
import zipfile
import zlib
from model_geometry import geometry_signature, model_bounds, rotation_angles

ROOT=Path(__file__).resolve().parent
PACK=ROOT/'pack'
DEFAULT_OUTPUT=ROOT.parents[1]/'.runtime/noqui-cosmetics-integration/pack'
CATALOG=ROOT/'lobby-cosmetics.json'
PROVENANCE=ROOT/'cosmetics-provenance.json'
PRESERVED=ROOT/'preserved-assets.json'
NATIVE=ROOT/'native-equipment.json'
GADGETS={'triple_boots':'boots','quad_boots':'boots','hammer':'hand','snow_bomb':'hand','gravity_suit':'suit'}
EVENTS=('0click2','0select2','0miss','0win','0heal')
PACK_UUID='7d5243d5-c30e-4bdd-a039-71ec1ad10001'
ORIGIN='https://noqui.danta.qzz.io/packs/'

def digest(data,algorithm='sha256'):
    return hashlib.new(algorithm,data).hexdigest()

def read_json(path):
    return json.loads(path.read_text(encoding='utf-8'))

def catalog():
    data=read_json(CATALOG)
    assert set(data)=={'schemaVersion','cosmetics'} and data['schemaVersion']==1, 'Invalid cosmetics schema'
    assert isinstance(data['cosmetics'],list) and data['cosmetics'], 'Empty cosmetics catalog'
    seen=set()
    for row in data['cosmetics']:
        assert set(row)=={'id','label','slot'}, 'Catalog entries contain only id, label, slot; prices belong to Java'
        name=row['id']
        assert isinstance(name,str) and re.fullmatch('[a-z_]+',name) and name not in seen and name not in GADGETS, ('Invalid/duplicate cosmetic id',name)
        assert isinstance(row['label'],str) and row['label'].strip()==row['label'] and row['label'], name
        assert row['slot'] in {'head','face','back'}, (name,'Invalid cosmetic slot')
        seen.add(name)
    return data['cosmetics']

def item_ids():
    return tuple(row['id'] for row in catalog())+tuple(GADGETS)

def safe_path(root,relative):
    assert isinstance(relative,str) and relative and '\\' not in relative, ('Invalid relative path',relative)
    path=root/relative
    assert not path.is_absolute() or path.resolve().is_relative_to(root.resolve()), ('Path escapes root',relative)
    assert not relative.startswith('/') and ':' not in relative and '..' not in Path(relative).parts, relative
    return path

def https_url(value):
    assert isinstance(value,str), 'Missing source URL'
    parsed=urlparse(value)
    assert parsed.scheme=='https' and parsed.netloc and not parsed.username and not parsed.password, value

def texture_path(model,reference):
    seen=set()
    while reference.startswith('#'):
        assert reference not in seen, ('Texture alias cycle',reference)
        seen.add(reference)
        assert reference[1:] in model['textures'], ('Unresolved texture',reference)
        reference=model['textures'][reference[1:]]
    assert re.fullmatch(r'noqui:[a-z0-9_/.-]+',reference) and '..' not in reference, reference
    namespace,relative=reference.split(':',1)
    return f'assets/{namespace}/textures/{relative}.png'

def validate_provenance(rows):
    data=read_json(PROVENANCE)
    assert data['schemaVersion']==1
    sources={source['id']:source for source in data['sources']}
    assert len(sources)==len(data['sources']), 'Duplicate provenance source id'
    expected={'COSMETICS_SOURCES.json'}
    assert (PACK/'COSMETICS_SOURCES.json').read_bytes()==PROVENANCE.read_bytes(), 'Pack source notices differ from canonical provenance'
    notices=(PACK/'ASSET_NOTICES.txt').read_text(encoding='utf-8')
    for source in sources.values():
        for field in ('title','author','license','modifications'):
            assert isinstance(source.get(field),str) and source[field].strip(), (source['id'],field)
        https_url(source['sourceUrl']);https_url(source['licenseUrl'])
        local=safe_path(ROOT,source['licenseFile']);packed=safe_path(PACK,source['packLicenseFile'])
        assert source['licenseFile'].startswith('vendor/') and source['packLicenseFile'].startswith('licenses/'), source['id']
        assert len(local.read_bytes())>20 and packed.read_bytes()==local.read_bytes(), ('License bytes differ',source['id'])
        assert source['sourceUrl'] in notices and source['author'] in notices and source['license'] in notices, ('Missing attribution',source['id'])
        expected.add(source['packLicenseFile'])
        assert source['sourceFiles'], ('Missing pinned source evidence',source['id'])
        assert any(record['path']==source['licenseFile'] for record in source['sourceFiles']), ('License evidence must be hash-pinned',source['id'])
        for record in source['sourceFiles']:
            path=safe_path(ROOT,record['path'])
            assert record['path'].startswith('vendor/') and digest(path.read_bytes())==record['sha256'], ('Source hash differs',record['path'])
    records={row['id']:row for row in data['cosmetics']}
    assert len(records)==len(data['cosmetics']), 'Duplicate provenance cosmetic id'
    preserved=read_json(PRESERVED)
    legacy={row['id']:row for row in preserved['cosmetics']}
    current={row['id']:row for row in rows}
    assert all(current.get(name)==row for name,row in legacy.items()), 'Existing cosmetic identity/label/slot changed'
    assert set(records)==set(current)-set(legacy), 'Every added cosmetic needs provenance'
    used=set()
    for name,record in records.items():
        assert record['sourceIds'] and set(record['sourceIds'])<=set(sources), ('Unknown source',name)
        used.update(record['sourceIds'])
        assert record['geometry'] in {'derived','original-adaptation'}, (name,'Geometry provenance is required')
        if record['geometry']=='original-adaptation':
            assert record.get('adaptationApproval'), (name,'Original adaptations need parent approval')
        assert record['modifications'].strip(), (name,'Describe the actual conversion/adaptation')
        path=PACK/f'assets/noqui/models/{name}.json'
        assert digest(path.read_bytes())==record['modelSha256'], ('Model differs from provenance',name)
        model=read_json(path)
        actual={texture_path(model,value) for value in model['textures'].values()}
        assert set(record['texturePaths'])==actual, ('Texture provenance differs',name)
    assert used==set(sources), 'Unused source license/material in pack'
    return expected

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
    assert 0<w<=512 and 0<h<=512,path
    assert depth in (1,2,4,8,16) and color in (0,2,3,4,6) and interlace==0,path
    channels={0:1,2:3,3:1,4:2,6:4}[color]
    stride=(w*channels*depth+7)//8
    raw=zlib.decompress(compressed)
    assert len(raw)==h*(stride+1) and all(raw[y*(stride+1)]<=4 for y in range(h)), ('Invalid PNG scanlines',path)
    if not pixels:return w,h
    assert (depth,color,interlace)==(8,6,0), 'Skin must be noninterlaced RGBA8'
    stride=w*4;rows=[];previous=[0]*stride
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

def validate_native(rows):
    native=read_json(NATIVE)
    assert native['schemaVersion']==1
    assert native['upstream']['sourceSha256']==digest((ROOT/'vendor/objcubed/objcubed.js').read_bytes())=='a52545b6c2e3a389859b5db32a3f83710164d8aaade12ea7f6ce8aed5856f58c'
    for relative,hash_value in native['files'].items():
        assert relative=='assets/noqui/items/wearable_head.json' or relative=='licenses/objcubed.txt' or relative.startswith(('assets/noqui/equipment/','assets/noqui/textures/entity/equipment/humanoid/','assets/minecraft/shaders/')),relative
        assert digest(safe_path(PACK,relative).read_bytes())==hash_value,('Native equipment changed',relative)
        if relative.endswith('.png'):png(PACK/relative)
        if relative.startswith('assets/minecraft/shaders/'):
            assert (PACK/relative).read_bytes()==(ROOT/'vendor/objcubed/shaders'/relative.removeprefix('assets/minecraft/shaders/')).read_bytes()
    assert (PACK/'licenses/objcubed.txt').read_bytes()==(ROOT/'vendor/objcubed/LICENSE').read_bytes()
    head=read_json(PACK/'assets/noqui/items/wearable_head.json')['model']
    assert head['type']=='minecraft:composite' and len(head['models'])==2
    for index,slot in enumerate(('head','face')):
        selected=head['models'][index]
        assert selected['type']=='minecraft:select' and selected['property']=='minecraft:custom_model_data' and selected['index']==index
        assert selected['fallback']=={'type':'minecraft:empty'}
        assert {entry['when']:entry['model'] for entry in selected['cases']}=={r['id']:{'type':'minecraft:model','model':'noqui:'+r['id']} for r in rows if r['slot']==slot}
    backs={r['id'] for r in rows if r['slot']=='back'}
    records={r['id']:r for r in native['backs']};assert set(records)==backs,'Native back catalogue differs'
    expected_assets={'triple_boots','quad_boots','gravity_suit'}|{prefix+'/'+name for name in backs for prefix in ('wearable_back','wearable_back_gravity')}
    actual_assets={p.relative_to(PACK/'assets/noqui/equipment').with_suffix('').as_posix() for p in (PACK/'assets/noqui/equipment').rglob('*.json')}
    assert actual_assets==expected_assets,'Native armor asset set differs'
    gravity=read_json(PACK/'assets/noqui/equipment/gravity_suit.json')['layers']['humanoid']
    for name in expected_assets:
        layers=read_json(PACK/f'assets/noqui/equipment/{name}.json')['layers']['humanoid'];assert layers,name
        for layer in layers:
            assert re.fullmatch(r'noqui:[a-z0-9_/]+',layer['texture']),name
            path='assets/noqui/textures/entity/equipment/humanoid/'+layer['texture'].split(':')[1]+'.png'
            assert path in native['files'] and png(PACK/path),path
    for name,record in records.items():
        model=read_json(PACK/f'assets/noqui/models/{name}.json')
        assert record['sourceModelSha256']==digest((PACK/f'assets/noqui/models/{name}.json').read_bytes()),name
        count=sum(len(e['faces']) for e in model['elements'])
        layers=read_json(PACK/f'assets/noqui/equipment/wearable_back/{name}.json')['layers']['humanoid']
        combined=read_json(PACK/f'assets/noqui/equipment/wearable_back_gravity/{name}.json')['layers']['humanoid']
        assert count==record['faces'] and len(layers)==math.ceil(count/4) and combined==gravity+layers,name
    return set(native['files'])

def validate(original=None):
    rows=catalog();ids=tuple(row['id'] for row in rows)+tuple(GADGETS)
    expected={'pack.mcmeta','MIT_BACKPACK_LICENSE.txt','SKIN_SOURCE_NOTICE.txt','ASSET_NOTICES.txt','assets/minecraft/sounds.json',
              'assets/noqui/textures/item/palette.png','assets/noqui/textures/item/backpack.png','assets/noqui/textures/entity/shopkeeper.png'}
    expected.update(validate_provenance(rows))
    expected.update(validate_native(rows))
    expected.update(f'assets/noqui/items/{name}.json' for name in ids)
    expected.update(f'assets/noqui/models/{name}.json' for name in ids)
    expected.update(f'assets/minecraft/sounds/arsf/{name}.ogg' for name in EVENTS)
    assert not any(p.is_symlink() for p in PACK.rglob('*')), 'No symlinks in pack'
    meta=json.loads((PACK/'pack.mcmeta').read_text(encoding='utf-8'))
    assert meta['pack']['min_format']==meta['pack']['max_format']==[97,1]
    assert not {'supported_formats','pack_format'}&set(meta['pack']), 'Use exact modern 97.1 format bounds'
    total_elements=0;geometry={};model_checks=[];textures=set()
    cosmetic_ids={row['id'] for row in rows}
    for name in ids:
        definition=json.loads((PACK/f'assets/noqui/items/{name}.json').read_text(encoding='utf-8'))
        assert definition=={'model':{'type':'minecraft:model','model':f'noqui:{name}'}}
        model=json.loads((PACK/f'assets/noqui/models/{name}.json').read_text(encoding='utf-8'))
        fixed=model['display']['fixed']
        assert set(fixed)=={'rotation','translation','scale'}, (name,'Explicit fixed transform required')
        for key,values in fixed.items():
            assert len(values)==3 and all(isinstance(v,(int,float)) and not isinstance(v,bool) and math.isfinite(v) for v in values), (name,'Invalid fixed transform')
            if key=='scale':assert all(0<v<=4 for v in values), (name,'Invalid fixed scale')
        assert 'parent' not in model and 1<=len(model['elements'])<=256, (name,'Native cuboid geometry required')
        total_elements+=len(model['elements'])
        for reference in model['textures'].values():
            relative=texture_path(model,reference)
            assert png(PACK/relative)
            expected.add(relative);textures.add(relative)
        solid_elements=0
        for e in model['elements']:
            assert len(e['from'])==len(e['to'])==3 and all(isinstance(v,(int,float)) and not isinstance(v,bool) and math.isfinite(v) for v in e['from']+e['to']), (name,'Non-finite bounds')
            assert all(-16<=min(a,b)<=max(a,b)<=32 for a,b in zip(e['from'],e['to'])),(name,e)
            solid_elements+=all(a!=b for a,b in zip(e['from'],e['to']))
            assert e['faces'] and set(e['faces'])<={'north','south','east','west','up','down'},(name,e)
            if 'rotation' in e:
                rotation=e['rotation'];angles=rotation_angles(rotation)
                assert len(rotation.get('origin',[]))==3 and all(isinstance(v,(int,float)) and math.isfinite(v) for v in rotation['origin']+angles), (name,'Invalid native rotation')
            for f in e['faces'].values():
                assert f['texture'].startswith('#') and texture_path(model,f['texture']) in textures
                assert len(f['uv'])==4 and all(isinstance(v,(int,float)) and not isinstance(v,bool) and math.isfinite(v) and 0<=v<=16 for v in f['uv']), (name,'UV bounds')
                assert f['uv'][0]!=f['uv'][2] and f['uv'][1]!=f['uv'][3], (name,'Zero-area texture UV')
                assert f.get('rotation',0) in (0,90,180,270)
        low,high=model_bounds(model,fixed=True);extents=[b-a for a,b in zip(low,high)]
        assert solid_elements, (name,'A 3D cosmetic must contain nonzero-volume cuboids')
        assert all(v>=.5 for v in extents) and max(extents)<=28, (name,'Geometry must have real depth',extents)
        if name in cosmetic_ids:
            signature=geometry_signature(model)
            assert signature not in geometry, ('Duplicate cosmetic geometry/recolor',name,geometry.get(signature))
            geometry[signature]=name
        model_checks.append({'id':name,'elements':len(model['elements']),'solidElements':solid_elements,'extents':[round(v,5) for v in extents]})
    actual={p.relative_to(PACK).as_posix() for p in PACK.rglob('*') if p.is_file()}
    assert actual==expected, {'unexpected':sorted(actual-expected),'missing':sorted(expected-actual)}
    w,h,skin=png(PACK/'assets/noqui/textures/entity/shopkeeper.png',True)
    assert (w,h)==(64,64)
    # Every visible base face in the slim skin is opaque; unused4th arm columns stay clear.
    for x,y,width,height in [(0,8,32,8),(8,0,16,8),(16,20,24,12),(20,16,16,4),
                             (40,20,14,12),(44,16,6,4),(32,52,14,12),(36,48,6,4),
                             (0,20,16,12),(4,16,8,4),(16,52,16,12),(20,48,8,4)]:
        assert all(skin[v][u*4+3]==255 for v in range(y,y+height) for u in range(x,x+width)),(x,y)
    assert all(skin[y][54*4+3]==0 for y in range(20,32)), 'Right arm must be3pixels wide'
    assert all(skin[y][46*4+3]==0 for y in range(52,64)), 'Left arm must be3pixels wide'
    source=ROOT/'vendor/munasharna/purple-hair-girl-in-tuxedo.png'
    skin_provenance=json.loads((ROOT/'vendor/munasharna/provenance.json').read_text(encoding='utf-8'))
    assert digest(source.read_bytes())==skin_provenance['sha256'], 'Downloaded skin source changed'
    assert (PACK/'assets/noqui/textures/entity/shopkeeper.png').read_bytes()==source.read_bytes(), 'Shop skin must match downloaded asset'
    assert (PACK/'SKIN_SOURCE_NOTICE.txt').read_bytes()==(ROOT/'vendor/munasharna/NOTICE.txt').read_bytes()
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
    for record in read_json(PRESERVED)['files']:
        current=safe_path(PACK,record['path'])
        if current.name in {'pirate_hat.json','bunny.json','cap.json','crown.json','glasses.json'} and '/models/' in record['path']:
            original=ROOT/'vendor/native-head-baseline'/current.name
            assert digest(original.read_bytes())==record['sha256'],record['path']
            changed=read_json(current);native_head=changed['display'].pop('head',None)
            assert native_head and changed==read_json(original),('Head fit changed original geometry/UVs',record['path'])
        else:assert digest(current.read_bytes())==record['sha256'], ('Preserved baseline asset changed',record['path'])
    texture_hashes={}
    for relative in sorted(textures):
        hash_value=digest((PACK/relative).read_bytes())
        assert hash_value not in texture_hashes, ('Duplicate texture content; share one resource',relative,texture_hashes.get(hash_value))
        texture_hashes[hash_value]=relative
    return {'cosmetics':len(rows),'gadgets':len(GADGETS),'models':len(ids),'elements':total_elements,'textures':len(textures)+1,'sounds':len(EVENTS),
            'files':len(actual),'uniqueCosmeticGeometry':len(geometry),'geometry':model_checks,'slimSkinUV':'64x64 opaque base faces,3pixel arms'}

def archive_bytes():
    stream=io.BytesIO()
    # PNG/OGG are already compressed; STORED avoids host zlib-version hash drift.
    with zipfile.ZipFile(stream,'w',compression=zipfile.ZIP_STORED) as z:
        for path in sorted((p for p in PACK.rglob('*') if p.is_file()),key=lambda p:p.relative_to(PACK).as_posix()):
            name=path.relative_to(PACK).as_posix()
            info=zipfile.ZipInfo(name,(1980,1,1,0,0,0));info.compress_type=zipfile.ZIP_STORED
            info.create_system=3;info.external_attr=0o100644<<16
            z.writestr(info,path.read_bytes(),compress_type=zipfile.ZIP_STORED)
    data=stream.getvalue();assert len(data)<8_000_000, 'Keep this lobby-only pack below 8 MB; exclude unused source assets'
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
                  'descriptorSource':'noqui/lobby-assets/lobby-pack.properties','classpathResource':'/lobby-pack.properties',
                  'catalogSource':'noqui/lobby-assets/lobby-cosmetics.json','catalogSha256':digest(CATALOG.read_bytes()),'checks':checks,
                  'files':[{'path':x.relative_to(PACK).as_posix(),'bytes':x.stat().st_size,'sha256':digest(x.read_bytes())} for x in sorted(PACK.rglob('*'),key=lambda p:p.relative_to(PACK).as_posix()) if x.is_file()]}
        (args.output/'pack-manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    summary={key:value for key,value in checks.items() if key!='geometry'}
    print(json.dumps({'checkOnly':args.check,'sha1':sha1,'sha256':sha256,'bytes':len(data),'descriptor':str(target),'output':str(args.output),**summary},ensure_ascii=False))

if __name__=='__main__':main()
