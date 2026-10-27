"""Build the independent Minecraft 26.3 Moundi hint overlay pack.

The font is Noto Sans KR (OFL); only GUI text is repositioned. No terrain,
lighting, item, entity, or fragment shader is replaced.
"""
import argparse
import hashlib
import json
import math
import struct
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parent
PACK = ROOT / 'pack'
RESOURCES = ROOT.parent / 'plugin/src/main/resources'
ASCENT = -(((1024 + 300) << 13) + 4095)
FONT = 'assets/noqui_hint/font/hint.json'
SPRITES = {f'assets/minecraft/textures/gui/sprites/boss_bar/pink_{kind}.png'
           for kind in ('background', 'progress')}


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, separators=(',', ':')) + '\n', encoding='utf-8', newline='\n')


def atlas_rows(glyphs):
    # Power-of-two row counts preserve native UV sampling at every GUI scale.
    rows = math.ceil(glyphs / 16)
    return 1 << (rows - 1).bit_length()


def rasterize(font_path):
    from PIL import Image, ImageFont, ImageDraw
    font = ImageFont.truetype(str(font_path), 30)
    font.set_variation_by_axes([500])
    chars = list(range(33, 127)) + list(range(0x3131, 0x318F)) + list(range(0xAC00, 0xD7A4))
    providers, metrics = [], {'32': 4}
    for page, start in enumerate(range(0, len(chars), 256)):
        codes = chars[start:start + 256]
        rows = atlas_rows(len(codes))
        image = Image.new('RGBA', (48 * 16, 36 * rows))
        draw = ImageDraw.Draw(image)
        grid = []
        for row in range(rows):
            line = ''
            for col in range(16):
                i = row * 16 + col
                char = chr(codes[i]) if i < len(codes) else '\0'
                line += char
                if char == '\0':
                    continue
                box = font.getbbox(char, anchor='ls')
                draw.text((col * 48 - box[0], row * 36 + 28), char, font=font, anchor='ls', fill='white')
                cell = image.crop((col * 48, row * 36, col * 48 + 48, row * 36 + 36))
                ink = cell.getchannel('A').getbbox()
                assert not ink or (ink[2] <= 48 and box[1] + 28 >= 0 and box[3] + 28 <= 36), ('Clipped glyph', char)
                metrics[str(ord(char))] = math.floor(.5 + (ink[2] if ink else 0) / 3) + 1
            grid.append(line)
        file = f'assets/noqui_hint/textures/font/page_{page:02}.png'
        path = PACK / file
        path.parent.mkdir(parents=True, exist_ok=True)
        # Discard only the 20 completely transparent columns in each cell.
        compact = Image.new('LA', (28 * 16, 36 * rows))
        for row in range(rows):
            for col in range(16):
                cell = image.crop((col * 48, row * 36, (col + 1) * 48, (row + 1) * 36))
                assert not cell.getchannel('A').crop((28, 0, 48, 36)).getbbox(), 'Glyph would be clipped'
                compact.paste(cell.crop((0, 0, 28, 36)).convert('LA'), (col * 28, row * 36))
        compact.save(path, optimize=True)
        providers.append({'type': 'bitmap', 'file': f'noqui_hint:font/page_{page:02}.png', 'height': 12, 'chars': grid})
    advances = {chr(0xF000 + n): n for n in range(-360, 361)}
    advances[' '] = 4
    write_json(PACK / FONT, {'providers': [
        {'type': 'space', 'advances': advances}, *[dict(p, ascent=ASCENT) for p in providers]]})
    write_json(RESOURCES / 'hint-glyph-widths.json', metrics)
    write_json(ROOT / 'font-provenance.json', {
        'font': 'Noto Sans KR', 'weight': 500, 'license': 'OFL-1.1',
        'source': 'https://github.com/google/fonts/tree/main/ofl/notosanskr',
        'sourceSha256': hashlib.sha256(font_path.read_bytes()).hexdigest(),
        'rasterCell': [28, 36], 'displayHeight': 12, 'glyphs': len(chars),
        'advance': 'floor(0.5 + rightmostOpaquePixel * 12 / 36) + 1'})


