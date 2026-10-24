"""Focused geometry/UV checks and review images for the three owned gadgets.

Does not run the full pack build or touch its manifest/hash/descriptor.
Native equipment views use the vanilla chestplate UV islands and 1px outer
armor inflation; they are software QA, not in-game screenshots.
"""
import sys
sys.dont_write_bytecode = True
from pathlib import Path
import hashlib
import json
import math
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageColor

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(ROOT))
from model_geometry import model_bounds, NORMALS
from build_pack import texture_path
from preview_assets import load, render, cube

PACK = ROOT / 'pack'
OUT = HERE / 'previews'
NAMES = ('hammer', 'gravity_suit', 'snow_bomb')


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def validate():
    results = {}
    for name in NAMES:
        path = PACK / f'assets/noqui/models/{name}.json'
        model = read(path)
        assert 'parent' not in model and 1 <= len(model['elements']) <= 256
        assert set(model['display']['fixed']) == {'rotation', 'translation', 'scale'}
        for slot, display in model['display'].items():
            for kind in ('rotation', 'translation', 'scale'):
                values = display[kind]
                assert len(values) == 3 and all(math.isfinite(v) for v in values), (name, slot, kind)
                if kind == 'scale':
                    assert all(0 < v <= 4 for v in values)
        textures = {}
        for reference in model['textures'].values():
            texture = PACK / texture_path(model, reference)
            with Image.open(texture) as image:
                image.load()
                assert image.size == (64, 64)
            textures[texture.relative_to(PACK).as_posix()] = sha(texture)
        for element in model['elements']:
            assert all(-16 <= a < b <= 32 for a, b in zip(element['from'], element['to']))
            assert set(element['faces']) == set(NORMALS), (name, element['name'])
            if 'rotation' in element:
                rotation = element['rotation']
                assert rotation['axis'] in ('x', 'y', 'z') and rotation['angle'] in (-45, -22.5, 0, 22.5, 45)
            for face in element['faces'].values():
                u, v, U, V = face['uv']
                assert 0 <= u <= 16 and 0 <= U <= 16 and 0 <= v <= 16 and 0 <= V <= 16
                assert u != U and v != V
                assert (PACK / texture_path(model, face['texture'])).is_file()
        low, high = model_bounds(model, fixed=True)
        extents = [round(b-a, 6) for a, b in zip(low, high)]
        assert min(extents) >= .5 and max(extents) <= 28
        project = read(HERE / (name + '.bbmodel'))
        assert len(project['elements']) == len(model['elements']), name
        assert project['textures'] and project['textures'][0]['source'].startswith('data:image/png;base64,'), name
        results[name] = {'modelSha256': sha(path), 'cubes': len(model['elements']),
                         'fixedExtentsModelUnits': extents, 'textures': textures}

    hammer = read(PACK / 'assets/noqui/models/hammer.json')
    assert hammer['display']['firstperson_righthand']['scale'] == [.64] * 3
    assert hammer['display']['thirdperson_righthand']['scale'] == [.9] * 3
    # Native item transform uses an XYZ quaternion. The grip center must land
    # at the fist origin after the item's rotation, scale and translation.
    display = hammer['display']['thirdperson_righthand']
    x, y, z = np.radians(display['rotation'])
    rx = np.array([[1,0,0],[0,np.cos(x),-np.sin(x)],[0,np.sin(x),np.cos(x)]])
    ry = np.array([[np.cos(y),0,np.sin(y)],[0,1,0],[-np.sin(y),0,np.cos(y)]])
    rz = np.array([[np.cos(z),-np.sin(z),0],[np.sin(z),np.cos(z),0],[0,0,1]])
    grip = rx @ ry @ rz @ (np.array([0, 3.95-8, 0])*display['scale']) + display['translation']
    assert np.linalg.norm(grip) < .025, grip
    # Reject the earlier shaft/forearm alignment. The handle must angle across
    # the fist; rotating local Y onto display Z produces that bad alignment.
    shaft = rx @ ry @ rz @ np.array([0, 1, 0])
    assert abs(shaft[2]) < .6 and shaft[1] > .8
    results['hammer']['gripDistanceFromFistModelUnits'] = round(float(np.linalg.norm(grip)), 6)
    results['hammer']['thirdpersonShaftInHandFrame'] = [round(float(v), 6) for v in shaft]

    snow = read(PACK / 'assets/noqui/models/snow_bomb.json')
    for slot in ('fixed', 'ground'):
        display = snow['display'][slot]
        assert display['scale'] == [.65] * 3
        bounds = model_bounds({**snow, 'display': {'fixed': display}}, fixed=True)
        blocks = [(b-a)/16 for a, b in zip(*bounds)]
        assert max(blocks) <= .3, blocks
        results['snow_bomb'][slot + 'ExtentsBlocks'] = [round(v, 5) for v in blocks]

    native = PACK / 'assets/noqui/textures/entity/equipment/humanoid/gravity_suit.png'
    pixels = np.asarray(Image.open(native).convert('RGBA'))
    assert pixels.shape == (32, 64, 4)
    assert not pixels[:16, :, 3].any(), 'Head texture area must remain clear'
    assert not pixels[16:, :16, 3].any(), 'Leg texture area must remain clear'
    assert not pixels[25:, 40:, 3].any(), 'Harness forearms must remain open'
    assert pixels[25:27, 23:25, :3].min() >= 220, 'Core highlight missing'
    uses = []
    for path in (PACK / 'assets/noqui/equipment').rglob('*.json'):
        layers = read(path).get('layers', {}).get('humanoid', [])
        if any(layer.get('texture') == 'noqui:gravity_suit' for layer in layers):
            assert layers[0]['texture'] == 'noqui:gravity_suit'
            uses.append(path.relative_to(PACK).as_posix())
    assert len(uses) > 1, 'Combined back+gravity assets must keep the shared suit layer'
    results['nativeGravity'] = {'sha256': sha(native), 'size': [64, 32], 'referencingEquipmentAssets': uses,
        'preview': 'vanilla humanoid chestplate UVs, 1px outer armor inflation, slight idle arm pose; software render'}
    return results


