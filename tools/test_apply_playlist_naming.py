import copy
import hashlib
import json
from pathlib import Path
import tempfile
import types
import unittest
from unittest.mock import patch

from apply_playlist_naming import apply


class SharedRecordingNamingTest(unittest.TestCase):
    def prepare(self, root):
        path=root/'audio/BaW_jenozKc.ogg';path.parent.mkdir();path.write_bytes(b'recording')
        row={'id':'first','index':1,'videoId':'BaW_jenozKc','youtubeUrl':'https://www.youtube.com/watch?v=BaW_jenozKc',
             'title':'Original','tags':['Writer'],'writers':['Writer'],'reviewRequired':False,
             'evidence':[{'url':'https://example.com/song','quote':'Original','retrievedEvidenceMatched':True}],
             'audio':{'status':'ready','path':'audio/BaW_jenozKc.ogg','byteLength':len(b'recording'),'sha256':hashlib.sha256(b'recording').hexdigest()}}
        second=copy.deepcopy(row);second.update(id='second',index=2)
        source=root/'catalogue.json';source.write_text(json.dumps({'playlistId':'list','songs':[row,second]}),encoding='utf-8')
        proposed=json.loads(source.read_text(encoding='utf-8'))
        for s in proposed['songs']:
            s.update(title='검증곡',tags=['작가'])
            s['evidence'].append({'url':'https://example.com/korean-song','quote':'검증곡 Original','retrievedEvidenceMatched':True})
        return source,path,proposed

    def codec(self,opened):
        class FakeOpus(dict):
            def __init__(self,path):super().__init__();self.path=path;opened.append(path)
            def save(self):self.path.write_bytes(b'recording-with-updated-comments')
        return patch.dict('sys.modules',{'mutagen':types.ModuleType('mutagen'),'mutagen.oggopus':types.SimpleNamespace(OggOpus=FakeOpus)})

    def test_shared_recording_is_retagged_once_and_all_references_get_new_hash(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);source,path,proposed=self.prepare(root);corrections=root/'proposed.json';corrections.write_text(json.dumps(proposed),encoding='utf-8');opened=[]
            with self.codec(opened):result=apply(source,corrections)
            rows=json.loads(source.read_text(encoding='utf-8'))['songs']
            self.assertEqual(2,result['changed']);self.assertEqual([path],opened)
            for row in rows:
                self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(),row['audio']['sha256'])
                self.assertEqual(path.stat().st_size,row['audio']['byteLength'])
                self.assertEqual('Original',row['originalTitle'])

    def test_conflicting_names_for_shared_audio_fail_before_any_file_mutation(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);source,path,proposed=self.prepare(root);proposed['songs'][1]['tags']=['다른작가'];corrections=root/'proposed.json';corrections.write_text(json.dumps(proposed),encoding='utf-8');before=source.read_bytes();opened=[]
            with self.codec(opened),self.assertRaisesRegex(ValueError,'Shared recording'):apply(source,corrections)
            self.assertEqual([],opened);self.assertEqual(b'recording',path.read_bytes());self.assertEqual(before,source.read_bytes())

    def test_english_replacement_does_not_pass_as_a_completed_korean_name(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);source,path,proposed=self.prepare(root)
            for row in proposed['songs']:
                row['title']='English fallback';row['evidence'].append({'url':'https://example.com/fallback','quote':'English fallback','retrievedEvidenceMatched':True})
            corrections=root/'proposed.json';corrections.write_text(json.dumps(proposed),encoding='utf-8');opened=[]
            with self.codec(opened),self.assertRaisesRegex(ValueError,'Korean display title'):apply(source,corrections)
            self.assertEqual([],opened);self.assertEqual(b'recording',path.read_bytes())


if __name__=='__main__':unittest.main()
