import copy
import json
from pathlib import Path
import tempfile
import unittest

from update_playlist_metadata import prepare


class InstalledNamingUpdateTest(unittest.TestCase):
    def files(self,root):
        url='https://www.youtube.com/watch?v=BaW_jenozKc'
        song={'id':'record','index':1,'youtubeUrl':url,'title':'검증곡','tags':['작가','Original'],
              'reviewRequired':False,'evidence':[{'url':'https://example.com/song','quote':'검증곡','retrievedEvidenceMatched':True}],
              'audio':{'status':'ready','durationSeconds':240}}
        held={**copy.deepcopy(song),'id':'held','index':2,'title':'Unresolved'}
        catalogue={'playlistId':'list','songs':[song,held]}
        audio={'path':'audio/BaW_jenozKc.ogg','sha256':'a'*64,'byteLength':1234567,'durationMs':240000}
        installed={'schemaVersion':1,'id':'list','name':'목록','songs':[
            {'song':{**{k:song[k] for k in ['id','youtubeUrl','title','tags']},'title':'Original'},'audio':audio},
            {'song':{k:held[k] for k in ['id','youtubeUrl','title','tags']},'audio':audio},
        ]}
        source=root/'catalogue.json';old=root/'installed.json';out=root/'proposed.json'
        source.write_text(json.dumps(catalogue),encoding='utf-8');old.write_text(json.dumps(installed),encoding='utf-8')
        return source,old,out,catalogue,installed

    def test_names_change_with_identical_audio_and_counted_naming_holds(self):
        with tempfile.TemporaryDirectory() as directory:
            source,old,out,_,installed=self.files(Path(directory));before=old.read_bytes();result=prepare(source,old,out)
            after=json.loads(out.read_text(encoding='utf-8'))
            self.assertEqual(before,old.read_bytes());self.assertEqual(installed['songs'][0]['audio'],after['songs'][0]['audio'])
            self.assertEqual('검증곡',after['songs'][0]['song']['title']);self.assertEqual(2,after['excludedSongs'][0]['index'])
            self.assertEqual((2,1,1),(result['tracks'],result['playable'],result['reviewRequired']))

    def test_recording_changes_cannot_be_smuggled_into_a_naming_update(self):
        for change in ['url','duration','id','count']:
            with self.subTest(change=change),tempfile.TemporaryDirectory() as directory:
                source,old,out,data,_=self.files(Path(directory))
                if change=='url':data['songs'][0]['youtubeUrl']='https://www.youtube.com/watch?v=jNQXAC9IVRw'
                if change=='duration':data['songs'][0]['audio']['durationSeconds']=120
                if change=='id':data['songs'][0]['id']='different'
                if change=='count':data['songs'].pop()
                source.write_text(json.dumps(data),encoding='utf-8')
                with self.assertRaises(ValueError):prepare(source,old,out)
                self.assertFalse(out.exists())

    def test_original_files_cannot_be_overwritten_by_proposals(self):
        with tempfile.TemporaryDirectory() as directory:
            source,old,_,_,_=self.files(Path(directory))
            for destination in [source,old]:
                with self.assertRaisesRegex(ValueError,'separate file'):prepare(source,old,destination)


if __name__=='__main__':unittest.main()
