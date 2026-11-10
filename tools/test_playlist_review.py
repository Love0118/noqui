import json
import importlib.util
from contextlib import closing
from pathlib import Path
import sqlite3
import tempfile
import unittest

from build_playlist import build
from build_playlist_database import build as build_database
from playlist_review import naming_review_reasons, playable, reviewed


def song(title='검수곡', **changes):
    return {
        'index':1, 'id':'record', 'videoId':'BaW_jenozKc',
        'youtubeUrl':'https://www.youtube.com/watch?v=BaW_jenozKc',
        'title':title, 'tags':['작가'], 'writers':['Writer'], 'reviewRequired':False,
        'evidence':[{'url':'https://example.com/releases/song','quote':title,'kind':'page','retrievedEvidenceMatched':True}],
        'audio':{'status':'ready'}, **changes,
    }


class NamingCompletionTest(unittest.TestCase):
    def test_english_title_with_korean_tags_is_not_completed(self):
        record=song('Your Reality', tags=['유어 리얼리티','댄 살바토'])
        self.assertFalse(reviewed(record))
        self.assertFalse(playable(record))
        self.assertTrue(naming_review_reasons(record))

    def test_korean_title_needs_actual_usage_evidence_and_resolved_review(self):
        record=song()
        self.assertTrue(reviewed(record))
        self.assertTrue(playable(record))
        for bad in [
            {'namingReviewRequired':True},
            {'evidence':[{'url':'https://example.com/', 'quote':'검수곡','retrievedEvidenceMatched':True}]},
            {'evidence':[{'url':'https://m.search.naver.com/search.naver?query=검수곡','quote':'검수곡','retrievedEvidenceMatched':True}]},
            {'evidence':[{'url':'https://example.com/releases/song','quote':'다른 노래','retrievedEvidenceMatched':True}]},
            {'evidence':[{'url':'https://example.com/releases/song','quote':'검수곡','retrievedEvidenceMatched':False}]},
        ]:
            with self.subTest(bad=bad):self.assertFalse(reviewed({**record,**bad}))

    def test_audio_and_credit_holds_remain_independent_of_verified_name(self):
        self.assertFalse(reviewed(song(reviewRequired=True)))
        self.assertTrue(reviewed(song(audioSourceReviewRequired=True)))
        self.assertFalse(playable(song(audioSourceReviewRequired=True)))
        self.assertFalse(playable(song(audio={'status':'failed'})))

    def test_published_numeric_titles_keep_their_actual_form(self):
        for title in ['1925','2002','0+0']:
            with self.subTest(title=title):self.assertTrue(reviewed(song(title)))
        self.assertFalse(reviewed(song('0+0',evidence=[{'url':'https://example.com/song','quote':'2002','retrievedEvidenceMatched':True}])))

    def test_export_keeps_naming_holds_counted_without_encoding_them(self):
        for playlist_id in ['list','ssip-noqui']:
            with self.subTest(playlist_id=playlist_id),tempfile.TemporaryDirectory() as directory:
                root=Path(directory);source=root/'catalogue.json'
                good=song(audio={'status':'failed'});bad=song('English Fallback',id='other',index=2)
                source.write_text(json.dumps({'songs':[good,bad]}),encoding='utf-8')
                result=build(source,root/'export',playlist_id,'목록','must-not-run')
                manifest=json.loads((root/'export/playlist.json').read_text(encoding='utf-8'))
                self.assertEqual((2,1,0),(result['tracks'],result['reviewRequired'],result['playable']))
                self.assertEqual(['record'],[t['song']['id'] for t in manifest['songs']])
                self.assertEqual(2,manifest['excludedSongs'][0]['index'])

    def test_all_unreviewed_names_cannot_publish_a_bundle(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);source=root/'catalogue.json'
            source.write_text(json.dumps({'songs':[song('English Fallback')]}),encoding='utf-8')
            with self.assertRaisesRegex(ValueError,'Korean naming review'):build(source,root/'export','list','목록','must-not-run')
            self.assertFalse((root/'export').exists())

    def test_database_does_not_count_unfinished_names_as_reviewed_or_playable(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);source=root/'catalogues/example/catalogue.json';source.parent.mkdir(parents=True)
            source.write_text(json.dumps({'playlistId':'example','playlistTitle':'목록','songs':[song(),song('English Fallback',id='other',index=2)]}),encoding='utf-8')
            destination=root/'catalogue.sqlite3';build_database(root,destination)
            with closing(sqlite3.connect(destination)) as db:
                self.assertEqual((2,1,1,1),db.execute('SELECT 전체곡수,검수완료곡수,재생가능곡수,이름검토필요곡수 FROM 플레이리스트').fetchone())
                self.assertEqual(1,db.execute('SELECT COUNT(*) FROM 검토필요곡').fetchone()[0])

    def test_original_catalogue_database_uses_the_same_completion_rule(self):
        module_path=Path(__file__).resolve().parents[1]/'catalogues/vocaloid-PLJpdxH7yK7IqyEKS5pm0cy_DpDWGC0FFB/build_database.py'
        spec=importlib.util.spec_from_file_location('original_catalogue_database',module_path);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);source=root/'catalogue.json';audio={'status':'ready','path':'audio/BaW_jenozKc.ogg','byteLength':1,'sha256':'a'*64}
            good=song(confidence='high',validationWarnings=[],audio=audio);bad=song('English Fallback',id='other',index=2,videoId='jNQXAC9IVRw',youtubeUrl='https://www.youtube.com/watch?v=jNQXAC9IVRw',confidence='high',validationWarnings=[],audio=audio)
            source.write_text(json.dumps({'songs':[good,bad]}),encoding='utf-8');result=module.build_database(source,root/'catalogue.sqlite3')
            self.assertEqual((1,1),(result['검수완료곡'],result['검토필요곡']))


if __name__=='__main__':unittest.main()