def armor_elements():
    tex = np.asarray(Image.open(PACK / 'assets/noqui/textures/entity/equipment/humanoid/gravity_suit.png').convert('RGBA'))
    def part(name, a, b, x, y, w, h, d, mirror=False):
        rects = {'up':[x+d,y,x+d+w,y+d], 'down':[x+d+w,y+d,x+d+2*w,y],
                 'west':[x,y+d,x+d,y+d+h], 'south':[x+d,y+d,x+d+w,y+d+h],
                 'east':[x+d+w,y+d,x+2*d+w,y+d+h], 'north':[x+2*d+w,y+d,x+2*d+2*w,y+d+h]}
        if mirror:
            rects['east'], rects['west'] = rects['west'], rects['east']
            rects = {f:[U,v,u,V] for f,(u,v,U,V) in rects.items()}
        faces = {f:{'uv':[u/4,v/2,U/4,V/2]} for f,(u,v,U,V) in rects.items()}
        return {'name':name, 'from':[v-1 for v in a], 'to':[v+1 for v in b],
                'faces':faces, '_texture':tex}
    result = [part('Body outer armor', [-4,12,-2], [4,24,2], 16,16,8,12,4),
              part('Right shoulder outer armor', [-8,12,-2], [-4,24,2], 40,16,4,12,4),
              part('Left shoulder outer armor', [4,12,-2], [8,24,2], 40,16,4,12,4, True)]
    for element, x, angle in ((result[1],-5,-5),(result[2],5,5)):
        element['rotation'] = {'origin':[x,22,0],'x':-5,'z':angle}
    return result


def wearable_preview():
    skin = '#b8a998'
    body = [cube([-4,24,-4], [4,32,4], skin), cube([-4,12,-2], [4,24,2], '#697581'),
            cube([-8,12,-2], [-4,24,2], skin), cube([4,12,-2], [8,24,2], skin),
            cube([-4,0,-2], [0,12,2], '#3d4656'), cube([0,0,-2], [4,12,2], '#3d4656')]
    for element, x, angle in ((body[2],-5,-5),(body[3],5,5)):
        element['rotation'] = {'origin':[x,22,0],'x':-5,'z':angle}
    elements = body + armor_elements()
    for name, view in (('front', (.7,.35,1)), ('back', (-.7,.35,-1))):
        render(elements, 460, 620, view=view, padding=25).save(OUT / f'gravity_native_{name}.png')
    # Saved preview-only model makes the exact UV mapping inspectable in BB.
    preview_model = {'texture_size':[64,32], 'textures':{'0':str(PACK / 'assets/noqui/textures/entity/equipment/humanoid/gravity_suit.png')}, 'elements':[]}
    for element in armor_elements():
        target = {'name':element['name'],
            'from':[element['from'][0]+8,element['from'][1],element['from'][2]+8],
            'to':[element['to'][0]+8,element['to'][1],element['to'][2]+8],
            'faces':{f:{**value,'texture':'#0'} for f,value in element['faces'].items()}}
        if 'rotation' in element:
            target['rotation'] = {**element['rotation'], 'origin':[element['rotation']['origin'][0]+8,22,8]}
        preview_model['elements'].append(target)
    (HERE / 'native_chest_preview.json').write_text(json.dumps(preview_model, indent=2)+'\n', encoding='utf-8')


