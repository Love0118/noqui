"""Reproduce the three original gadget designs; never rebuild the shared pack.

Requires Pillow. Run this file, then run blockbench_export.js through the
configured Blockbench MCP risky_eval tool for each item. Only owned outputs
are written. No vendor, shared palette, equipment definitions or hashes change.
"""
from pathlib import Path
import json
import math
from PIL import Image, ImageDraw, ImageColor

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
PACK = ROOT / 'pack'
MODEL = PACK / 'assets/noqui/models'
TEXTURE = PACK / 'assets/noqui/textures'
FACES = ('north', 'south', 'east', 'west', 'up', 'down')


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')


def tile_uv(tile):
    x, y = tile % 4 * 4, tile // 4 * 4
    return [x, y, x + 4, y + 4]


def box(name, low, high, tile, face_tiles=None, rotation=None):
    faces = {f: {'uv': tile_uv((face_tiles or {}).get(f, tile)), 'texture': '#0'} for f in FACES}
    result = {'name': name, 'from': low, 'to': high, 'faces': faces}
    if rotation:
        result['rotation'] = rotation
    return result


def atlas(name, colors, motifs):
    image = Image.new('RGBA', (64, 64))
    for tile, color in enumerate(colors):
        tile_image = Image.new('RGBA', (16, 16), color)
        draw = ImageDraw.Draw(tile_image)
        # Deliberate, restrained pixel wear rather than flat material swatches.
        rgb = tuple(int(color[i:i + 2], 16) for i in (1, 3, 5))
        light = tuple(min(255, c + 10) for c in rgb)
        dark = tuple(max(0, c - 9) for c in rgb)
        for y in range(16):
            for x in range(16):
                if (x * 7 + y * 13 + tile * 3) % 31 == 0:
                    draw.point((x, y), fill=light)
                elif (x * 11 + y * 5 + tile) % 47 == 0:
                    draw.point((x, y), fill=dark)
        if tile in motifs:
            motifs[tile](draw)
        image.paste(tile_image, (tile % 4 * 16, tile // 4 * 16))
    path = TEXTURE / 'item' / (name + '.png')
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path)


def wood(d):
    for x in (2, 6, 11, 14):
        d.line((x, 0, x, 15), fill='#9d623d')
    d.line((3, 1, 3, 12), fill='#e8b47b')
    d.line((12, 4, 12, 15), fill='#dfaa72')
    d.rectangle((6, 7, 8, 10), outline='#b67b4e')


def grip(d):
    for y in (1, 5, 9, 13):
        d.line((0, y, 15, y), fill='#774f43')
        d.line((0, y + 1, 15, y + 1), fill='#332b31')


def rubber(d):
    d.line((0, 1, 15, 1), fill='#e27aa5')
    d.line((1, 3, 1, 12), fill='#ca608c')
    d.line((0, 14, 15, 14), fill='#8f365f')


def endcap(d):
    d.rectangle((2, 2, 13, 13), outline='#d46e9c', width=2)
    d.rectangle((4, 4, 11, 11), outline='#643956')
    # A simple four-point toy star, readable at inventory scale.
    d.polygon([(7, 4), (9, 7), (12, 8), (9, 9), (8, 12), (6, 9), (3, 8), (6, 7)], fill='#f4d49a')
    d.rectangle((7, 7, 8, 8), fill='#fff1cb')


def metal(d):
    d.line((0, 1, 15, 1), fill='#5d7884')
    d.line((0, 14, 15, 14), fill='#172733')
    for x, y in ((3, 4), (12, 11)):
        d.point((x, y), fill='#b9c8c5')


def brass(d):
    d.line((0, 1, 15, 1), fill='#f7d9a0')
    d.line((0, 14, 15, 14), fill='#9c7347')


def core(d):
    d.rectangle((1, 1, 14, 14), outline='#152e39', width=2)
    d.rectangle((3, 3, 12, 12), outline='#4bbeb8', width=2)
    d.polygon([(7, 4), (11, 8), (8, 11), (4, 7)], fill='#c9ffed')
    d.rectangle((7, 6, 9, 8), fill='#f0fff4')


def panel(d):
    d.line((1, 1, 14, 1), fill='#77949e')
    d.line((1, 2, 1, 14), fill='#4b6570')
    d.line((14, 4, 14, 14), fill='#182b35')
    d.rectangle((3, 11, 6, 12), fill='#1b343e')


def vent(d):
    d.rectangle((1, 1, 14, 14), outline='#4c6772')
    for y in (4, 7, 10):
        d.rectangle((3, y, 12, y + 1), fill='#142630')
        d.line((4, y, 11, y), fill='#357c83')


def octagonal_barrel(elements, name, x0, x1, tile, ends=3, radius=2.2):
    """Cuboid-only rounded barrel: flat panels and four real 45 degree bevels."""
    cy, cz = 13, 8
    h, a = radius, radius - .65
    def section(section_name, low, high):
        result = box(section_name, low, high, tile, {'east': ends, 'west': ends})
        # Crop the end artwork to each piece of the octagon instead of stamping
        # a second tiny star on every cap strip.
        u, v, _, _ = tile_uv(ends)
        uv = [u + (low[2] - (cz-h))*2/h, v + ((cy+h)-high[1])*2/h,
              u + (high[2] - (cz-h))*2/h, v + ((cy+h)-low[1])*2/h]
        for face in ('east', 'west'):
            result['faces'][face]['uv'] = [round(n, 5) for n in uv]
        elements.append(result)
    section(name + ' center', [x0, cy-a, cz-h], [x1, cy+a, cz+h])
    # The two cap strips only touch the center panel; no coplanar overlap.
    for sign in (-1, 1):
        y0, y1 = sorted((cy + sign*a, cy + sign*h))
        section(name + (' upper' if sign > 0 else ' lower'),
                [x0, y0, cz-a], [x1, y1, cz+a])
    t = .44
    bevel = (h-a)*math.sqrt(2)
    center = (a+h)/2 - t/(2*math.sqrt(2))
    for sy, sz in ((1, 1), (1, -1), (-1, 1), (-1, -1)):
        y, z = cy + sy*center, cz + sz*center
        elements.append(box(name + ' bevel ' + str(sy) + '/' + str(sz),
                            [x0, y-t/2, z-bevel/2], [x1, y+t/2, z+bevel/2], tile,
                            {'east': 6, 'west': 6},
                            {'origin': [(x0+x1)/2, y, z], 'axis': 'x',
                             'angle': 45 if sy == sz else -45}))


def transform(rotation=(0, 0, 0), translation=(0, 0, 0), scale=1):
    return {'rotation': list(rotation), 'translation': list(translation), 'scale': [scale]*3}


def item(name, texture, elements, display):
    model = {'credit': 'Original Noqui gadget redesign, authored with Blockbench MCP',
             'texture_size': [64, 64], 'textures': {'0': 'noqui:item/' + texture, 'particle': '#0'},
             'display': {'fixed': transform(), **display}, 'elements': elements}
    write_json(HERE / 'inputs' / (name + '.json'), model)
    write_json(MODEL / (name + '.json'), model)


def mallet():
    atlas('hammer_aurora', ['#c99563', '#513a35', '#b74c80', '#7a3a60',
                          '#294c55', '#cda36b', '#d36895', '#34414b',
                          '#36adab', '#edd7ac', '#984268', '#674a3c',
                          '#46616b', '#e8b9cc', '#866552', '#1d333d'],
          {0: wood, 1: grip, 2: rubber, 3: endcap, 4: metal, 5: brass})
    e = [box('Ash wood handle', [7.35, 1.8, 7.35], [8.65, 12.4, 8.65], 0),
         box('Wrapped lower grip', [7.23, 2.1, 7.23], [8.77, 5.8, 8.77], 1),
         box('Teal pommel', [7.14, 1.65, 7.14], [8.86, 2.3, 8.86], 4, {'down': 8}),
         box('Grip top binding', [7.18, 5.55, 7.18], [8.82, 5.98, 8.82], 5),
         box('Warm brass socket', [6.95, 10.4, 6.95], [9.05, 11.55, 9.05], 5)]
    octagonal_barrel(e, 'Raspberry rubber', 4.25, 11.75, 2)
    octagonal_barrel(e, 'Left soft striking cap', 3.65, 4.25, 6)
    octagonal_barrel(e, 'Right soft striking cap', 11.75, 12.35, 6)
    # Central sleeve sits above the barrel without hiding its rounded profile.
    octagonal_barrel(e, 'Teal center sleeve', 7.33, 8.67, 4, ends=4, radius=2.28)
    e += [box('Front brass rivet', [7.74, 12.72, 5.68], [8.26, 13.28, 5.79], 5),
          box('Rear brass rivet', [7.74, 12.72, 10.21], [8.26, 13.28, 10.32], 5)]
    item('hammer', 'hammer_aurora', e, {
        'gui': transform((25, 135, -12), scale=.82),
        'ground': transform(translation=(0, 2, 0), scale=.48),
        'firstperson_righthand': transform((0, -90, 15), (.75, 2.8, .6), .64),
        'firstperson_lefthand': transform((0, 90, -15), (.75, 2.8, .6), .64),
        'thirdperson_righthand': transform((0, 90, 25), (0, 3.30, 1.54), .90),
        'thirdperson_lefthand': transform((0, -90, -25), (0, 3.30, 1.54), .90),
        'head': transform(translation=(0, 10, 0), scale=.7)})


def harness():
    atlas('gravity_harness', ['#263b46', '#425c68', '#75919a', '#1b303b',
                            '#279e9c', '#80e5d2', '#c9ffed', '#4c7580',
                            '#d0ad78', '#1b5260', '#49616b', '#22323d',
                            '#344952', '#9fbdc1', '#315962', '#112a33'],
          {0: panel, 1: metal, 2: metal, 3: vent, 4: panel, 5: core, 7: metal, 8: brass})
    e = []
    # Open torso and collar keep the silhouette visibly a harness, not a slab.
    for side, x0, x1 in (('Left', 3.9, 5.5), ('Right', 10.5, 12.1)):
        e += [box(side + ' front shoulder strap', [x0, 5.4, 5.25], [x1, 13.6, 6.15], 0),
              box(side + ' shoulder bridge', [x0, 12.55, 5.35], [x1, 13.8, 10.3], 1),
              box(side + ' back strap', [x0, 4.0, 9.7], [x1, 12.65, 10.55], 0),
              box(side + ' teal shoulder seam', [x0+.2, 12.8, 5.15], [x1-.2, 13.28, 5.32], 4),
              box(side + ' lower latch', [x0+.15, 4.2, 4.98], [x1-.15, 5.1, 5.32], 8)]
    e += [box('Chest carrier plate', [5.1, 7.3, 5.13], [10.9, 12.5, 6.05], 0),
          box('Upper chest silver brow', [5.5, 11.95, 4.95], [10.5, 12.55, 5.35], 2),
          box('Reactor dark inset', [5.65, 8.35, 4.70], [10.35, 11.95, 5.22], 3),
          box('Core frame left', [5.55, 8.55, 4.35], [6.35, 11.7, 5.1], 1),
          box('Core frame right', [9.65, 8.55, 4.35], [10.45, 11.7, 5.1], 1),
          box('Core frame upper', [6.3, 11.25, 4.35], [9.7, 11.95, 5.1], 2),
          box('Core frame lower', [6.3, 8.2, 4.35], [9.7, 8.9, 5.1], 1),
          # Screen is recessed behind the four rim pieces.
          box('Recessed teal luminous core', [6.35, 8.9, 4.62], [9.65, 11.25, 4.91], 5),
          box('Lower floating plate', [5.6, 5.6, 5.02], [10.4, 7.65, 5.85], 0),
          box('Lower teal running light', [6.1, 6.1, 4.86], [9.9, 6.5, 5.09], 4),
          box('Belt front', [3.9, 2.6, 5.2], [12.1, 4.1, 6.1], 0),
          box('Belt back', [3.9, 2.6, 9.9], [12.1, 4.1, 10.8], 0),
          box('Belt left', [3.9, 2.6, 6.1], [4.8, 4.1, 9.9], 0),
          box('Belt right', [11.2, 2.6, 6.1], [12.1, 4.1, 9.9], 0),
          box('Belt silver clasp', [7.0, 2.75, 4.9], [9.0, 3.95, 5.3], 2),
          box('Belt clasp indicator', [7.65, 3.0, 4.74], [8.35, 3.65, 4.98], 4),
          box('Compact rear power pack', [5.2, 5.0, 10.1], [10.8, 12.3, 11.25], 0),
          box('Rear vent inset', [6.0, 7.0, 11.25], [10.0, 11.7, 11.6], 3),
          box('Rear lower status light', [6.8, 5.5, 11.25], [9.2, 6.0, 11.48], 4)]
    for side, x0 in (('Left', 2.35), ('Right', 12.1)):
        e += [box(side+' low profile shoulder cap', [x0, 11.2, 6.0], [x0+1.55, 13.25, 10.05], 1),
              box(side+' shoulder teal inlay', [x0+.15, 12.4, 5.86], [x0+1.4, 12.85, 6.10], 4)]
    for x in (4.35, 11.3):
        e.append(box('Strap fastener', [x, 11.45, 5.02], [x+.35, 11.8, 5.28], 8))
    item('gravity_suit', 'gravity_harness', e, {
        'gui': transform((18, 215, 0), scale=.87),
        'ground': transform(translation=(0, 2, 0), scale=.5),
        'firstperson_righthand': transform((0, -25, 0), (0, 1.5, 0), .55),
        'firstperson_lefthand': transform((0, 25, 0), (0, 1.5, 0), .55),
        'thirdperson_righthand': transform((0, 0, 0), (0, 3, 0), .5),
        'thirdperson_lefthand': transform((0, 0, 0), (0, 3, 0), .5)})


def wearable():
    image = Image.new('RGBA', (64, 32))
    palette = {'.': (0,0,0,0), 'D': '#20313b', 'M': '#3c535f', 'S': '#829da5',
               'T': '#2baaa6', 'c': '#73e7d0', 'W': '#dcfff1', 'G': '#4c6b75', 'B': '#cead78'}
    def pixels(x0, y0, rows):
        for y, row in enumerate(rows):
            for x, color in enumerate(row):
                value = palette[color]
                image.putpixel((x0+x, y0+y), ImageColor.getcolor(value, 'RGBA') if isinstance(value, str) else value)
    pixels(20, 20, [
        'SM....MS', 'MTM..MTM', 'DMTTTTMD', 'DMSTTSMD',
        'DMTccTMD', 'DMcWWcMD', 'DMcWWcMD', 'DMTccTMD',
        'TTMD DMTT'.replace(' ', ''), 'MDBMMBDM', 'SSSTTSSS', 'DMMDDMMD'])
    pixels(32, 20, [
        'SMTTTTMS', 'MTMDDMTM', 'DMTTTTMD', 'DMSD D SMD'.replace(' ', ''),
        'DMTDDTMD', 'DMSDDSMD', 'DMTDDTMD', 'DMSDDSMD',
        'TTMDDMTT', 'MDBMMBDM', 'SSSTTSSS', 'DMMDDMMD'])
    # Native body top/bottom: four by eight texels. The neck opening stays clear.
    pixels(20, 16, ['MM....MM', 'SM....MS', 'SM....MS', 'MM....MM'])
    pixels(28, 16, ['MDDMMDDM', 'DMMDDMMD', 'DMMDDMMD', 'MDDMMDDM'])
    for x in (16, 28):
        pixels(x, 20, ['MSMM', 'DTTD', 'DMDM', 'DDMD', 'DDMD', 'DDMD',
                       'DDMD', 'DDMD', 'TTTT', 'MDMM', 'SSSS', 'DMDM'])
    # Both native arms share this vanilla chestplate UV island. Short shoulder
    # caps end at row 24; the forearm is transparent, preserving the harness cut.
    pixels(44, 16, ['MMMM', 'MSSM', 'MTTM', 'MMMM'])
    for x in (40, 44, 48, 52):
        pixels(x, 20, ['MSSM', 'STTS', 'DTTD', 'DMDM', '.DD.'])
    path = TEXTURE / 'entity/equipment/humanoid/gravity_suit.png'
    image.save(path)


def snow():
    def snow_grain(d):
        for x, y in ((2, 3), (11, 4), (6, 10), (13, 12), (3, 14)):
            d.point((x, y), fill='#c5e0e9')
        d.line((2, 1, 12, 1), fill='#ffffff')

    def snowflake(d):
        d.rectangle((1, 1, 14, 14), outline='#5aafca')
        d.line((7, 3, 7, 12), fill='#f1ffff', width=2)
        d.line((3, 7, 12, 7), fill='#f1ffff', width=2)
        d.line((4, 4, 11, 11), fill='#c9f6ff')
        d.line((4, 11, 11, 4), fill='#c9f6ff')
        d.rectangle((6, 6, 9, 9), fill='#ffffff')

    atlas('snow_bomb_frost', ['#edf7fa', '#d7ebf3', '#adcbd9', '#318dae',
                            '#5fbdd4', '#99b6c2', '#395567', '#dbb06b',
                            '#ffffff', '#a8e4ed', '#244454', '#d2f2f9',
                            '#6fa5b5', '#e1f2f7', '#abc5d3', '#213947'],
          {0: snow_grain, 1: snow_grain, 3: snowflake, 5: metal, 6: metal, 7: brass})
    e = [box('Packed snow rounded center', [5.6, 6.2, 5.2], [10.4, 9.4, 10.8], 0,
             {'down': 1, 'west': 1}),
         box('Packed snow left edge', [5.2, 6.6, 5.8], [5.6, 9.0, 10.2], 1),
         box('Packed snow right edge', [10.4, 6.6, 5.8], [10.8, 9.0, 10.2], 0),
         box('Snow lower rounding', [5.8, 5.65, 5.8], [10.2, 6.2, 10.2], 1),
         box('Snow bottom', [6.45, 5.2, 6.45], [9.55, 5.65, 9.55], 1),
         box('Snow upper rounding', [5.8, 9.4, 5.8], [10.2, 9.95, 10.2], 0),
         box('Snow crown', [6.45, 9.95, 6.45], [9.55, 10.4, 9.55], 0),
         box('Ice belt front', [5.6, 7.2, 5.05], [10.4, 8.1, 5.30], 4),
         box('Ice belt back', [5.6, 7.2, 10.7], [10.4, 8.1, 10.95], 4),
         box('Ice belt left', [5.05, 7.2, 5.8], [5.3, 8.1, 10.2], 4),
         box('Ice belt right', [10.7, 7.2, 5.8], [10.95, 8.1, 10.2], 4),
         box('Snowflake front seal', [7.15, 6.9, 4.88], [8.85, 8.6, 5.2], 3),
         box('Snowflake back seal', [7.15, 6.9, 10.8], [8.85, 8.6, 11.12], 3),
         box('Arming collar', [7.1, 10.35, 7.1], [8.9, 10.9, 8.9], 6),
         box('Safety lever', [7.3, 10.9, 7.45], [9.55, 11.2, 8.1], 5),
         box('Warm pin latch', [8.75, 10.65, 7.38], [9.1, 11.3, 8.15], 7),
         box('Pin ring left', [9.25, 10.8, 7.55], [9.45, 11.8, 7.8], 5),
         box('Pin ring right', [10.15, 10.8, 7.55], [10.35, 11.8, 7.8], 5),
         box('Pin ring top', [9.45, 11.6, 7.55], [10.15, 11.8, 7.8], 5),
         box('Pin ring bottom', [9.45, 10.8, 7.55], [10.15, 11.0, 7.8], 5)]
    item('snow_bomb', 'snow_bomb_frost', e, {
        'fixed': transform(scale=.65),
        'gui': transform((20, 215, -8), (0, -.5, 0), 1.4),
        'ground': transform(translation=(0, 0, 0), scale=.65),
        'firstperson_righthand': transform((0, -20, 0), (0, 3.8, 0), .7),
        'firstperson_lefthand': transform((0, 20, 0), (0, 3.8, 0), .7),
        'thirdperson_righthand': transform((0, 0, 0), (0, 2.5, .5), .8),
        'thirdperson_lefthand': transform((0, 0, 0), (0, 2.5, .5), .8)})


if __name__ == '__main__':
    mallet()
    harness()
    wearable()
    snow()
    print('Authored three gadget models, three dedicated 64x64 item atlases, native 64x32 chest texture.')
