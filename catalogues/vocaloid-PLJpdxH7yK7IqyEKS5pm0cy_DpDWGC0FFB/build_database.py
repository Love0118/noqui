"""Build the local, browsable SQLite catalogue from the reviewed JSON snapshot."""
import json
from pathlib import Path
import sqlite3
import tempfile
import time
from contextlib import closing


ROOT = Path(__file__).resolve().parent
ISSUE_LABELS = {
    'No title/producer evidence supplied': '제목·작가 확인 출처 부족',
    'Selected title does not appear in the matched evidence quote': '선택한 제목의 출처 문구 확인 필요',
    'Korean usage of the retained international title is not established': '통용 한국어 제목 확인 필요',
    'Original songwriter/producer is unresolved': '원곡 작가 확인 필요',
    'Metadata does not satisfy the current Noqui catalogue API constraints': '현재 곡 등록 형식에 맞는 이름 확인 필요',
    'Medley original songwriter credits have not been fully verified': '메들리 원곡별 작가 확인 필요',
    'Evidence quote/source could not be matched against retrieved pages': '출처와 확인 문구 대조 필요',
    'Evidence URL is search navigation or a homepage rather than a source article': '실제 원문 출처 확인 필요',
    'Evidence is missing its source URL or quote': '출처 주소 또는 확인 문구 누락',
    'Producer is a placeholder rather than a verified credit': '실제 작가명 확인 필요',
}


def populate(connection, catalogue):
    connection.executemany(
        'INSERT INTO catalogue_info VALUES (?, ?)',
        [(key, json.dumps(value, ensure_ascii=False)) for key, value in catalogue.items() if key != 'songs'],
    )
    for song in catalogue['songs']:
        song_id = song['id']
        connection.execute(
            'INSERT INTO songs VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)',
            (song_id, song['index'], song['videoId'], song['title'], song.get('sourceTitle'),
             song.get('channel'), song.get('durationSeconds'), song['youtubeUrl'],
             song['confidence'], int(song['reviewRequired']), song.get('notes', '')),
        )
        for position, writer in enumerate(song.get('writers', song['tags'])):
            connection.execute('INSERT INTO writers(name) VALUES (?) ON CONFLICT(name) DO NOTHING', (writer,))
            writer_id = connection.execute('SELECT id FROM writers WHERE name = ?', (writer,)).fetchone()[0]
            connection.execute('INSERT INTO song_writers VALUES (?, ?, ?)', (song_id, writer_id, position))
        for position, tag in enumerate(song['tags']):
            connection.execute('INSERT INTO song_tags VALUES (?, ?, ?)', (song_id, position, tag))
        for position, alias in enumerate(song.get('aliases', [])):
            connection.execute('INSERT INTO aliases VALUES (?, ?, ?, ?, ?, ?, ?)',
                               (song_id, position, alias['kind'], alias['name'], alias.get('writer'), alias.get('url'), alias['quote']))
        audio = song['audio']
        connection.execute(
            'INSERT INTO audio VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)',
            (song_id, audio['status'], audio.get('path'), audio.get('format'), audio.get('codec'),
             audio.get('nominalBitrate'), audio.get('channels'), audio.get('durationSeconds'),
             audio.get('byteLength'), audio.get('sha256'), audio.get('stage'),
             audio.get('failureCode'), audio.get('reason')),
        )
        for position, source in enumerate(song['evidence']):
            connection.execute(
                'INSERT INTO evidence VALUES (?, ?, ?, ?, ?, ?, ?, ?)',
                (song_id, position, source['url'], source['quote'], source['kind'],
                 int(source.get('retrievedEvidenceMatched', False)), source.get('retrievalUrl'),
                 source.get('retrievedAt') or source.get('checkedAt')),
            )
        for position, message in enumerate(song['validationWarnings']):
            connection.execute(
                'INSERT INTO review_issues VALUES (?, ?, ?, ?)',
                (song_id, position, message, ISSUE_LABELS.get(message, message)),
            )


def verify_database(connection, catalogue):
    songs = catalogue['songs']
    expected = {
        'songs': len(songs),
        'audio': len(songs),
        'writers': len({tag for song in songs for tag in song.get('writers', song['tags'])}),
        'song_writers': sum(len(song.get('writers', song['tags'])) for song in songs),
        'song_tags': sum(len(song['tags']) for song in songs),
        'aliases': sum(len(song.get('aliases', [])) for song in songs),
        'evidence': sum(len(song['evidence']) for song in songs),
        'review_issues': sum(len(song['validationWarnings']) for song in songs),
        '전체곡': len(songs),
        '검수완료곡': sum(not song['reviewRequired'] for song in songs),
        '검토필요곡': sum(song['reviewRequired'] for song in songs),
        '다운로드불가곡': sum(song['audio']['status'] == 'failed' for song in songs),
    }
    for name, count in expected.items():
        actual = connection.execute(f'SELECT COUNT(*) FROM "{name}"').fetchone()[0]
        if actual != count:
            raise ValueError(f'{name}: expected {count}, got {actual}')
    for song in songs:
        actual_tags = [row[0] for row in connection.execute(
            'SELECT w.name FROM song_writers sw JOIN writers w ON w.id = sw.writer_id '
            'WHERE sw.song_id = ? ORDER BY sw.tag_order', (song['id'],),
        )]
        if actual_tags != song.get('writers', song['tags']):
            raise ValueError(f'Writer order or identity changed: {song["videoId"]}')
    if connection.execute('PRAGMA integrity_check').fetchall() != [('ok',)]:
        raise ValueError('SQLite integrity check failed')
    if connection.execute('PRAGMA foreign_key_check').fetchall():
        raise ValueError('SQLite foreign key check failed')
    return expected


def build_database(source=ROOT / 'catalogue.json', destination=ROOT / 'catalogue.sqlite3'):
    source, destination = Path(source), Path(destination)
    if source.resolve() == destination.resolve():
        raise ValueError('Database output cannot overwrite its JSON source')
    catalogue = json.loads(source.read_text(encoding='utf-8'))
    # Publish only a fully populated, verified database; a failed build leaves the old DB intact.
    with tempfile.NamedTemporaryFile(prefix='.catalogue-', suffix='.sqlite3', dir=destination.parent, delete=False) as handle:
        temporary = Path(handle.name)
    try:
        connection = sqlite3.connect(temporary)
        try:
            connection.executescript((ROOT / 'schema.sql').read_text(encoding='utf-8'))
            with connection:
                populate(connection, catalogue)
                counts = verify_database(connection, catalogue)
            connection.execute('VACUUM')
        finally:
            connection.close()
        try:
            temporary.replace(destination)
        except PermissionError:
            # Windows viewers may deny file replacement while still allowing SQLite writes.
            # SQLite backup updates an open destination atomically, preserving existing readers.
            deadline = time.monotonic() + 10
            def progress(status, remaining, total):
                if time.monotonic() > deadline:
                    raise TimeoutError('Catalogue database is locked by another application')
            with closing(sqlite3.connect(temporary)) as prepared, closing(sqlite3.connect(destination, timeout=2)) as existing:
                prepared.backup(existing, pages=256, progress=progress, sleep=.1)
            temporary.unlink()
        return counts
    finally:
        temporary.unlink(missing_ok=True)


if __name__ == '__main__':
    print(json.dumps(build_database(), ensure_ascii=False))
