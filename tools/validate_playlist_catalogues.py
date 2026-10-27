"""Check playlist inventories, private metadata and optional complete local audio files."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import uuid

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('audio_validation', ROOT/'catalogues/vocaloid-PLJpdxH7yK7IqyEKS5pm0cy_DpDWGC0FFB/validate.py')
audio_validation = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audio_validation)


def validate(source, audio=False):
    data = json.loads(source.read_text(encoding='utf-8'))
    songs = data['songs']
    if data['playlistId'].startswith('tj-'):
        assert len(songs)==100, f'{source}: incomplete chart'
        assert sorted(song['rank'] for song in songs)==list(range(1,101)), f'{source}: missing ranks'
        assert data['sourceDate']=='2026-10-27' and data['chartSources']
    ids=set()
    for song in songs:
        label=f'{source.parent.name}: {song["index"]}'
        assert song['id'] not in ids, f'{label}: duplicate ID'
        ids.add(song['id'])
        assert str(uuid.UUID(song['id']))==song['id'], f'{label}: invalid ID'
        if not song['reviewRequired']:
            title=song['title'];tags=song['tags']
            assert title.strip()==title and 0<len(title)<=160 and re.search('[0-9A-Za-z가-힣]',title), f'{label}: invalid title'
            assert len(tags)<=10 and all(t.strip()==t and 0<len(t)<=32 and re.search('[0-9A-Za-z가-힣]',t) for t in tags),f'{label}: invalid tag'
            assert song.get('writers',tags) and song['evidence'],f'{label}: missing credit or evidence'
            assert not any(re.search(r'외\s*\d+\s*명',w) for w in song.get('writers',tags)),f'{label}: placeholder writer'
            assert all(e.get('url') and e.get('quote') and e.get('retrievedEvidenceMatched') for e in song['evidence']),f'{label}: unmatched evidence'
        for alias in song.get('aliases',[]):
            assert alias['name'].strip() and alias['kind'] in {'title','writer','punctuation','singer'},f'{label}: invalid alias'
            if alias['kind']!='punctuation':
                assert alias.get('url') and alias.get('quote'),f'{label}: unsourced alias'
        a=song['audio']
        assert a['status'] in {'ready','failed'},f'{label}: unfinished audio'
        if a['status']=='ready':
            assert re.fullmatch('[A-Za-z0-9_-]{11}',song['videoId']),f'{label}: invalid video ID'
            assert song['youtubeUrl']=='https://www.youtube.com/watch?v='+song['videoId'],f'{label}: noncanonical URL'
            assert a['path']=='audio/'+song['videoId']+'.ogg',f'{label}: invalid audio path'
            if audio:
                path=source.parent/a['path'];assert path.stat().st_size==a['byteLength'],f'{label}: size mismatch'
                assert hashlib.sha256(path.read_bytes()).hexdigest()==a['sha256'],f'{label}: hash mismatch'
                comments,duration=audio_validation.opus_comments(path)
                assert abs(duration-a['durationSeconds'])<.02,f'{label}: duration mismatch'
                assert comments.get('title')==[song['title']],f'{label}: embedded title mismatch'
                assert comments.get('composer',[])==song.get('writers',song['tags']),f'{label}: embedded writer mismatch'
    return {'name':data['playlistTitle'],'entries':len(songs),'reviewed':sum(not s['reviewRequired'] for s in songs),
            'downloaded':sum(s['audio']['status']=='ready' for s in songs),
            'playable':sum(not s['reviewRequired'] and s['audio']['status']=='ready' for s in songs)}


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--audio',action='store_true')
    parser.add_argument('sources',nargs='*',type=Path)
    args=parser.parse_args()
    print(json.dumps([validate(p,args.audio) for p in args.sources or sorted((ROOT/'catalogues').glob('*/catalogue.json'))],ensure_ascii=False))
