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
from model_geometry import rotated_point

class PackTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.original=build.PACK
        build.PACK=Path(self.tmp.name)/'pack';shutil.copytree(self.original,build.PACK)
        self.original_catalog=build.CATALOG;self.original_provenance=build.PROVENANCE
        build.CATALOG=Path(self.tmp.name)/'lobby-cosmetics.json';shutil.copyfile(self.original_catalog,build.CATALOG)
        build.PROVENANCE=Path(self.tmp.name)/'cosmetics-provenance.json';shutil.copyfile(self.original_provenance,build.PROVENANCE)
        self.original_native=build.NATIVE;build.NATIVE=Path(self.tmp.name)/'native-equipment.json';shutil.copyfile(self.original_native,build.NATIVE)
    def tearDown(self):
        build.PACK=self.original;build.CATALOG=self.original_catalog;build.PROVENANCE=self.original_provenance;build.NATIVE=self.original_native;self.tmp.cleanup()
    def edit(self,relative,fn):
        p=build.PACK/relative;v=json.loads(p.read_text());fn(v);p.write_text(json.dumps(v))
    def test_references_skin_sound_provenance_and_determinism(self):
        checks=build.validate();self.assertEqual(checks['models'],len(build.item_ids()));self.assertEqual(checks['sounds'],5)
        self.assertEqual(checks['cosmetics'],len(build.catalog()));self.assertEqual(checks['gadgets'],5)
        self.assertEqual(checks['uniqueCosmeticGeometry'],len(build.catalog()))
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

    def add_cosmetic_fixture(self):
        """A genuine existing MIT source exercises the extensible import boundary."""
        name='fixture_bag';model=build.read_json(build.PACK/'assets/noqui/models/backpack.json')
        model['elements'][0]['from'][0]-=.2
        path=build.PACK/f'assets/noqui/models/{name}.json';path.write_text(json.dumps(model),encoding='utf-8')
        (build.PACK/f'assets/noqui/items/{name}.json').write_text(json.dumps({'model':{'type':'minecraft:model','model':f'noqui:{name}'}}),encoding='utf-8')
        catalog=build.read_json(build.CATALOG);catalog['cosmetics'].append({'id':name,'label':'Fixture bag','slot':'back'})
        build.CATALOG.write_text(json.dumps(catalog),encoding='utf-8')
        source=build.read_json(build.ROOT/'asset-provenance.json')['backpack']
        provenance=build.read_json(build.PROVENANCE)
        provenance['sources'].append({'id':'fixture_source','title':'MIT backpack test fixture','author':source['copyrightNotice'],
            'sourceUrl':source['model'],'license':'MIT','licenseUrl':source['licenseUrl'],
            'licenseFile':'vendor/jashduck/LICENSE','packLicenseFile':'licenses/fixture.txt',
            'sourceFiles':[{'path':relative,'sha256':build.digest((build.ROOT/relative).read_bytes())}
                           for relative in ('vendor/jashduck/backpack.bbmodel','vendor/jashduck/LICENSE')],
            'modifications':'Fixture widens the original bag by 0.2 model units.'})
        provenance['cosmetics'].append({'id':name,'sourceIds':['fixture_source'],'geometry':'derived','modifications':'Fixture width change.',
            'modelSha256':build.digest(path.read_bytes()),'texturePaths':['assets/noqui/textures/item/backpack.png']})
        build.PROVENANCE.write_text(json.dumps(provenance),encoding='utf-8')
        (build.PACK/'COSMETICS_SOURCES.json').write_bytes(build.PROVENANCE.read_bytes())
        (build.PACK/'licenses').mkdir(exist_ok=True)
        shutil.copyfile(build.ROOT/'vendor/jashduck/LICENSE',build.PACK/'licenses/fixture.txt')
        with (build.PACK/'ASSET_NOTICES.txt').open('a',encoding='utf-8') as out:out.write('\n'+source['model']+'\n'+source['copyrightNotice']+'\nMIT\n')
        native=build.read_json(build.NATIVE)
        native['backs'].append({'id':name,'faces':42,'sourceModelSha256':build.digest(path.read_bytes())})
        for prefix in ('wearable_back','wearable_back_gravity'):
            relative=f'assets/noqui/equipment/{prefix}/{name}.json'
            target=build.PACK/relative;shutil.copyfile(build.PACK/f'assets/noqui/equipment/{prefix}/backpack.json',target)
            native['files'][relative]=build.digest(target.read_bytes())
        build.NATIVE.write_text(json.dumps(native),encoding='utf-8')
        return name

    def test_native_head_selection_covers_every_head_and_face(self):
        self.edit('assets/noqui/items/wearable_head.json',lambda value:value['model']['models'][0]['cases'].pop())
        with self.assertRaises(AssertionError):build.validate()

    def test_native_gadget_equipment_texture_is_required(self):
        (build.PACK/'assets/noqui/textures/entity/equipment/humanoid/triple_boots.png').unlink()
        with self.assertRaises((AssertionError,FileNotFoundError)):build.validate()

    def test_native_back_shader_and_gravity_layers_are_pinned(self):
        path=build.PACK/'assets/minecraft/shaders/core/entity.vsh';path.write_bytes(path.read_bytes()+b'\nchanged')
        with self.assertRaises(AssertionError):build.validate()

    def test_unneeded_sodium_terrain_overrides_are_rejected(self):
        (build.PACK/'assets/minecraft/shaders/core/terrain.vsh').write_text('#version 330\n')
        with self.assertRaises(AssertionError):build.validate()

    def test_vanilla_shader_include_overrides_are_rejected(self):
        (build.PACK/'assets/minecraft/shaders/include/light.glsl').write_text('// incompatible override\n')
        with self.assertRaises(AssertionError):build.validate()

    def test_catalog_drives_models_textures_and_provenance_without_fixed_count(self):
        before=build.validate();self.add_cosmetic_fixture();after=build.validate()
        self.assertEqual(after['cosmetics'],before['cosmetics']+1)
        self.assertEqual(after['models'],before['models']+1)
        self.assertEqual(after['textures'],before['textures'])

    def test_catalog_rejects_duplicate_ids_prices_and_invalid_slots(self):
        original=build.CATALOG.read_bytes()
        for edit in (lambda v:v['cosmetics'].append(v['cosmetics'][0]),
                     lambda v:v['cosmetics'][0].update(stars=200),
                     lambda v:v['cosmetics'][0].update(slot='hand')):
            data=json.loads(original);edit(data);build.CATALOG.write_text(json.dumps(data),encoding='utf-8')
            with self.assertRaises(AssertionError):build.validate()

    def test_existing_catalog_identity_is_preserved(self):
        data=build.read_json(build.CATALOG);data['cosmetics'][0]['slot']='face';build.CATALOG.write_text(json.dumps(data),encoding='utf-8')
        with self.assertRaisesRegex(AssertionError,'identity'):build.validate()

    def test_added_cosmetic_requires_provenance(self):
        data=build.read_json(build.CATALOG);data['cosmetics'].append({'id':'unlicensed','label':'Missing source','slot':'head'})
        build.CATALOG.write_text(json.dumps(data),encoding='utf-8')
        with self.assertRaisesRegex(AssertionError,'needs provenance'):build.validate()

    def test_license_and_source_hashes_are_verified(self):
        self.add_cosmetic_fixture();(build.PACK/'licenses/fixture.txt').write_text('not the source license',encoding='utf-8')
        with self.assertRaisesRegex(AssertionError,'License bytes'):build.validate()

    def test_model_hash_is_pinned_to_provenance(self):
        name=self.add_cosmetic_fixture()
        self.edit(f'assets/noqui/models/{name}.json',lambda v:v['elements'][0]['from'].__setitem__(0,1))
        with self.assertRaisesRegex(AssertionError,'Model differs'):build.validate()

    def test_changed_source_evidence_is_rejected(self):
        self.add_cosmetic_fixture();data=build.read_json(build.PROVENANCE);data['sources'][-1]['sourceFiles'][0]['sha256']='0'*64
        build.PROVENANCE.write_text(json.dumps(data),encoding='utf-8');(build.PACK/'COSMETICS_SOURCES.json').write_bytes(build.PROVENANCE.read_bytes())
        with self.assertRaisesRegex(AssertionError,'Source hash differs'):build.validate()

    def test_original_adaptation_requires_explicit_approval(self):
        self.add_cosmetic_fixture();data=build.read_json(build.PROVENANCE);data['cosmetics'][-1]['geometry']='original-adaptation'
        build.PROVENANCE.write_text(json.dumps(data),encoding='utf-8');(build.PACK/'COSMETICS_SOURCES.json').write_bytes(build.PROVENANCE.read_bytes())
        with self.assertRaisesRegex(AssertionError,'parent approval'):build.validate()

    def test_recolor_duplicate_geometry_is_rejected(self):
        crown=(build.PACK/'assets/noqui/models/crown.json').read_bytes()
        (build.PACK/'assets/noqui/models/cap.json').write_bytes(crown)
        with self.assertRaisesRegex(AssertionError,'Duplicate cosmetic geometry'):build.validate()

    def test_geometry_signature_ignores_uniform_scale_translation_and_element_order(self):
        model=build.read_json(build.PACK/'assets/noqui/models/crown.json');signature=build.geometry_signature(model)
        for element in model['elements']:
            element['from']=[v*.5+2 for v in element['from']];element['to']=[v*.5+2 for v in element['to']]
        model['elements'].reverse();self.assertEqual(signature,build.geometry_signature(model))

    def test_flat_icon_geometry_is_rejected(self):
        def flatten(model):
            for e in model['elements']:e['from'][2]=8;e['to'][2]=8.01
        self.edit('assets/noqui/models/cap.json',flatten)
        with self.assertRaisesRegex(AssertionError,'real depth'):build.validate()

    def test_texture_aliases_resolve_and_cycles_are_rejected(self):
        model={'textures':{'0':'#base','base':'noqui:item/palette'}}
        self.assertEqual(build.texture_path(model,'#0'),'assets/noqui/textures/item/palette.png')
        model['textures']['base']='#0'
        with self.assertRaisesRegex(AssertionError,'alias cycle'):build.texture_path(model,'#0')

    def test_unreferenced_texture_is_rejected(self):
        shutil.copyfile(build.PACK/'assets/noqui/textures/item/palette.png',build.PACK/'assets/noqui/textures/item/unused.png')
        with self.assertRaises(AssertionError):build.validate()

    def test_native_rotations_use_xyz_order_and_legacy_notation_precedence(self):
        element={'rotation':{'origin':[8,8,8],'x':90,'y':90,'z':0}}
        self.assertTrue(all(abs(a-b)<1e-8 for a,b in zip(rotated_point(element,[8,9,8]),[9,8,8])))
        element['rotation'].update(axis='z',angle=90)
        self.assertTrue(all(abs(a-b)<1e-8 for a,b in zip(rotated_point(element,[8,9,8]),[7,8,8])))

    def test_geometry_signature_includes_element_rotations(self):
        model={'elements':[{'from':[0,0,0],'to':[1,2,3]}]};original=build.geometry_signature(model)
        model['elements'][0]['rotation']={'origin':[0,0,0],'axis':'y','angle':22.5}
        self.assertNotEqual(original,build.geometry_signature(model))

    def test_all_planar_source_model_is_not_counted_as_solid_cosmetic(self):
        def flatten(model):
            for i,e in enumerate(model['elements']):e['from'][i%3]=e['to'][i%3]
        self.edit('assets/noqui/models/cap.json',flatten)
        with self.assertRaisesRegex(AssertionError,'nonzero-volume cuboids'):build.validate()

if __name__=='__main__':unittest.main(verbosity=2)
