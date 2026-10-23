"""Stdlib-only pack boundary/regression tests; never mutates committed assets."""
from pathlib import Path
import json
import io
import shutil
import struct
import tempfile
import unittest
import zipfile
import zlib
import ast
import hashlib
import build_pack as build

class PackTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.original=build.PACK
        build.PACK=Path(self.tmp.name)/'pack';shutil.copytree(self.original,build.PACK)
    def tearDown(self):
        build.PACK=self.original;self.tmp.cleanup()
    def edit(self,relative,fn):
        p=build.PACK/relative;v=json.loads(p.read_text());fn(v);p.write_text(json.dumps(v))
    def test_references_skin_sound_provenance_and_determinism(self):
        checks=build.validate();self.assertEqual(checks['models'],11);self.assertEqual(checks['sounds'],5)
        self.assertEqual(build.archive_bytes(),build.archive_bytes())
        with zipfile.ZipFile(io.BytesIO(build.archive_bytes())) as archive:
            self.assertEqual(archive.namelist(),sorted(archive.namelist()))
            self.assertTrue(all(info.date_time==(1980,1,1,0,0,0) and info.compress_type==zipfile.ZIP_STORED and info.create_system==3 for info in archive.infolist()))
    def test_broken_model_reference(self):
        self.edit('assets/noqui/items/crown.json',lambda v:v['model'].update(model='noqui:absent'))
        with self.assertRaises(AssertionError):build.validate()
    def test_out_of_bounds_uv(self):
        self.edit('assets/noqui/models/cap.json',lambda v:v['elements'][0]['faces']['north'].update(uv=[0,0,17,4]))
        with self.assertRaises(AssertionError):build.validate()
    def test_hidden_archive_is_rejected(self):
        (build.PACK/'original.zip').write_bytes(b'PK fake')
        with self.assertRaises(AssertionError):build.validate()
    def test_wrong_pack_version(self):
        self.edit('pack.mcmeta',lambda v:v['pack'].update(min_format=[98,0]))
        with self.assertRaises(AssertionError):build.validate()
    def test_changed_original_sound_is_rejected(self):
        p=build.PACK/'assets/minecraft/sounds/arsf/0win.ogg';p.write_bytes(p.read_bytes()+b'changed')
        with self.assertRaises(AssertionError):build.validate()
    def test_extra_sound_event_is_rejected(self):
        self.edit('assets/minecraft/sounds.json',lambda v:v.update(unrequested={'sounds':['music/private']}))
        with self.assertRaises(AssertionError):build.validate()
    def test_skin_crc_is_verified(self):
        p=build.PACK/'assets/noqui/textures/entity/shopkeeper.png';data=p.read_bytes();p.write_bytes(data[:-1]+bytes([data[-1]^1]))
        with self.assertRaises(AssertionError):build.validate()
    def test_web_skin_bytes_and_truthful_source_notice_are_pinned(self):
        provenance=json.loads((build.ROOT/'vendor/munasharna/provenance.json').read_text(encoding='utf-8'))
        self.assertEqual(provenance['model'],'slim')
        self.assertEqual(build.digest((build.PACK/'assets/noqui/textures/entity/shopkeeper.png').read_bytes()),provenance['sha256'])
        self.assertIn('No explicit Creative Commons',(build.PACK/'SKIN_SOURCE_NOTICE.txt').read_text(encoding='utf-8'))
        self.assertIn('https://www.minecraftskins.com/uploads/skins/',provenance['sourceUrl'])
        self.assertFalse(provenance['license']['openLicenseAsserted'])
        self.assertIsNone(provenance['license']['spdx'])
    def test_authoring_tool_preserves_downloaded_skin_instead_of_generating_one(self):
        # The full authoring workflow would touch cosmetics/sounds; isolate only its skin-copy step.
        tree=ast.parse((build.ROOT/'author_assets.py').read_text(encoding='utf-8'))
        function=next(n for n in tree.body if isinstance(n,ast.FunctionDef) and n.name=='skin')
        # Execute the actual copy-only function without importing optional Pillow/NumPy authoring tools.
        namespace={'ROOT':build.ROOT,'PACK':build.PACK,'json':json,'hashlib':hashlib}
        exec(compile(ast.Module(body=[function],type_ignores=[]),'author_assets.py','exec'),namespace)
        (build.PACK/'assets/noqui/textures/entity/shopkeeper.png').write_bytes(b'obsolete')
        namespace['skin']()
        self.assertEqual((build.PACK/'assets/noqui/textures/entity/shopkeeper.png').read_bytes(),(build.ROOT/'vendor/munasharna/purple-hair-girl-in-tuxedo.png').read_bytes())
    def test_non_slim_opaque_arm_columns_are_rejected(self):
        def chunk(kind,data):return struct.pack('>I',len(data))+kind+data+struct.pack('>I',zlib.crc32(kind+data)&0xffffffff)
        header=struct.pack('>IIBBBBB',64,64,8,6,0,0,0)
        data=b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',header)+chunk(b'IDAT',zlib.compress((b'\0'+b'\xff'*256)*64))+chunk(b'IEND',b'')
        (build.PACK/'assets/noqui/textures/entity/shopkeeper.png').write_bytes(data)
        with self.assertRaisesRegex(AssertionError,'3pixels'):build.validate()

if __name__=='__main__':unittest.main(verbosity=2)
