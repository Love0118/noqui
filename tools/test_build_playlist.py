import json
from pathlib import Path
import tempfile
import unittest
from build_playlist import build


class RecordingSourceGateTest(unittest.TestCase):
    def test_unverified_video_audio_remains_counted_without_being_exported(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'catalogue.json'
            source.write_text(json.dumps({'songs': [{
                'index': 1, 'id': 'record', 'youtubeUrl': 'https://www.youtube.com/watch?v=BaW_jenozKc',
                'videoId': 'BaW_jenozKc', 'title': 'Song', 'tags': ['Writer'],
                'reviewRequired': False, 'audioSourceReviewRequired': True,
                'audio': {'status': 'ready', 'path': 'audio/BaW_jenozKc.ogg'},
            }]}), encoding='utf-8')
            result = build(source, root / 'export', 'source-review', 'Source review', 'must-not-run')
            self.assertEqual(1, result['tracks'])
            self.assertEqual(0, result['playable'])
            manifest = json.loads((root / 'export/playlist.json').read_text(encoding='utf-8'))
            self.assertIsNone(manifest['songs'][0]['audio'])
            self.assertEqual([], list((root / 'export/audio').iterdir()))


if __name__ == '__main__':
    unittest.main()
