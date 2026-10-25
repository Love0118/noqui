"""Build the independent Minecraft 26.3 Moundi hint overlay pack.

The font is Noto Sans KR (OFL); only GUI text is repositioned. No terrain,
lighting, item, entity, or fragment shader is replaced.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parent
PACK = ROOT / 'pack'
RESOURCES = ROOT.parent / 'plugin/src/main/resources'


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, separators=(',', ':')) + '\n', encoding='utf-8', newline='\n')


def rasterize(font_path):
    from PIL import Image, ImageFont, ImageDraw
    font = ImageFont.truetype(str(font_path), 30)
    font.set_variation_by_axes([500])
    chars = list(range(33, 127)) + list(range(0x3131, 0x318F)) + list(range(0xAC00, 0xD7A4))
    providers, metrics = [], {'32': 4}
    for page, start in enumerate(range(0, len(chars), 256)):
        codes = chars[start:start + 256]
        image = Image.new('RGBA', (48 * 16, 36 * 16))
        draw = ImageDraw.Draw(image)
        grid = []
        for row in range(16):
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
        image.save(path, optimize=True)
        providers.append({'type': 'bitmap', 'file': f'noqui_hint:font/page_{page:02}.png', 'height': 12, 'chars': grid})
    advances = {chr(0xF000 + n): n for n in range(-360, 361)}
    advances[' '] = 4
    for row in range(6):
        ascent = -(((1024 + 300 + row) << 13) + 4095)
        write_json(PACK / f'assets/noqui_hint/font/row_{row}.json', {'providers': [
            {'type': 'space', 'advances': advances}, *[dict(p, ascent=ascent) for p in providers]]})
    write_json(RESOURCES / 'hint-glyph-widths.json', metrics)
    write_json(ROOT / 'font-provenance.json', {
        'font': 'Noto Sans KR', 'weight': 500, 'license': 'OFL-1.1',
        'source': 'https://github.com/google/fonts/tree/main/ofl/notosanskr',
        'sourceSha256': hashlib.sha256(font_path.read_bytes()).hexdigest(),
        'rasterCell': [48, 36], 'displayHeight': 12, 'glyphs': len(chars),
        'advance': 'floor(0.5 + rightmostOpaquePixel * 12 / 36) + 1'})


def validate():
    assert json.loads((PACK / 'pack.mcmeta').read_text())['pack']['min_format'] == [97, 1]
    shader_files = {p.relative_to(PACK).as_posix() for p in PACK.rglob('*') if p.is_file() and '/shaders/' in p.as_posix()}
    assert shader_files == {'assets/minecraft/shaders/core/text.vsh'}
    widths = json.loads((RESOURCES / 'hint-glyph-widths.json').read_text(encoding='utf-8'))
    assert len(widths) == 11361 and max(widths.values()) <= 12
    for row in range(6):
        providers = json.loads((PACK / f'assets/noqui_hint/font/row_{row}.json').read_text(encoding='utf-8'))['providers']
        assert len(providers) == 46
        assert all(p['ascent'] == -(((1024 + 300 + row) << 13) + 4095) for p in providers[1:])
        assert all((PACK / ('assets/noqui_hint/textures/' + p['file'].split(':')[1])).is_file() for p in providers[1:])
    assert (PACK / 'licenses/NotoSansKR-OFL.txt').stat().st_size > 1000


def build(output):
    validate()
    output.mkdir(parents=True, exist_ok=True)
    temp = output / 'hint-pack.zip'
    with zipfile.ZipFile(temp, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path in sorted(PACK.rglob('*')):
            if path.is_file():
                entry = zipfile.ZipInfo(path.relative_to(PACK).as_posix(), date_time=(2026, 10, 25, 0, 0, 0))
                entry.compress_type = zipfile.ZIP_DEFLATED
                data = path.read_bytes() if path.suffix == '.png' else path.read_text(encoding='utf-8').replace('\r\n', '\n').encode('utf-8')
                archive.writestr(entry, data)
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
