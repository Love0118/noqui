"""Replace MV edits with matched album audio or personal subtitled song uploads."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import sys


def replace(catalogue, replacements, packages=None):
    catalogue, replacements = Path(catalogue), Path(replacements)
    data = json.loads(catalogue.read_text(encoding='utf-8'))
    proposals = json.loads(replacements.read_text(encoding='utf-8'))
    if packages:
        sys.path.insert(0, str(packages))
    from mutagen.oggopus import OggOpus
    songs = {s['id']: s for s in data['songs']}
    prepared = []
    for proposal in proposals:
        song = songs[proposal['songId']]
        video = proposal['videoId']
        source = Path(proposal['localAudio'])
        evidence = proposal['evidence']
        source_type = proposal.get('sourceType', 'official_album_audio')
        if (song['videoId'] != proposal['previousVideoId']
                or not re.fullmatch('[A-Za-z0-9_-]{11}', video)
                or source_type not in {'official_album_audio', 'subtitled_audio'}
                or not proposal.get('sameArtistAndSongVerified')
                or not evidence.get('retrievedEvidenceMatched')
                or not evidence.get('quote')
                or evidence.get('url') != 'https://www.youtube.com/watch?v=' + video
                or source_type == 'official_album_audio' and 'Provided to YouTube by' not in evidence['quote']):
            raise ValueError('Replacement needs matching song identity and album or subtitled-upload evidence')
        if hashlib.sha256(source.read_bytes()).hexdigest() != proposal['sha256']:
            raise ValueError('Replacement audio hash mismatch')
        audio = OggOpus(source)
        expected = proposal['sourceDurationSeconds']
        if audio.info.channels != 2 or abs(audio.info.length - expected) > max(5, expected * .02):
            raise ValueError('Replacement audio is not the complete stereo recording')
        prepared.append((song, proposal, source, source_type))
    for song, proposal, source, source_type in prepared:
        previous = {key: song.get(key) for key in ['videoId', 'youtubeUrl', 'audio']}
        song.setdefault('recordingHistory', []).append({**previous,
            'reason': 'Replace music-video edit with matched song audio', 'replacementSourceType': source_type})
        video = proposal['videoId']
        target = catalogue.parent / 'audio' / (video + '.ogg')
        temporary = target.with_suffix('.replacement.ogg')
        try:
            shutil.copy2(source, temporary)
            audio = OggOpus(temporary)
            audio['TITLE'] = [song['title']]
            audio['COMPOSER'] = song['writers']
            audio['ARTIST'] = [song['singer']] if song.get('singer') else song['writers']
            audio['SOURCE_URL'] = ['https://www.youtube.com/watch?v=' + video]
            audio['CATALOGUE_REVIEW_REQUIRED'] = [str(song['reviewRequired']).lower()]
            if song['tags']:
                audio['ANSWER_TAG'] = song['tags']
            audio.save()
            duration = audio.info.length
            temporary.replace(target)
        finally:
            temporary.unlink(missing_ok=True)
        song.update(videoId=video, youtubeUrl='https://www.youtube.com/watch?v=' + video)
        song['audio'] = {'status': 'ready', 'path': 'audio/' + target.name,
            'format': 'ogg', 'codec': 'opus', 'nominalBitrate': 48000, 'channels': 2,
            'durationSeconds': duration, 'byteLength': target.stat().st_size,
            'sha256': hashlib.sha256(target.read_bytes()).hexdigest()}
        song['audioSourceReviewRequired'] = False
        song['audioSourceReviewReasons'] = []
        song['audioSourceType'] = source_type
        if proposal['evidence'] not in song['evidence']:
            song['evidence'].append(proposal['evidence'])
    catalogue.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    for name, predicate in [('review-required.json', lambda s: s['reviewRequired']),
            ('download-failures.json', lambda s: s['audio']['status'] == 'failed')]:
        path = catalogue.parent / name
        if path.exists():
            path.write_text(json.dumps([s for s in data['songs'] if predicate(s)], ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    return {'playlistId': data['playlistId'], 'replaced': len(prepared)}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('catalogue', type=Path)
    parser.add_argument('replacements', type=Path)
    parser.add_argument('--packages', type=Path)
    args = parser.parse_args()
    print(json.dumps(replace(args.catalogue, args.replacements, args.packages), ensure_ascii=False))
