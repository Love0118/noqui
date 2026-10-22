"""Optional authoring tool: regenerate committed native models/PNG sources (Pillow).

Normal pack builds/checks do not import this module or require Pillow/network.
New geometry/pixel art is authored for this project; backpack is a pinned MIT conversion.
"""
from pathlib import Path
from PIL import Image, ImageDraw
import base64
import hashlib
import itertools
import json
import math
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent
PACK = ROOT / 'pack'
COMMIT = '959204795f2242be80186a040f46b81fbf5ffa10'
REPO = f'https://raw.githubusercontent.com/joshua-pechan/Minecraft-Figura-Cosmetic/{COMMIT}/'
IDS = ['pirate_hat', 'backpack', 'glasses', 'crown', 'cap', 'bunny', 'triple_boots', 'quad_boots', 'hammer', 'snow_bomb', 'gravity_suit']
SOUNDS = ['0click2', '0select2', '0miss', '0win', '0heal']
PALETTE = ['#202937', '#374459', '#66503c', '#a87d50', '#e4b95d', '#fff1c0', '#c05560', '#e3ecf0', '#96cadd', '#4e93ab', '#73599a', '#b89cdb', '#243338', '#53c8be', '#e7a9b4', '#f7d9dd']

def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')

def box(a, b, color, name='', faces=None):
    u, v = (color % 4) * 4, (color // 4) * 4
    return {'name': name, 'from': a, 'to': b, 'faces': faces or {face: {'texture': '#0', 'uv': [u, v, u+4, v+4]} for face in ['north','south','east','west','up','down']}}

def model(name, elements, texture='palette'):
    # FIXED transforms are explicit; elements are centered at8,8,8 (16 units/block).
    shifted = []
    for e in elements:
        e = dict(e)
        e['from'] = [round(x+8, 5) for x in e['from']]
        e['to'] = [round(x+8, 5) for x in e['to']]
        shifted.append(e)
    write_json(PACK / f'assets/noqui/models/{name}.json', {
        'textures': {'0': f'noqui:item/{texture}', 'particle': f'noqui:item/{texture}'},
        'display': {'fixed': {'rotation':[0,0,0], 'translation':[0,0,0], 'scale':[1,1,1]},
                    'gui': {'rotation':[20,145,0], 'translation':[0,0,0], 'scale':[.85,.85,.85]}},
        'elements': shifted,
    })
    write_json(PACK / f'assets/noqui/items/{name}.json', {'model': {'type': 'minecraft:model', 'model': f'noqui:{name}'}})

def palette():
    im = Image.new('RGBA', (64,64))
    d = ImageDraw.Draw(im)
    for i, color in enumerate(PALETTE):
        x, y = i%4*16, i//4*16
        d.rectangle((x,y,x+15,y+15), fill=color)
        # Subtle original pixel weave, not a flat missing-texture placeholder.
        rgb = tuple(int(color[k:k+2],16) for k in (1,3,5))
        for u in range(1,16,4):
            for v in range(1,16,4):
                d.point((x+u,y+v), fill=tuple(min(255,n+7) for n in rgb)+(255,))
    p=PACK/'assets/noqui/textures/item/palette.png'; p.parent.mkdir(parents=True,exist_ok=True);im.save(p)

def ring(y0,y1,x=4.5,z=4.5,color=0):
    return [box([-x,y0,-z],[x,y1,-z+.65],color),box([-x,y0,z-.65],[x,y1,z],color),
            box([-x,y0,-z],[-x+.65,y1,z],color),box([x-.65,y0,-z],[x,y1,z],color)]

def native_models():
    e=[box([-6,-1,-4.5],[6,0,4.5],0,'wide tricorn brim'),box([-4,0,-3],[4,3,3],0,'hat crown'),box([-4.1,.1,-3.1],[4.1,.8,3.1],4,'gold ribbon'),box([-5,0,3.6],[5,1.3,4.5],0,'front turned brim'),box([-6,0,-3],[-5,1.4,3],0),box([5,0,-3],[6,1.4,3],0)]
    e += [box([-.8,1.25,3.05],[.8,2.6,3.35],7,'skull'),box([-.45,.8,3.05],[.45,1.35,3.35],7),box([-.6,1.8,3.35],[-.2,2.15,3.4],0),box([.2,1.8,3.35],[.6,2.15,3.4],0),box([-5.2,1,-1],[-4.7,5.6,-.5],7,'feather shaft'),box([-5.6,2.4,-1.1],[-4.3,4.8,-.45],6,'feather')]
    model('pirate_hat',e)
    e=ring(-1,.3,4.4,4.2,4)
    for x in [-3.5,0,3.5]:
        e += [box([x-.55,.3,3.5],[x+.55,2.6,4.3],4,'crown prong'),box([x-.35,2.6,3.55],[x+.35,3.7,4.2],5)]
    for x in [-4.4,3.7]:
        for z in [-3,0]:e.append(box([x,.3,z-.5],[x+.7,2.3,z+.5],4))
    e += [box([-.6,-.7,4.2],[.6,.6,4.5],6,'ruby'),box([-3.8,-.6,4.2],[-3.1,.3,4.5],13),box([3.1,-.6,4.2],[3.8,.3,4.5],13)]
    model('crown',e)
    model('cap',[box([-4.3,-.7,-4],[4.3,.4,4],0,'cap rim'),box([-4,.4,-3.8],[4,2.5,3.5],1),box([-3,2.5,-3],[3,3.6,2.7],1),box([-3.8,-.7,3.3],[3.8,-.1,7],0,'visor'),box([-2.8,-.7,7],[2.8,-.1,7.8],0),box([-.6,3.6,-.4],[.6,4,.4],4),box([-1,.8,3.5],[1,2.1,3.7],4,'badge')])
    e=ring(-1,.1,4.3,4,7)
    for x in [-2.8,1.3]:
        e += [box([x,0,-.7],[x+1.5,8,1],7,'rabbit ear'),box([x+.3,8,-.5],[x+1.2,9.3,.8],7),box([x+.35,1,.99],[x+1.15,7.6,1.15],14,'pink ear inner')]
    model('bunny',e)
    e=[]
    for x in [-3.8,.5]:
        e += [box([x,-1.2,-.25],[x+3.3,-.75,.3],0),box([x,.8,-.25],[x+3.3,1.25,.3],0),box([x,-.75,-.25],[x+.4,.8,.3],0),box([x+2.9,-.75,-.25],[x+3.3,.8,.3],0)]
    e += [box([-.5,.1,-.2],[.5,.5,.25],4,'bridge'),box([-4.2,-.1,-4],[-3.7,.4,.1],0,'temple'),box([3.7,-.1,-4],[4.2,.4,.1],0)]
    model('glasses',e)
    for name,count,color in [('triple_boots',3,9),('quad_boots',4,10)]:
        e=[]
        for x in [-3.9,.3]:
            e += [box([x,-1.7,-2.1],[x+3.6,-1,3.5],0,'sole'),box([x,-1,-1.8],[x+3.6,1.5,2.4],color,'boot'),box([x+.2,1.5,-1.5],[x+3.4,2.6,1.8],1,'cuff')]
            for n in range(count):
                y=-.6+n*.55;e.append(box([x+.7,y,2.35],[x+2.9,y+.2,2.6],13 if count==3 else 11,'jump tier stripe'))
            e += [box([x+.5,-1.9,-2.7],[x+1.4,-.5,-2],4,'thruster'),box([x+2.2,-1.9,-2.7],[x+3.1,-.5,-2],4)]
        model(name,e)
    model('hammer',[box([4.5,-6,-.6],[5.5,4,.6],3,'wood handle'),box([4.4,-5.6,-.7],[5.6,-4,.7],0,'grip'),box([1.2,3,-1.8],[8.8,6.4,1.8],1,'steel head'),box([.6,3.3,-2],[1.5,6.1,2],4,'left brass cap'),box([8.5,3.3,-2],[9.4,6.1,2],4),box([3.5,3,-1.9],[6.5,6.5,1.9],0,'central band')])
    e=[box([1.5,-2,-2],[6.5,2,2],8,'ice bomb core'),box([2,-2.5,-1.5],[6,2.5,1.5],7),box([2.5,-3,-1],[5.5,3,1],8),box([2,-1.4,-2.5],[6,1.4,2.5],7),box([3.4,3,-.5],[4.4,4,.5],0,'fuse cap'),box([3.7,4,-.2],[4.2,5,.3],3,'fuse'),box([4,4.7,-.4],[4.7,5.4,.4],4,'spark')]
    model('snow_bomb',e)
    e=ring(-5.8,-4.7,4.7,2.7,0)
    for x in [-4.7,3.4]:
        e += [box([x,-4.7,2],[x+1.3,3.2,2.8],1,'front frame'),box([x,-4.7,-2.8],[x+1.3,3.2,-2],1),box([x,3.2,-2.5],[x+1.3,4.7,2.8],10,'shoulder rail')]
    e += [box([-3.5,1.5,2.2],[3.5,3.8,3],0,'chest plate'),box([-1.3,1.2,3],[1.3,4,3.7],10,'gravity core'),box([-.7,1.8,3.7],[.7,3.4,4],13,'core glow'),box([-6.7,.5,-2.5],[-4.7,4.2,2.5],10,'left shoulder'),box([4.7,.5,-2.5],[6.7,4.2,2.5],10),box([-2.8,-4.3,-3.8],[2.8,3.6,-2.4],0,'power pack')]
    for y in [-3,-1,1]:e.append(box([-2,y,-4],[2,y+.35,-3.8],13,'back coil'))
    model('gravity_suit',e)

NORMALS={'east':(1,0,0),'west':(-1,0,0),'up':(0,1,0),'down':(0,-1,0),'south':(0,0,1),'north':(0,0,-1)}
def face_vertices(a,b,face):
    x,y,z=a;X,Y,Z=b
    return {'south':[(x,Y,Z),(X,Y,Z),(X,y,Z),(x,y,Z)],'north':[(X,Y,z),(x,Y,z),(x,y,z),(X,y,z)],
            'east':[(X,Y,Z),(X,Y,z),(X,y,z),(X,y,Z)],'west':[(x,Y,z),(x,Y,Z),(x,y,Z),(x,y,z)],
            'up':[(x,Y,z),(X,Y,z),(X,Y,Z),(x,Y,Z)],'down':[(x,y,Z),(X,y,Z),(X,y,z),(x,y,z)]}[face]

def rotate(v,angles):
    x,y,z=v
    for axis,angle in enumerate(angles):
        c,s=round(math.cos(math.radians(angle))),round(math.sin(math.radians(angle)))
        if axis==0:y,z=y*c-z*s,y*s+z*c
        elif axis==1:x,z=x*c+z*s,-x*s+z*c
        else:x,y=x*c-y*s,x*s+y*c
    return [x,y,z]

def backpack():
    vendor=ROOT/'vendor/jashduck';vendor.mkdir(parents=True,exist_ok=True)
    for filename in ['backpack.bbmodel','LICENSE','README.md','backpack.png']:
        target=vendor/filename
        if not target.exists():target.write_bytes(urllib.request.urlopen(REPO+filename,timeout=30).read())
    data=json.loads((vendor/'backpack.bbmodel').read_text())
    tex=data['textures'][0].get('source')
    texture=base64.b64decode(tex.split(',',1)[1]) if tex else (vendor/'backpack.png').read_bytes()
    p=PACK/'assets/noqui/textures/item/backpack.png';p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(texture)
    elements=[]
    for item in data['elements']:
        angles=item.get('rotation',[0,0,0]);pivot=item.get('origin',[0,0,0])
        assert all(a%90==0 for a in angles), 'Only lossless quarter turns are baked'
        corners=[rotate([v[i]-pivot[i] for i in range(3)],angles) for v in itertools.product(*zip(item['from'],item['to']))]
        a=[min(v[i]+pivot[i] for v in corners) for i in range(3)];b=[max(v[i]+pivot[i] for v in corners) for i in range(3)]
        # Recenter the original back-mounted body on the runtime's back anchor.
        raw_a,raw_b=a,b
        a=[a[0],a[1]-17.25,a[2]+4];b=[b[0],b[1]-17.25,b[2]+4]
        faces={}
        for face,value in item['faces'].items():
            if value.get('texture') is None:continue
            normal=rotate(NORMALS[face],angles)
            mapped=next(k for k,n in NORMALS.items() if list(n)==normal)
            uv=[round(n/data['resolution']['width']*16,5) for n in value['uv']]
            transformed=[]
            for vertex in face_vertices(item['from'],item['to'],face):
                rotated=rotate([vertex[i]-pivot[i] for i in range(3)],angles)
                transformed.append([round(rotated[i]+pivot[i],5) for i in range(3)])
            target=list(face_vertices(raw_a,raw_b,mapped)[0])
            quarter=transformed.index(target)
            faces[mapped]={'texture':'#0','uv':uv,'rotation':(value.get('rotation',0)+quarter*90)%360}
        elements.append(box(a,b,0,item.get('name','backpack'),faces))
    model('backpack',elements,'backpack')

def skin():
    im=Image.new('RGBA',(64,64));d=ImageDraw.Draw(im)
    skin='#e9baa0';hair='#45322d';jacket='#293345';trousers='#303b50';shoe='#171e2a';white='#f3eee1';gold='#dcb65b'
    def limb(x,y,w,h,depth,color):
        # Standard unfolded cube/slim player UV: top,bottom,right,front,left,back.
        for rect in [(x+depth,y,x+depth+w-1,y+depth-1),(x+depth+w,y,x+depth+w*2-1,y+depth-1),
                     (x,y+depth,x+depth-1,y+depth+h-1),(x+depth,y+depth,x+depth+w-1,y+depth+h-1),
                     (x+depth+w,y+depth,x+depth+w+depth-1,y+depth+h-1),(x+depth+w+depth,y+depth,x+2*depth+2*w-1,y+depth+h-1)]:d.rectangle(rect,fill=color)
    limb(0,0,8,8,8,skin);d.rectangle((8,0,23,7),fill=hair);d.rectangle((24,8,31,15),fill=hair)
    for x in [0,16]:d.rectangle((x,8,x+7,15),fill=hair)
    d.rectangle((8,8,15,10),fill=hair);d.rectangle((8,11,8,15),fill=hair);d.rectangle((15,10,15,15),fill=hair)
    d.point((10,12),fill='#364e51');d.point((13,12),fill='#364e51');d.point((10,11),fill='#49352e');d.point((13,11),fill='#49352e')
    d.point((11,13),fill='#d59c84');d.line((11,14,12,14),fill='#a75a63')
    limb(16,16,8,12,4,jacket);d.polygon([(22,20),(25,20),(24,26),(23,28),(22,26)],fill=white)
    d.polygon([(22,20),(20,20),(22,25),(23,23)],fill='#576176');d.polygon([(25,20),(27,20),(25,25),(24,23)],fill='#576176')
    d.line((23,22,23,26),fill='#8f3c4b');d.point((23,21),fill='#8f3c4b');d.point((24,28),fill=gold);d.point((24,30),fill=gold)
    d.line((25,25,26,27),fill=gold);d.line((26,27,27,25),fill=gold)
    d.rectangle((33,20,38,25),fill=hair);d.rectangle((34,26,37,27),fill=hair)
    for x,y in [(40,16),(32,48)]:
        limb(x,y,3,12,4,jacket)
        d.rectangle((x+4,y+13,x+6,y+13),fill=white);d.rectangle((x+4,y+14,x+6,y+15),fill=skin)
    for x,y in [(0,16),(16,48)]:
        limb(x,y,4,12,4,trousers)
        d.rectangle((x,y+13,x+15,y+15),fill=shoe)
        d.line((x+5,y+5,x+5,y+12),fill='#3e4a60')
    # Hair outer layer and an actual gold monocle over the right eye.
    d.rectangle((40,0,47,7),fill=hair);d.rectangle((56,8,63,15),fill=hair)
    d.rectangle((40,8,47,9),fill=hair);d.rectangle((40,10,40,15),fill=hair);d.rectangle((47,10,47,15),fill=hair)
    for pt in [(44,11),(45,10),(46,11),(46,12),(45,13),(44,12)]:d.point(pt,fill=gold)
    d.line((46,13,47,15),fill=gold)
    path=PACK/'assets/noqui/textures/entity/shopkeeper.png';path.parent.mkdir(parents=True,exist_ok=True);im.save(path)

def sounds(archive):
    records=[]
    with zipfile.ZipFile(archive) as z:
        name=next(n for n in z.namelist() if n.endswith('/assets/minecraft/sounds.json'))
        definitions=json.loads(z.read(name).decode('utf-8-sig'))
        minimal={key:definitions[key] for key in SOUNDS}
        write_json(PACK/'assets/minecraft/sounds.json',minimal)
        prefix=name[:-len('sounds.json')]
        for key in SOUNDS:
            member=prefix+f'sounds/arsf/{key}.ogg';data=z.read(member)
            out=PACK/f'assets/minecraft/sounds/arsf/{key}.ogg';out.parent.mkdir(parents=True,exist_ok=True);out.write_bytes(data)
            records.append({'event':f'minecraft:{key}','member':member,'sha256':hashlib.sha256(data).hexdigest(),'bytes':len(data)})
    return records

if __name__=='__main__':
    import argparse
    parser=argparse.ArgumentParser();parser.add_argument('--original-zip',type=Path,required=True);args=parser.parse_args()
    palette();native_models();backpack();skin();records=sounds(args.original_zip)
    write_json(PACK/'pack.mcmeta',{'pack':{'description':'Noqui lobby cosmetics and original quiz cues','min_format':[97,1],'max_format':[97,1]}})
    write_json(ROOT/'sound-provenance.json',{'source':'User-provided 노래퀴즈1218.zip; no external redistribution license asserted','archiveSha256':hashlib.sha256(args.original_zip.read_bytes()).hexdigest(),'sounds':records})
    print('Authored11 model/item definitions, original palette/slim skin, converted pinned MIT backpack, and copied exact5 user-supplied sounds')
