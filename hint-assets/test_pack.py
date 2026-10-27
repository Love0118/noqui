"""Guard the one-font pack against duplicate providers and unused resources."""
from pathlib import Path
import json
import shutil
import tempfile
import unittest
import build_pack as build


class PackTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.original = build.PACK
        build.PACK = Path(self.tmp.name) / 'pack'
        shutil.copytree(self.original, build.PACK)

    def tearDown(self):
        build.PACK = self.original
        self.tmp.cleanup()

    def edit_font(self, edit):
        path = build.PACK / build.FONT
        font = json.loads(path.read_text(encoding='utf-8'))
        edit(font['providers'])
        path.write_text(json.dumps(font), encoding='utf-8')

    def test_one_font_covers_the_complete_original_character_set(self):
        build.validate()
        self.assertEqual(len(list((build.PACK / 'assets/noqui_hint/font').glob('*.json'))), 1)

    def test_old_per_row_font_is_rejected(self):
        shutil.copyfile(build.PACK / build.FONT, build.PACK / 'assets/noqui_hint/font/row_0.json')
        with self.assertRaises(AssertionError): build.validate()

    def test_unused_texture_is_rejected(self):
        shutil.copyfile(build.PACK / 'assets/noqui_hint/textures/font/page_00.png',
                        build.PACK / 'assets/noqui_hint/textures/font/unused.png')
        with self.assertRaises(AssertionError): build.validate()

    def test_duplicate_provider_and_missing_character_are_rejected(self):
        self.edit_font(lambda providers: providers.__setitem__(2, providers[1]))
        with self.assertRaises(AssertionError): build.validate()

    def test_wrong_row_marker_is_rejected(self):
        self.edit_font(lambda providers: providers[1].update(ascent=build.ASCENT - 8192))
        with self.assertRaises(AssertionError): build.validate()

    def test_missing_bossbar_sprite_is_rejected(self):
        (build.PACK / sorted(build.SPRITES)[0]).unlink()
        with self.assertRaises(AssertionError): build.validate()


if __name__ == '__main__': unittest.main(verbosity=2)