def validate():
    meta = json.loads((PACK / 'pack.mcmeta').read_text())['pack']
    assert meta['min_format'] == meta['max_format'] == [97, 1]
    shader_files = {p.relative_to(PACK).as_posix() for p in PACK.rglob('*') if p.is_file() and '/shaders/' in p.as_posix()}
    assert shader_files == {'assets/minecraft/shaders/core/text.vsh'}
    widths = json.loads((RESOURCES / 'hint-glyph-widths.json').read_text(encoding='utf-8'))
    assert len(widths) == 11361 and all(isinstance(v, int) and 0 < v <= 12 for v in widths.values())
    providers = json.loads((PACK / FONT).read_text(encoding='utf-8'))['providers']
    assert len(providers) == 46 and providers[0]['type'] == 'space'
    assert providers[0]['advances'] == {**{chr(0xF000 + n): n for n in range(-360, 361)}, ' ': 4}
    expected = {'pack.mcmeta', 'licenses/NotoSansKR-OFL.txt', FONT, *shader_files, *SPRITES}
    expected_codes = list(range(33, 127)) + list(range(0x3131, 0x318F)) + list(range(0xAC00, 0xD7A4))
    codes = []
    for page, provider in enumerate(providers[1:]):
        assert provider['type'] == 'bitmap' and provider['ascent'] == ASCENT and provider['height'] == 12
        assert provider['file'] == f'noqui_hint:font/page_{page:02}.png'
        grid = provider['chars']
        rows = atlas_rows(min(256, len(expected_codes) - page * 256))
        assert len(grid) == rows and all(len(row) == 16 for row in grid)
        codes.extend(ord(char) for row in grid for char in row if char != '\0')
        relative = 'assets/noqui_hint/textures/' + provider['file'].split(':')[1]
        image = (PACK / relative).read_bytes()
        assert image[:8] == b'\x89PNG\r\n\x1a\n' and struct.unpack('>IIBB', image[16:26]) == (448, rows * 36, 8, 4)
        expected.add(relative)
    assert codes == expected_codes
    assert set(widths) == {'32', *(str(code) for code in codes)}
    assert not any(p.is_symlink() for p in PACK.rglob('*')), 'No symlinks in pack'
    actual = {p.relative_to(PACK).as_posix() for p in PACK.rglob('*') if p.is_file()}
    assert actual == expected, {'unexpected': sorted(actual - expected), 'missing': sorted(expected - actual)}
    assert (PACK / 'licenses/NotoSansKR-OFL.txt').stat().st_size > 1000


def packed_files():
    files = {}
    license_path = 'licenses/NotoSansKR-OFL.txt'
    for path in sorted(PACK.rglob('*')):
        if not path.is_file() or path.relative_to(PACK).as_posix() == license_path:
            continue
        files[path.relative_to(PACK).as_posix()] = (path.read_bytes() if path.suffix == '.png'
            else path.read_text(encoding='utf-8').replace('\r\n', '\n').encode('utf-8'))
    notice = (PACK / license_path).read_text(encoding='utf-8').replace('\r\n', '\n')
    metadata = json.loads(files['pack.mcmeta'])
    metadata['licenses'] = {hashlib.sha256(notice.encode('utf-8')).hexdigest(): notice}
    files['pack.mcmeta'] = (json.dumps(metadata, ensure_ascii=False, separators=(',', ':')) + '\n').encode('utf-8')
    return files


def build(output):
    validate()
    output.mkdir(parents=True, exist_ok=True)
    temp = output / 'hint-pack.zip'
    with zipfile.ZipFile(temp, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, data in packed_files().items():
            entry = zipfile.ZipInfo(name, date_time=(2026, 10, 25, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.create_system = 3
            entry.external_attr = 0o100644 << 16
            archive.writestr(entry, data, compresslevel=9)
    data = temp.read_bytes()
    sha256, sha1 = hashlib.sha256(data).hexdigest(), hashlib.sha1(data).hexdigest()
    target = output / f'{sha256}.zip'
    temp.replace(target)
    (RESOURCES / 'hint-pack.properties').write_text(f'url=https://noqui.danta.qzz.io/packs/{sha256}.zip\nsha1={sha1}\n', encoding='ascii', newline='\n')
    print(json.dumps({'pack': str(target), 'sha256': sha256, 'sha1': sha1, 'bytes': len(data)}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--font', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.font:
        rasterize(args.font)
    build(args.output)
