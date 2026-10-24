"""Optional software3D previews of the actual committed model/texture/skin assets.

Pillow/NumPy are preview-only dependencies; build_pack.py is stdlib-only.
"""
from pathlib import Path
import argparse
import hashlib
import json
import math
import numpy as np
from PIL import Image, ImageDraw, ImageFont
from model_geometry import face_vertices, NORMALS, rotated_point, rotated_normal, model_bounds
from build_pack import ROOT, PACK, GADGETS, DEFAULT_OUTPUT, catalog, item_ids, texture_path

OUT=DEFAULT_OUTPUT/'previews'
FONT=Path('C:/Windows/Fonts/segoeui.ttf')
def font(size):return ImageFont.truetype(str(FONT),size) if FONT.exists() else ImageFont.load_default(size=size)

def cube(a,b,color):
    im=Image.new('RGBA',(16,16),color)
    return {'from':a,'to':b,'faces':{f:{'uv':[0,0,16,16]} for f in NORMALS},'_texture':np.asarray(im)}

def load(name):
    model=json.loads((PACK/f'assets/noqui/models/{name}.json').read_text(encoding='utf-8'))
    textures={key:np.asarray(Image.open(PACK/texture_path(model,'#'+key)).convert('RGBA')) for key in model['textures']}
    result=[]
    for source in model['elements']:
        e={**source,'from':[n-8 for n in source['from']],'to':[n-8 for n in source['to']],'_textures':textures}
        if 'rotation' in source:e['rotation']={**source['rotation'],'origin':[n-8 for n in source['rotation']['origin']]}
        result.append(e)
    return result

def display_matrix(name,context='gui'):
    model=json.loads((PACK/f'assets/noqui/models/{name}.json').read_text(encoding='utf-8'))
    display=model['display'].get(context,{})
    x,y,z=np.radians(display.get('rotation',[0,0,0]))
    rx=np.array([[1,0,0],[0,np.cos(x),-np.sin(x)],[0,np.sin(x),np.cos(x)]])
    ry=np.array([[np.cos(y),0,np.sin(y)],[0,1,0],[-np.sin(y),0,np.cos(y)]])
    rz=np.array([[np.cos(z),-np.sin(z),0],[np.sin(z),np.cos(z),0],[0,0,1]])
    return rz@ry@rx@np.diag(display.get('scale',[1,1,1]))

def points_for_face(element,face):
    points=np.array([rotated_point(element,p) for p in face_vertices(element['from'],element['to'],face)])
    return points@element.get('_transform',np.eye(3)).T+element.get('_offset',np.zeros(3))

