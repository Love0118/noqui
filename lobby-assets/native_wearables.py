"""Author native head transforms and portable back fits from preserved models.

Python standard library only. Existing model geometry and gadget textures are preserved.
"""
from pathlib import Path
import hashlib, json, shutil
from model_geometry import model_bounds, back_translation

ROOT=Path(__file__).resolve().parent
PACK=ROOT/'pack'
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
def manifest(rows):
 backs=[]
 for row in rows:
  if row['slot']!='back':continue
  path=PACK/f'assets/noqui/models/{row["id"]}.json'
  backs.append({'id':row['id'],'sourceModelSha256':sha(path),'translation':back_translation(read(path))})
 generated=[p for p in PACK.rglob('*') if p.is_file() and ('/equipment/' in p.as_posix() or p.name=='wearable_head.json')]
 write(ROOT/'native-equipment.json',{'schemaVersion':2,'backs':backs,
       'files':{p.relative_to(PACK).as_posix():sha(p) for p in sorted(generated)}})

def main():
 rows=read(ROOT/'lobby-cosmetics.json')['cosmetics'];heads(rows);manifest(rows)
 print('Native head/face equipment, vanilla gadget armor and portable back attachments authored.')
if __name__=='__main__':main()
