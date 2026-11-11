import json
from contextlib import closing
from pathlib import Path
import sys
import tempfile
import unittest
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_tj_new_playlist as builder


class BuildTJNewPlaylistTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.scratch = Path(temporary.name)

    def official(self, *, word='Composer', pro=12345, index=1, com='Composer'):
        return {
            'index': index, 'pro': pro, 'indexTitle': 'Hoshi', 'indexSong': 'Artist',
            'word': word, 'com': com, 'publishdate': '2026-11-01',
            'categoryUrl': 'https://www.tjmedia.com/song/song_view.asp?곡=1', 'categoryVerified': True,
        }

    def research(self, **overrides):
        row = {
            'index': 1, 'tjNumber': 12345, 'title': '푸른 별', 'originalTitle': 'Hoshi',
            'singer': 'Artist', 'singerAliases': [], 'writers': ['legacy value'],
            'writerAliases': [{'name': '작곡가', 'canonicalName': 'Composer', 'url': 'https://example.com/credit',
                               'quote': '작곡가 Composer', 'retrievedEvidenceMatched': True}],
            'titleAliases': [], 'tags': [], 'aliases': [],
            'evidence': [{'url': 'https://example.com/song', 'quote': '푸른 별 (Hoshi) by Artist',
                          'kind': 'page', 'retrievedEvidenceMatched': True}],
            'reviewRequired': False, 'reviewReasons': [], 'namingReviewRequired': False,
            'namingReviewReasons': [], 'searches': [], 'videoId': 'abcdefghijk',
            'youtubeUrl': 'https://www.youtube.com/watch?v=abcdefghijk', 'audioSourceType': 'official_album_audio',
            'audioEvidence': [{'url': 'https://www.youtube.com/watch?v=abcdefghijk', 'quote': 'official audio',
                               'retrievedEvidenceMatched': True}],
        }
        row.update(overrides)
        return row

    def test_literal_title_requires_matched_song_and_artist_evidence(self):
        row = self.research()
        self.assertTrue(builder.title_evidence_valid(row, '푸른 별'))
        self.assertFalse(builder.title_evidence_valid(row, '푸른빛'))
        row['evidence'][0]['quote'] = '푸른 별 by Someone Else'
        self.assertFalse(builder.title_evidence_valid(row, '푸른 별'))
        row['evidence'][0]['retrievedEvidenceMatched'] = False
        self.assertFalse(builder.title_evidence_valid(row, '푸른 별'))

    def test_only_authorized_english_original_can_use_pronunciation_fallback(self):
        fallback = {'authorized': True, 'origin': 'user_authorized_english_pronunciation',
                    'title': '호시', 'searches': ['Hoshi Korean title search']}
        english = self.research(title='Hoshi', englishPronunciationFallback=fallback)
        approved = builder.build_song(self.official(), english, self.scratch)
        self.assertEqual(approved['titleOrigin'], 'user_authorized_english_pronunciation')
        japanese = self.research(originalTitle='星', title='Hoshi', englishPronunciationFallback=fallback)
        held = builder.build_song(self.official(index=1), japanese, self.scratch)
        self.assertTrue(held['reviewRequired'])
        self.assertTrue(any('literally verified' in reason for reason in held['reviewReasons']))

    def test_sourced_japanese_phonetic_common_title_clears_naming_only_hold(self):
        official = self.official()
        official.update({'indexTitle': 'ラグトレイン', 'indexSong': 'Artist'})
        row = self.research(
            title='래그트레인', originalTitle='ラグトレイン',
            namingReviewRequired=True, namingReviewReasons=['한국어 통용 발음 확인이 필요합니다.'],
            evidence=[{'url': 'https://example.com/song',
                       'quote': '래그트레인 (ラグトレイン) by Artist', 'kind': 'page',
                       'retrievedEvidenceMatched': True}],
        )
        song = builder.build_song(official, row, self.scratch)
        self.assertFalse(song['reviewRequired'])
        self.assertEqual(song['title'], '래그트레인')

    def test_old_pronunciation_alias_cannot_replace_actual_use_evidence(self):
        official = self.official()
        official.update({'indexTitle': 'ラグトレイン', 'indexSong': 'Artist'})
        row = self.research(
            title='래그트레인', originalTitle='ラグトレイン',
            aliases=[{'kind': 'title', 'name': '래그트레인',
                      'origin': 'user_authorized_pronunciation', 'language': 'ja'}],
            evidence=[{'url': 'https://example.com/song', 'quote': 'ラグトレイン by Artist',
                       'kind': 'page', 'retrievedEvidenceMatched': True}],
        )
        song = builder.build_song(official, row, self.scratch)
        self.assertTrue(song['reviewRequired'])
        self.assertTrue(any('literally verified' in reason for reason in song['reviewReasons']))

    def test_raw_tj_credits_are_authoritative_and_audio_absence_is_not_identity_hold(self):
        song = builder.build_song(self.official(com='작곡가、作詞家'), self.research(), self.scratch)
        self.assertEqual(song['writers'], ['작곡가', '作詞家', 'Composer'])
        self.assertEqual(song['audio']['status'], 'pending')
        self.assertTrue(song['reviewRequired'])
        self.assertTrue(any('mapping is missing' in reason for reason in song['reviewReasons']))
        self.assertFalse(any('Audio is pending' in reason for reason in song['reviewReasons']))

    def test_missing_audio_keeps_otherwise_reviewed_song_publishable(self):
        song = builder.build_song(self.official(), self.research(), self.scratch)
        self.assertFalse(song['reviewRequired'])
        self.assertEqual(song['audio']['status'], 'pending')
        self.assertIsNone(song['audio']['path'])

    def test_unverified_subtitle_audio_does_not_reopen_a_sourced_korean_title(self):
        row = self.research(reviewRequired=True,
                            reviewReasons=['A clean original subtitle recording needs audio verification.'])
        song = builder.build_song(self.official(), row, self.scratch)
        self.assertTrue(song['reviewRequired'])
        self.assertFalse(song['namingReviewRequired'])

    def test_wrong_official_number_holds_instead_of_index_backfill(self):
        song = builder.build_song(self.official(pro=99999), self.research(), self.scratch)
        self.assertTrue(song['reviewRequired'])
        self.assertTrue(any('TJ number does not match' in reason for reason in song['reviewReasons']))
        self.assertEqual(song['id'], str(uuid.uuid5(builder.NAMESPACE, f'{builder.PLAYLIST_ID}:12345')))

    def test_tag_cleanup_supports_kana_and_han_and_deduplicates_normalized_values(self):
        tags, reasons = builder.make_tags('星・空', ['星 空', '初音ミク', '漢字'], [])
        self.assertEqual(tags, ['星空', '初音ミク', '漢字'])
        self.assertEqual(reasons, [])

    def test_distinct_composer_and_lyricist_and_bilingual_performer_are_retained(self):
        row = self.research(
            writerAliases=[
                {'name': '작곡가', 'canonicalName': 'Composer', 'url': 'https://example.com/credit',
                 'quote': '작곡가 Composer', 'retrievedEvidenceMatched': True},
                {'name': '작사가', 'canonicalName': 'Lyricist', 'url': 'https://example.com/credit',
                 'quote': '작사가 Lyricist', 'retrievedEvidenceMatched': True}],
            aliases=[{'kind': 'singer', 'name': '가수', 'canonicalName': 'Artist',
                      'url': 'https://example.com/artist', 'quote': '가수 Artist',
                      'retrievedEvidenceMatched': True}])
        song = builder.build_song(self.official(word='Lyricist'), row, self.scratch)
        self.assertFalse(song['reviewRequired'], song['reviewReasons'])
        self.assertEqual(song['writers'], ['Composer', 'Lyricist'])
        self.assertEqual(song['tags'], ['Hoshi', 'Composer', '작곡가', 'Lyricist', '작사가', 'Artist', '가수'])

    def test_versioned_title_keeps_its_identity(self):
        official = self.official()
        official['indexTitle'] = 'YELLOW(CLOSET version)'
        row = self.research(originalTitle='YELLOW(CLOSET version)')
        self.assertEqual(builder.match_official_row(official, row), [])

    def test_large_credited_team_fits_without_dropping_the_original_title(self):
        names = [f'Creator{i}' for i in range(5)]
        aliases = [{'name': f'작가{i}', 'canonicalName': name} for i, name in enumerate(names)]
        tags, reasons = builder.make_tags('アレルギー', names, aliases, 'Artist', [{'name': '가수'}])
        self.assertEqual(len(tags), 13)
        self.assertEqual(tags[0], 'アレルギー')
        self.assertEqual(reasons, [])

    def test_primary_release_correction_preserves_raw_tj_credits(self):
        correction = {'tjNumber': 12345, 'originalTitle': 'Hoshi', 'artist': 'Artist',
                      'canonicalCredits': {'composer': 'Actual Writer', 'lyricist': 'Actual Writer'},
                      'evidence': [{'url': 'https://example.com/release', 'quote': 'Artist - Hoshi: music and lyrics Actual Writer',
                                    'sourceRole': 'official_artist_discography', 'retrievedEvidenceMatched': True}]}
        row = self.research(creditCorrection=correction, writerAliases=[
            {'name': '실제 작가', 'canonicalName': 'Actual Writer', 'url': 'https://example.com/artist',
             'quote': '실제 작가 Actual Writer', 'retrievedEvidenceMatched': True}])
        song = builder.build_song(self.official(com='Wrong Composer', word='Wrong Lyricist'), row, self.scratch)
        self.assertFalse(song['reviewRequired'], song['reviewReasons'])
        self.assertEqual(song['writers'], ['Actual Writer'])
        self.assertEqual(song['officialCredits'], {'composer': 'Wrong Composer', 'lyricist': 'Wrong Lyricist'})
        correction['evidence'][0]['quote'] = 'Another Song: Actual Writer'
        self.assertTrue(builder.build_song(self.official(), row, self.scratch)['reviewRequired'])

    def test_unverified_creator_reading_never_becomes_an_answer_tag(self):
        row = self.research(writerAliases=[{'name': '미검증발음', 'canonicalName': 'Composer',
                                           'url': 'https://example.com/credit', 'quote': 'Composer',
                                           'retrievedEvidenceMatched': False}])
        song = builder.build_song(self.official(), row, self.scratch)
        self.assertTrue(song['reviewRequired'])
        self.assertNotIn('미검증발음', song['tags'])
        self.assertEqual(song['writerAliases'], [])

    def test_recording_and_credit_annotations_do_not_require_the_qualifier_in_an_answer(self):
        tags, reasons = builder.make_tags('YELLOW(CLOSET version)', ['作家(所属)'], [],
                                         'キャラ(CV:声優)', [], [], '옐로우 (CLOSET version)')
        for expected in ['YELLOW', '作家', 'キャラ', '옐로우']:
            self.assertIn(expected, tags)
        self.assertEqual(reasons, [])

    def test_database_exposes_bilingual_metadata_and_evidence(self):
        song = builder.build_song(self.official(), self.research(), self.scratch)
        catalogue = {'playlistId': builder.PLAYLIST_ID, 'name': builder.PLAYLIST_NAME, 'songs': [song]}
        path = self.scratch / 'catalogue.sqlite3'
        builder.create_database(path, catalogue)
        import sqlite3
        with closing(sqlite3.connect(path)) as db:
            row = db.execute('SELECT 한국어곡명, 원곡명, TJ번호, 작가한국어명, 작가원문명 FROM 이중언어곡목록').fetchone()
            self.assertEqual(row, ('푸른 별', 'Hoshi', 12345, '작곡가', 'Composer'))
            self.assertEqual(db.execute('SELECT COUNT(*) FROM 출처목록').fetchone()[0], 1)
        korean = self.research(writerAliases=[{'name': 'BrotherSu', 'canonicalName': '브라더수',
                                               'url': 'https://example.com/credit', 'quote': '브라더수 BrotherSu',
                                               'retrievedEvidenceMatched': True}])
        song = builder.build_song(self.official(com='브라더수', word='브라더수'), korean, self.scratch)
        catalogue['songs'] = [song]
        builder.create_database(path, catalogue)
        with closing(sqlite3.connect(path)) as db:
            self.assertEqual(db.execute('SELECT 작가한국어명,작가원문명 FROM 이중언어곡목록').fetchone(), ('브라더수', 'BrotherSu'))


if __name__ == '__main__':
    unittest.main()