def render(elements,width,height,view=(.7,.45,1),padding=20,transform=None):
    transform=np.eye(3) if transform is None else transform
    view=np.array(view,dtype=float);view/=np.linalg.norm(view)
    right=np.cross([0,1,0],view);right/=np.linalg.norm(right);up=np.cross(view,right)
    allpoints=np.concatenate([points_for_face(e,f) for e in elements for f in e['faces']])@transform.T
    projected=np.column_stack([allpoints@right, -allpoints@up]);lo=projected.min(axis=0);hi=projected.max(axis=0)
    scale=min((width-2*padding)/(hi[0]-lo[0]),(height-2*padding)/(hi[1]-lo[1]));center=(lo+hi)/2
    canvas=np.empty((height,width,4),dtype=np.uint8);canvas[:]=[247,248,250,255]
    depth=np.full((height,width),-np.inf)
    light=np.array([-.3,.8,.6]);light/=np.linalg.norm(light)
    for element in elements:
        for face,spec in element['faces'].items():
            texture=element['_textures'][spec['texture'][1:]] if '_textures' in element else element['_texture']
            th,tw=texture.shape[:2]
            normal=np.linalg.inv(transform@element.get('_transform',np.eye(3))).T@np.array(rotated_normal(element,face));normal=normal/np.linalg.norm(normal)
            if normal@view<=0:continue
            points=points_for_face(element,face)@transform.T
            xy=np.column_stack([points@right,-points@up]);xy=(xy-center)*scale+[width/2,height/2];z=points@view
            u,v,U,V=spec['uv'];uv=np.array([[u,v],[U,v],[U,V],[u,V]],float)/16
            uv=np.roll(uv,-spec.get('rotation',0)//90,axis=0)
            shade=.64+.36*max(0,float(normal@light))
            for tri in [(0,1,2),(0,2,3)]:
                a,b,c=xy[list(tri)];x0=max(0,int(np.floor(min(a[0],b[0],c[0]))));x1=min(width-1,int(np.ceil(max(a[0],b[0],c[0]))))
                y0=max(0,int(np.floor(min(a[1],b[1],c[1]))));y1=min(height-1,int(np.ceil(max(a[1],b[1],c[1]))))
                if x0>x1 or y0>y1:continue
                yy,xx=np.mgrid[y0:y1+1,x0:x1+1];xx=xx+.5;yy=yy+.5
                denom=(b[1]-c[1])*(a[0]-c[0])+(c[0]-b[0])*(a[1]-c[1])
                if abs(denom)<1e-9:continue
                l0=((b[1]-c[1])*(xx-c[0])+(c[0]-b[0])*(yy-c[1]))/denom
                l1=((c[1]-a[1])*(xx-c[0])+(a[0]-c[0])*(yy-c[1]))/denom;l2=1-l0-l1
                zz=l0*z[tri[0]]+l1*z[tri[1]]+l2*z[tri[2]]
                texuv=l0[...,None]*uv[tri[0]]+l1[...,None]*uv[tri[1]]+l2[...,None]*uv[tri[2]]
                tx=np.clip((texuv[...,0]*tw).astype(int),0,tw-1);ty=np.clip((texuv[...,1]*th).astype(int),0,th-1)
                colors=texture[ty,tx].copy();colors[...,:3]=(colors[...,:3]*shade).astype(np.uint8)
                region=canvas[y0:y1+1,x0:x1+1];zregion=depth[y0:y1+1,x0:x1+1]
                mask=(l0>=-1e-8)&(l1>=-1e-8)&(l2>=-1e-8)&(zz>zregion)&(colors[...,3]>0)
                alpha=colors[...,3:4].astype(float)/255
                blended=(colors[...,:3]*alpha+region[...,:3]*(1-alpha)).astype(np.uint8)
                region[mask,:3]=blended[mask];zregion[mask]=zz[mask]
    return Image.fromarray(canvas,'RGBA')

def dummy():
    boxes=[([-4,24,-4],[4,32,4]),([-4,12,-2],[4,24,2]),([-7,12,-2],[-4,24,2]),([4,12,-2],[7,24,2]),([-4,0,-2],[0,12,2]),([0,0,-2],[4,12,2])]
    return [cube(a,b,'#c7cdd4') for a,b in boxes]

def attached(name):
    slots={row['id']:row['slot'] for row in catalog()};slots.update(GADGETS)
    model=json.loads((PACK/f'assets/noqui/models/{name}.json').read_text(encoding='utf-8'))
    if slots[name] in ('head','face'):
        # Official CustomHeadLayer anchor + HEAD item transform, expressed in
        # the preview's upright +Z-front player frame (uniform player scale omitted).
        native=np.diag([-.625,.625,-.625])
        matrix=native@display_matrix(name,'head')
        offset=np.array([0,28,0])+native@np.array(model['display']['head']['translation'])
    elif slots[name]=='back':
        # The exporter recentres each mesh. Match the actual native north
        # carrier fit: torso centre y=18, back surface beyond z=-3.2 model pixels.
        fixed=model['display']['fixed'];low,high=np.array(model_bounds(model,fixed=True))-np.array(fixed['translation'])
        center=(low+high)/2;matrix=np.diag([-.8,.8,-.8])@display_matrix(name,'fixed')
        offset=np.array([.8*center[0],18-.8*center[1],-3.2+.8*low[2]])
    else:
        raise ValueError('Native armor gadgets use equipment textures; use item previews for their shop icons')
    return dummy()+[{**e,'_transform':matrix,'_offset':offset} for e in load(name)]

def skin_elements():
    tex=np.asarray(Image.open(PACK/'assets/noqui/textures/entity/shopkeeper.png').convert('RGBA'))
    def part(name,a,b,x,y,w,h,d,expand=0):
        # Player UV is unfolded right/front/left/back with top and bottom above.
        rects={'up':[x+d,y,x+d+w,y+d],'down':[x+d+w,y+d,x+d+2*w,y],
               'west':[x,y+d,x+d,y+d+h],'south':[x+d,y+d,x+d+w,y+d+h],
               'east':[x+d+w,y+d,x+2*d+w,y+d+h],'north':[x+2*d+w,y+d,x+2*d+2*w,y+d+h]}
        return {'from':[v-expand for v in a],'to':[v+expand for v in b],'faces':{f:{'uv':[v/4 for v in uv]} for f,uv in rects.items()},'_texture':tex,'_part':'root/'+name}
    # Native 26.3 PlayerModel: all twelve parts, 0.5 hat / 0.25 clothing inflation,
    # and legs centered at +/-1.9. Overlay UVs must never be omitted from visual QA.
    return [part('head',[-4,24,-4],[4,32,4],0,0,8,8,8),
            part('body',[-4,12,-2],[4,24,2],16,16,8,12,4),
            part('right_arm',[-7,12,-2],[-4,24,2],40,16,3,12,4),
            part('left_arm',[4,12,-2],[7,24,2],32,48,3,12,4),
            part('right_leg',[-3.9,0,-2],[.1,12,2],0,16,4,12,4),
            part('left_leg',[-.1,0,-2],[3.9,12,2],16,48,4,12,4),
            part('head/hat',[-4,24,-4],[4,32,4],32,0,8,8,8,.5),
            part('body/jacket',[-4,12,-2],[4,24,2],16,32,8,12,4,.25),
            part('right_arm/right_sleeve',[-7,12,-2],[-4,24,2],40,32,3,12,4,.25),
            part('left_arm/left_sleeve',[4,12,-2],[7,24,2],48,48,3,12,4,.25),
            part('right_leg/right_pants',[-3.9,0,-2],[.1,12,2],0,32,4,12,4,.25),
            part('left_leg/left_pants',[-.1,0,-2],[3.9,12,2],0,48,4,12,4,.25)]

def sheet(worn=False,names=None,filename=None,columns=5,cellw=280,cellh=285):
    names=item_ids() if names is None else names
    if worn:names=[name for name in names if name not in GADGETS]
    width=columns*cellw;height=90+math.ceil(len(names)/columns)*cellh
    image=Image.new('RGB',(width,height),'#f7f8fa');draw=ImageDraw.Draw(image)
    draw.text((28,18),'NOQUI / ACTUAL MODEL '+('ATTACHMENTS' if worn else 'CONTACT SHEET'),font=font(24),fill='#202937')
    draw.text((28,53),'Software render of committed cuboids + UV textures. Not an in-game screenshot.',font=font(14),fill='#647080')
    slots={row['id']:row['slot'] for row in catalog()}
    for i,name in enumerate(names):
        col,row=i%columns,i//columns;x=col*cellw;y=86+row*cellh
        view=(-.7,.4,-1) if worn and slots.get(name)=='back' else (.65,.35,1)
        tile=render(attached(name) if worn else load(name),cellw,cellh-48,view)
        image.paste(tile,(x,y));draw.text((x+12,y+cellh-41),name,font=font(15),fill='#202937')
        if worn and slots.get(name)=='back':draw.text((x+12,y+cellh-19),'back view / runtime scale 0.8',font=font(12),fill='#647080')
    path=OUT/(filename or ('attachments.png' if worn else 'models.png'));image.save(path)
    return path

def item_images():
    """Use each item's actual GUI transform and all of its JSON face textures."""
    target=OUT/'items';target.mkdir(parents=True,exist_ok=True)
    records=[]
    for name in item_ids():
        model_path=PACK/f'assets/noqui/models/{name}.json'
        image_path=target/f'{name}.png'
        render(load(name),256,256,view=(0,0,1),transform=display_matrix(name)).save(image_path)
        model=json.loads(model_path.read_text(encoding='utf-8'))
        records.append({'id':name,'image':image_path.relative_to(OUT).as_posix(),
                        'modelSha256':hashlib.sha256(model_path.read_bytes()).hexdigest(),
                        'imageSha256':hashlib.sha256(image_path.read_bytes()).hexdigest(),
                        'textures':{relative:hashlib.sha256((PACK/relative).read_bytes()).hexdigest()
                                    for relative in sorted({texture_path(model,reference) for reference in model['textures'].values()})}})
    (OUT/'preview-manifest.json').write_text(json.dumps({'renderer':'JSON cuboids, face UVs, z-buffer, GUI transforms; software QA, no live client claim',
        'attachments':{'unitsPerBlock':16,'front':'+Z','modelOrigin':[8,8,8],'nativeHeadContext':True,'hatBaseY':2,'eyeY':1.859375,'nativeBackCentreY':1.125,'nativeBackSurface':-.2,'backScale':.8},
        'items':records},indent=2)+'\n',encoding='utf-8',newline='\n')

def skin_sheet():
    image=Image.new('RGB',(1060,800),'#f7f8fa');d=ImageDraw.Draw(image)
    d.text((25,18),'NOQUI / SLIM SHOPKEEPER',font=font(26),fill='#202937')
    d.text((25,57),'Munasharna: navy tuxedo, purple hair, monocle. All 12 native skin parts / front / back / UV.',font=font(15),fill='#647080')
    parts=skin_elements()
    image.paste(render(parts,350,650,(.25,.1,1)),(0,100));image.paste(render(parts,350,650,(-.4,.15,-1)),(350,100))
    skin=Image.open(PACK/'assets/noqui/textures/entity/shopkeeper.png').convert('RGBA').resize((320,320),Image.Resampling.NEAREST)
    board=Image.new('RGBA',(320,320),'#e3e7ed');board.alpha_composite(skin);image.paste(board.convert('RGB'),(720,135))
    d.text((725,475),'SLIM3px arms',font=font(20),fill='#202937');d.text((725,512),'noqui:entity/shopkeeper',font=font(15),fill='#647080')
    image.save(OUT/'shopkeeper.png')

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',type=Path,default=OUT);parser.add_argument('--skip-skin',action='store_true')
    args=parser.parse_args();OUT=args.output;OUT.mkdir(parents=True,exist_ok=True)
    sheet(columns=8,cellw=210,cellh=230);sheet(True,columns=8,cellw=210,cellh=260)
    names=item_ids()
    for start in range(0,len(names),20):
        page=start//20+1;subset=names[start:start+20]
        sheet(names=subset,filename=f'models-{page:02}.png');sheet(True,names=subset,filename=f'attachments-{page:02}.png')
    item_images()
    if not args.skip_skin:skin_sheet()
    print(OUT)
