"""Author native head transforms, vanilla gadget armor and body-bone mesh assets.

Optional authoring dependencies: Pillow, and npm ci in this directory. Pack checking
and normal builds remain standard-library-only. Existing model geometry is preserved.
"""
from pathlib import Path
import hashlib, json, math, shutil, subprocess, sys
from PIL import Image, ImageDraw
from model_geometry import rotated_point, rotate, model_bounds

ROOT=Path(__file__).resolve().parent
PACK=ROOT/'pack'
REPO=ROOT.parents[1]
NORMALIZED=ROOT/'vendor/native-back-inputs'
OUT=REPO/'.runtime/noqui-native-authoring'
TABLE={'north':[2,5,7,4],'east':[1,2,4,3],'south':[6,1,3,8],'west':[5,6,8,7],'up':[5,2,1,6],'down':[8,3,4,7]}
def read(p):return json.loads(p.read_text(encoding='utf-8'))
def write(p,value):
 p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def heads(rows):
 selectors=[]
 for index,slot in enumerate(('head','face')):
  cases=[]
  for row in rows:
   if row['slot']!=slot:continue
   path=PACK/f'assets/noqui/models/{row["id"]}.json';model=read(path);fixed=model['display']['fixed']
   # Native HEAD anchor is four model pixels above the neck. Hats rest at the
   # eight-pixel crown; glasses sit at eye height, just in front of the face.
   baseline=4-max(0,model_bounds(model,fixed=True)[0][1]) if slot=='head' else 1.75
   front=0 if slot=='head' else 4.25
   x,y,z=fixed['translation'];rx,ry,rz=fixed['rotation']
   assert rx==rz==0,'Review non-Y fixed rotation before converting native head fit'
   model['display']['head']={'rotation':[0,(ry+180)%360,0],'translation':[-x*1.6,(y+baseline)*1.6,-(z+front)*1.6],
                            'scale':[v*1.6 for v in fixed['scale']]}
   write(path,model);cases.append({'when':row['id'],'model':{'type':'minecraft:model','model':'noqui:'+row['id']}})
  selectors.append({'type':'minecraft:select','property':'minecraft:custom_model_data','index':index,'cases':cases,'fallback':{'type':'minecraft:empty'}})
 write(PACK/'assets/noqui/items/wearable_head.json',{'model':{'type':'minecraft:composite','models':selectors}})
 provenance=read(ROOT/'cosmetics-provenance.json')
 for row in provenance['cosmetics']:row['modelSha256']=sha(PACK/f'assets/noqui/models/{row["id"]}.json')
 write(ROOT/'cosmetics-provenance.json',provenance);shutil.copyfile(ROOT/'cosmetics-provenance.json',PACK/'COSMETICS_SOURCES.json')
def gadgets():
 for name,base,accent in [('triple_boots','#19343d','#75e6ee'),('quad_boots','#332646','#cc96ff'),('gravity_suit','#242a2b','#a8e8bc')]:
  image=Image.new('RGBA',(64,32));draw=ImageDraw.Draw(image)
  if name.endswith('boots'):
   draw.rectangle((0,26,15,31),fill=base);draw.rectangle((8,16,11,19),fill=base)
   draw.rectangle((0,26,15,27),fill=accent);draw.rectangle((0,30,15,31),fill=accent)
   if name=='quad_boots':draw.rectangle((0,28,15,28),fill='#eee4ff')
  else:
   draw.rectangle((16,16,39,31),fill=base);draw.rectangle((40,16,55,31),fill=base)
   draw.rectangle((20,20,21,31),fill=accent);draw.rectangle((26,20,27,31),fill=accent)
   draw.rectangle((16,29,39,30),fill=accent);draw.rectangle((40,20,55,21),fill=accent)
  dest=PACK/f'assets/noqui/textures/entity/equipment/humanoid/{name}.png';dest.parent.mkdir(parents=True,exist_ok=True);image.save(dest)
  write(PACK/f'assets/noqui/equipment/{name}.json',{'layers':{'humanoid':[{'texture':'noqui:'+name}]}})