def review_sheet():
    board = Image.new('RGB', (1560, 1250), '#edf1f5')
    draw = ImageDraw.Draw(board)
    font = ImageFont.truetype('C:/Windows/Fonts/segoeui.ttf', 23)
    small = ImageFont.truetype('C:/Windows/Fonts/segoeui.ttf', 18)
    draw.text((30, 18), 'NOQUI / GADGET REDESIGN', fill='#213849', font=font)
    draw.text((30, 54), 'Blockbench MCP item exports and display previews / native armor UV software QA', fill='#526c7b', font=small)
    def paste(path, xy, size, crop=True):
        image = Image.open(path).convert('RGBA')
        if crop:
            bbox = image.getbbox()
            if bbox:
                image = image.crop(bbox)
        image.thumbnail(size, Image.Resampling.LANCZOS)
        x, y = xy
        board.paste(image, (x+(size[0]-image.width)//2, y+(size[1]-image.height)//2), image)
    for i, (name, title) in enumerate((('hammer','Aurora toy mallet'),('gravity_suit','Gravity harness'),('snow_bomb','Frost snow bomb'))):
        x = 30 + 510*i
        draw.rounded_rectangle((x, 95, x+480, 435), radius=14, fill='#ffffff')
        draw.text((x+20, 110), title, fill='#213849', font=font)
        paste(OUT / (name+'_gui.png'), (x+30,155), (420,260))
    for i, slot in enumerate(('righthand','lefthand')):
        x = 30 + i*500
        draw.rounded_rectangle((x,455,x+480,815),radius=14,fill='#fff')
        draw.text((x+18,470), 'Third person / '+slot.replace('hand',' hand'),fill='#213849',font=small)
        paste(OUT / ('hammer_thirdperson_'+slot+'.png'), (x+18,505),(444,290))
    draw.rounded_rectangle((1030,455,1530,1215),radius=14,fill='#fff')
    draw.text((1050,470),'Native CHEST / front + back',fill='#213849',font=small)
    paste(OUT/'gravity_native_front.png',(1045,505),(235,480),crop=False)
    paste(OUT/'gravity_native_back.png',(1280,505),(235,480),crop=False)
    native = Image.open(PACK/'assets/noqui/textures/entity/equipment/humanoid/gravity_suit.png').convert('RGBA').resize((384,192),Image.Resampling.NEAREST)
    board.paste(native,(1088,995),native)
    for i, slot in enumerate(('righthand','lefthand')):
        x = 30 + i*500
        draw.rounded_rectangle((x,835,x+480,1215),radius=14,fill='#fff')
        draw.text((x+18,850),'First person / '+slot.replace('hand',' hand'),fill='#213849',font=small)
        paste(OUT / ('hammer_firstperson_'+slot+'.png'), (x+15,900),(450,290),crop=False)
    board.save(OUT/'review_sheet.png')


if __name__ == '__main__':
    OUT.mkdir(exist_ok=True)
    results = validate()
    wearable_preview()
    for name in NAMES:
        # Additional large, neutral-background geometry/texture inspection.
        view = (.6,.45,1) if name == 'hammer' else (.6,.35,-1)
        render(load(name), 600, 600, view=view, padding=35).save(OUT/(name+'_software.png'))
    review_sheet()
    report = {'scope':'three owned gadget models and dedicated textures only',
              'blockbench':{'version':'5.1.6','mcpPluginVersion':'1.10.1','transport':'configured localhost streamable HTTP'},
              'validation':results,
              'previewNotes':'Raw item/display images were rendered on demand by Blockbench MCP. Native chest images use the committed 64x32 texture on vanilla inflated armor geometry with a slight idle arm pose. No in-game verification claimed.',
              'previewFiles':{p.name:sha(p) for p in sorted(OUT.glob('*.png'))}}
    (HERE/'validation.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    baseline = {'hammer':'69a08f18aa23c576acf0ead12b4535d070c3ea33fe0e45dce33105d6b86c80c3',
                'gravity_suit':'acd9aef76fa80c92ee4883c09934815cae6e64da1dab71eb40d2ef910e52f004',
                'snow_bomb':'fed3b19eb29e857df39f46f1e99d896d1cd3469ac6a39bee5e1f1d092a745bd1'}
    provenance = {'origin':'Original Noqui cuboid and pixel designs authored for the explicit user request; no third-party model or texture inputs.',
        'authoring':'Procedural Pillow pixel textures and cuboid inputs, imported/edited/inspected/exported via the configured Blockbench MCP.',
        'models':{name:{'packPath':f'assets/noqui/models/{name}.json',
                        'baselineSha256':baseline[name], 'replacementSha256':results[name]['modelSha256'],
                        'dedicatedTextures':results[name]['textures']} for name in NAMES},
        'nativeTexture':{'packPath':'assets/noqui/textures/entity/equipment/humanoid/gravity_suit.png',
                         'baselineSha256':'765436c5357a953cf4c574bf3d91f4119ed987019ee05020274fa70def6969e6',
                         'replacementSha256':results['nativeGravity']['sha256']},
        'integration':'Parent reconciles preserved-assets.json model records and the native-equipment.json texture record, then runs the full pack build/check and Git delivery.',
        'inGameVerification':False}
    (HERE/'provenance.json').write_text(json.dumps(provenance,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'validated':list(results),'cubes':{n:results[n]['cubes'] for n in NAMES},
                      'snowFixedBlocks':results['snow_bomb']['fixedExtentsBlocks'],
                      'gripDistance':results['hammer']['gripDistanceFromFistModelUnits'],
                      'nativeReferences':len(results['nativeGravity']['referencingEquipmentAssets'])},indent=2))
