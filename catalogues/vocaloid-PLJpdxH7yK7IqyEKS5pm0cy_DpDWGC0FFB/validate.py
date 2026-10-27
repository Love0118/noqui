"""Validate this playlist catalogue and optional local audio, using Python's standard library."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import unicodedata
from urllib.parse import unquote, urlparse


ROOT = Path(__file__).resolve().parent


def normalized(value):
    return ''.join(c.casefold() for c in unicodedata.normalize('NFKC', value) if c.isalnum())


def check(condition, message):
    if not condition:
        raise ValueError(message)

def is_article(url):
    parsed = urlparse(url)
    host = parsed.hostname or ''
    path = unquote(parsed.path)
    return (parsed.scheme in {'http', 'https'} and bool(host)
            and path not in {'', '/'} and '나무위키:대문' not in path
            and not any(part in host for part in ('search.naver.com', 'search.shopping.naver.com',
                        'msearch.shopping.naver.com', 'mkt.naver.com')))


def opus_comments(path):
    """Read the two Opus header packets and final granule without decoding audio."""
    headers = []
    packet = bytearray()
    final_granule = 0
    serial = None
    with path.open('rb') as stream:
        while header := stream.read(27):
            check(len(header) == 27 and header[:5] == b'OggS\0', f'Invalid Ogg page: {path.name}')
            page_serial = struct.unpack_from('<I', header, 14)[0]
            if serial is None:
                serial = page_serial
            check(serial == page_serial, f'Unexpected chained stream: {path.name}')
            granule = struct.unpack_from('<Q', header, 6)[0]
            if granule != 0xffffffffffffffff:
                final_granule = granule
            lacing = stream.read(header[26])
            check(len(lacing) == header[26], f'Truncated Ogg lacing: {path.name}')
            size = sum(lacing)
            if len(headers) < 2:
                body = stream.read(size)
                check(len(body) == size, f'Truncated Ogg body: {path.name}')
                offset = 0
                for segment in lacing:
                    packet.extend(body[offset:offset + segment])
                    offset += segment
                    if segment < 255:
                        headers.append(bytes(packet))
                        packet.clear()
                        if len(headers) == 2:
                            break
            else:
                stream.seek(size, 1)
    check(len(headers) >= 2 and headers[0].startswith(b'OpusHead'), f'Missing OpusHead: {path.name}')
    check(headers[1].startswith(b'OpusTags'), f'Missing OpusTags: {path.name}')
    pre_skip = struct.unpack_from('<H', headers[0], 10)[0]
    check(headers[0][9] == 2, f'Non-stereo audio: {path.name}')
    data = headers[1]
    position = 8
    vendor_size = struct.unpack_from('<I', data, position)[0]
    position += 4 + vendor_size
    count = struct.unpack_from('<I', data, position)[0]
    position += 4
    comments = {}
    for _ in range(count):
        size = struct.unpack_from('<I', data, position)[0]
        position += 4
        value = data[position:position + size].decode('utf-8')
        position += size
        key, separator, text = value.partition('=')
        check(bool(separator), f'Invalid Opus comment: {path.name}')
        comments.setdefault(key.lower(), []).append(text)
    return comments, (final_granule - pre_skip) / 48000


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--audio', action='store_true', help='Also verify all local audio hashes, durations, and tags')
    args = parser.parse_args()
    catalogue = json.loads((ROOT / 'catalogue.json').read_text(encoding='utf-8'))
    rows = catalogue['songs']
    check(len(rows) == 565, 'Expected all 565 playlist entries')
    check([row['index'] for row in rows] == list(range(1, 566)), 'Playlist order is incomplete')
    check(len({row['videoId'] for row in rows}) == 565, 'Duplicate video IDs')
    check(len({row['id'] for row in rows}) == 565, 'Duplicate catalogue IDs')
    imports = []
    for path in sorted(ROOT.glob('import-batch-*.json')):
        check(path.stat().st_size < 65536, 'Import batch exceeds the API request limit')
        payload = json.loads(path.read_text(encoding='utf-8'))
        check(payload['schemaVersion'] == 1 and len(payload['songs']) <= 50, 'Invalid import batch')
        imports.extend(payload['songs'])
    expected = []
    audio_count = 0
    for row in rows:
        name = f"{row['index']}:{row['videoId']}"
        check(re.fullmatch(r'[A-Za-z0-9_-]{11}', row['videoId']), f'Invalid video ID: {name}')
        check(row['youtubeUrl'] == 'https://www.youtube.com/watch?v=' + row['videoId'], f'Invalid URL: {name}')
        check(row['confidence'] in {'high', 'medium', 'low'}, f'Invalid confidence: {name}')
        if not row['reviewRequired']:
            check(row['title'] and row['tags'], f'Unresolved metadata marked ready: {name}')
            check(not row['validationWarnings'], f'Validation warnings in ready entry: {name}')
            check(len(row['title']) <= 160 and len(row['tags']) <= 10, f'Metadata bounds: {name}')
            check(all(len(tag) <= 32 for tag in row['tags']), f'Tag too long: {name}')
            check(all(e.get('retrievedEvidenceMatched') for e in row['evidence']), f'Unmatched evidence: {name}')
            check(all(is_article(e['url']) for e in row['evidence']), f'Non-article source URL: {name}')
            check(any(normalized(row['title']) in normalized(e['quote']) for e in row['evidence']), f'Title absent from evidence: {name}')
            expected.append({key: row[key] for key in ['id', 'youtubeUrl', 'title', 'tags']})
        audio = row['audio']
        check(audio['status'] in {'ready', 'failed'}, f'Download still pending: {name}')
        if audio['status'] == 'ready':
            audio_count += 1
            relative = Path(audio['path'])
            check(not relative.is_absolute() and relative.parts == ('audio', row['videoId'] + '.ogg'), f'Invalid audio path: {name}')
            if args.audio:
                path = ROOT / relative
                check(path.is_file(), f'Audio missing: {name}')
                check(path.stat().st_size == audio['byteLength'], f'Audio size mismatch: {name}')
                check(hashlib.sha256(path.read_bytes()).hexdigest() == audio['sha256'], f'Audio hash mismatch: {name}')
                comments, duration = opus_comments(path)
                check(abs(duration - audio['durationSeconds']) < 0.02, f'Audio duration mismatch: {name}')
                expected_duration = row.get('durationSeconds')
                check(not expected_duration or abs(duration - expected_duration) < max(5, expected_duration * .02), f'Audio is not full-length: {name}')
                check(comments.get('title', ['']) == [row['title']], f'Embedded title mismatch: {name}')
                check(comments.get('composer', []) == row['tags'], f'Embedded composer mismatch: {name}')
                check(comments.get('artist', []) == row['tags'], f'Embedded artist mismatch: {name}')
                check(comments.get('catalogue_review_required') == [str(row['reviewRequired']).lower()], f'Embedded review status mismatch: {name}')
    check(imports == expected, 'Import batches do not exactly match the verified catalogue entries')
    unresolved = json.loads((ROOT / 'review-required.json').read_text(encoding='utf-8'))
    failures = json.loads((ROOT / 'download-failures.json').read_text(encoding='utf-8'))
    check(unresolved == [row for row in rows if row['reviewRequired']], 'Review list mismatch')
    check(failures == [row for row in rows if row['audio']['status'] != 'ready'], 'Failure list mismatch')
    print(json.dumps({'entries': len(rows), 'downloaded': audio_count, 'downloadFailures': len(failures),
                      'verifiedMetadata': len(expected), 'reviewRequired': len(unresolved), 'audioVerified': args.audio}))


if __name__ == '__main__':
    main()