def body_inputs(rows):
 entries=[]
 for row in rows:
  if row['slot']!='back':continue
  name=row['id'];model=read(NORMALIZED/name/'model.json');fixed=model['display']['fixed'];lines=[];count=0;all_points=[];faces=0
  for i,e in enumerate(model['elements']):
   x,y,z=e['from'];X,Y,Z=e['to'];cube=[[X,Y,Z],[X,Y,z],[X,y,Z],[X,y,z],[x,Y,z],[x,Y,Z],[x,y,z],[x,y,Z]]
   points=[]
   for p in cube:
    p=rotated_point(e,p);p=rotate([(p[j]-8)*fixed['scale'][j]*.8 for j in range(3)],fixed['rotation'])
    points.append([v/16 for v in p])
   all_points.extend(points);lines.append(f'o ocp1e0i{i}')
   for p in points:lines.append('v '+' '.join(f'{v:.9f}' for v in p))
   for direction,f in e['faces'].items():
    u,v,U,V=f['uv'];corners=[[u,v],[U,v],[U,V],[u,V]]
    for _ in range(f.get('rotation',0)//90):corners.insert(0,corners.pop())
    # Repeating positions per quad makes independent index/UV correspondence explicit.
    # The eight cube positions above remain the vertex table for this element.
    uvbase=faces*4
    for a,b in corners:lines.append(f'vt {a/16:.9f} {1-b/16:.9f}')
    lines.append('f '+' '.join(f'{count+TABLE[direction][k]}/{uvbase+k+1}' for k in (3,2,1,0)));faces+=1
   count+=8
  low=[min(p[i] for p in all_points) for i in range(3)];high=[max(p[i] for p in all_points) for i in range(3)]
  height=high[1]-low[1];depth=high[2]-low[2]
  # Invert the actual native north carrier basis after the upstream bbox recenter.
  offset=[.00390625,(.8125-.375)*.9235-.785-height/2,(.20+.1875)*.9235-.175+depth/2]
  obj=OUT/(name+'.obj');obj.write_text('\n'.join(lines)+'\n',encoding='utf-8')
  entries.append({'id':name,'obj':str(obj),'atlas':str(NORMALIZED/name/'atlas.png'),'faces':faces,'offset':offset,'sourceModelSha256':sha(PACK/f'assets/noqui/models/{name}.json')})
 return entries
def main():
 rows=read(ROOT/'lobby-cosmetics.json')['cosmetics'];OUT.mkdir(parents=True,exist_ok=True);heads(rows);gadgets()
 entries=body_inputs(rows);write(OUT/'request.json',{'pack':str(PACK),'entries':entries})
 subprocess.run(['node',str(ROOT/'export_native_backs.mjs'),str(OUT/'request.json')],check=True)
 shader_root=ROOT/'vendor/objcubed/shaders'
 shutil.copytree(shader_root,PACK/'assets/minecraft/shaders',dirs_exist_ok=True)
 shutil.copyfile(ROOT/'vendor/objcubed/LICENSE',PACK/'licenses/objcubed.txt')
 notice=PACK/'ASSET_NOTICES.txt';text=notice.read_text(encoding='utf-8')
 if 'JagerMeistars/obj-cubed' not in text:notice.write_text(text+'\nNative equipment renderer: https://github.com/JagerMeistars/obj-cubed\nMIT Copyright (c) 2022 Godlander. Pinned 266880bb7030dae4ab51178df9ee9f8ff91b395f.\nSee licenses/objcubed.txt. Native-body PNG layers generated from the unchanged cosmetic meshes.\n',encoding='utf-8',newline='\n')
 generated=[p for p in PACK.rglob('*') if p.is_file() and ('/shaders/' in p.as_posix() or '/equipment/' in p.as_posix() or p.name=='wearable_head.json' or p.name=='objcubed.txt')]
 write(ROOT/'native-equipment.json',{'schemaVersion':1,'upstream':{'url':'https://github.com/JagerMeistars/obj-cubed','commit':'266880bb7030dae4ab51178df9ee9f8ff91b395f','license':'MIT','sourceSha256':sha(ROOT/'vendor/objcubed/objcubed.js')},
       'backs':[{k:v for k,v in e.items() if k not in ('obj','atlas')} for e in entries],
       'files':{p.relative_to(PACK).as_posix():sha(p) for p in sorted(generated)}})
 print('Native equipment authored: 59 head/face products, 21 back products, 3 gadgets.')
if __name__=='__main__':main()
