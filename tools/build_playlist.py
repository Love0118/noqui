"""Export reviewed catalogue entries and metadata-free full-length Opus for server installation."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import re


def build(source, output, playlist_id, name, ffmpeg='ffmpeg'):
    source, output = Path(source).resolve(), Path(output).resolve()
    if not re.fullmatch(r'[a-z0-9-]{1,64}', playlist_id) or not name.strip() or len(name)>64:
        raise ValueError('Invalid playlist identity')
    if output==source.parent:
        raise ValueError('Export must use a separate output folder')
    data = json.loads(source.read_text(encoding='utf-8'))
    rows = [song for song in data['songs'] if not song['reviewRequired']]
    if not rows:
        raise ValueError('No reviewed songs to export')
    output.parent.mkdir(parents=True, exist_ok=True)
    if output.exists():
        raise ValueError('Export folder already exists; choose a new path to preserve the existing bundle')
    with tempfile.TemporaryDirectory(prefix='.playlist-export-', dir=output.parent) as temporary:
        staging = Path(temporary) / 'bundle'
        staging.mkdir()
        excluded=[{'index':row['index'],'title':row['title'],
                   'reasons':row.get('reviewReasons') or [row.get('notes') or 'Song identity, title or credits require review']}
                  for row in data['songs'] if row['reviewRequired']] if playlist_id.startswith('tj-') else []
        result = export_rows(source, staging, playlist_id, name, rows, ffmpeg, excluded)
        staging.replace(output)
    return result


def export_rows(source, output, playlist_id, name, rows, ffmpeg, excluded):
    (output / '.gitignore').write_text('audio/\n', encoding='utf-8')
    (output / 'audio').mkdir()
    tracks = []
    for row in rows:
        if (not re.search('[0-9A-Za-z가-힣]', row['title']) or len(row['title'])>160 or len(row['tags'])>10
                or any(not re.search('[0-9A-Za-z가-힣]', tag) or len(tag)>32 for tag in row['tags'])):
            raise ValueError(f'Invalid reviewed game metadata: {row.get("index")}: {row["title"]}')
        track = {'song': {key: row[key] for key in ('id', 'youtubeUrl', 'title', 'tags')}, 'audio': None}
        audio = row['audio']
        if audio['status'] == 'ready':
            original = (source.parent / audio['path']).resolve()
            if not original.is_relative_to(source.parent) or hashlib.sha256(original.read_bytes()).hexdigest() != audio['sha256']:
                raise ValueError(f'Invalid source audio: {row["videoId"]}')
            target = output / 'audio' / (row['videoId'] + '.ogg')
            # Keep full packets and duration, removing answer-bearing comments before browser delivery.
            with tempfile.TemporaryDirectory(prefix='.export-', dir=output) as temporary:
                remux = Path(temporary) / 'audio.ogg'
                subprocess.run([ffmpeg, '-nostdin', '-v', 'error', '-y', '-i', str(original),
                                '-map', '0:a:0', '-map_metadata', '-1', '-c:a', 'copy', '-fflags', '+bitexact', str(remux)], check=True)
                remux.replace(target)
            track['audio'] = {'path': f'audio/{row["videoId"]}.ogg', 'sha256': hashlib.sha256(target.read_bytes()).hexdigest(),
                              'byteLength': target.stat().st_size, 'durationMs': round(audio['durationSeconds'] * 1000)}
        tracks.append(track)
    manifest = {'schemaVersion': 1, 'id': playlist_id, 'name': name, 'songs': tracks}
    if excluded:
        manifest['excludedSongs']=excluded
    temporary_manifest = output / '.playlist.json'
    temporary_manifest.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    temporary_manifest.replace(output / 'playlist.json')
    return {'id': playlist_id, 'name': name, 'tracks': len(tracks)+len(excluded), 'reviewRequired':len(excluded), 'playable': sum(t['audio'] is not None for t in tracks)}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('catalogue', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--id', required=True)
    parser.add_argument('--name', required=True)
    parser.add_argument('--ffmpeg', default=shutil.which('ffmpeg') or 'ffmpeg')
    args = parser.parse_args()
    print(json.dumps(build(args.catalogue, args.output, args.id, args.name, args.ffmpeg), ensure_ascii=False))
