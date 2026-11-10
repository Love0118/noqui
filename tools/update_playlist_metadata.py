"""Prepare reviewed playlist metadata using an installed manifest's unchanged audio records."""
import argparse
import copy
import json
from pathlib import Path
import unicodedata

from playlist_review import normalize, review_reasons, reviewed


def prepare(catalogue, installed, destination):
    catalogue,installed,destination=Path(catalogue),Path(installed),Path(destination)
    if destination.resolve() in {catalogue.resolve(),installed.resolve()}:
        raise ValueError('Write the proposal to a separate file before live activation')
    data=json.loads(catalogue.read_text(encoding='utf-8'));before=json.loads(installed.read_text(encoding='utf-8'))
    expected_id='ssip-noqui' if catalogue.parent.name.startswith('vocaloid-') else data['playlistId']
    if before['schemaVersion']!=1 or before['id']!=expected_id:
        raise ValueError('Playlist identity differs from installed library')
    candidates=[s for s in data['songs'] if not s['reviewRequired']] if expected_id=='ssip-noqui' else data['songs']
    if len(candidates)!=len(before['songs'])+len(before.get('excludedSongs',[])):
        raise ValueError('A metadata correction must preserve the selected playlist size')
    old={row['song']['id']:row for row in before['songs']}
    if len(old)!=len(before['songs']):raise ValueError('Duplicate installed song ID')
    tracks=[];excluded=[];changed=[]
    for song in candidates:
        if not reviewed(song):
            excluded.append({'index':song['index'],'title':song['title'][:160],'reasons':review_reasons(song)})
            continue
        previous=old.get(song['id'])
        if previous is None:raise ValueError(f'Missing installed recording for reviewed song: {song["index"]}')
        if previous['song']['youtubeUrl']!=song['youtubeUrl']:
            raise ValueError(f'Recording URL changed: {song["index"]}')
        title=song['title'];tags=song['tags']
        if (title.strip()!=title or not 0<len(title)<=160 or any(unicodedata.category(c)=='Cc' for c in title)
                or len(tags)>10 or len({normalize(t) for t in tags})!=len(tags)
                or any(t.strip()!=t or not normalize(t) or len(t)>32 or any(unicodedata.category(c)=='Cc' for c in t) for t in tags)):
            raise ValueError(f'Invalid game naming: {song["index"]}')
        audio=previous['audio']
        if audio is not None:
            if song['audio']['status']!='ready' or song.get('audioSourceReviewRequired',False):
                raise ValueError(f'Recording review or availability changed: {song["index"]}')
            if abs(audio['durationMs']-round(song['audio']['durationSeconds']*1000))>20:
                raise ValueError(f'Recording duration changed: {song["index"]}')
        track={'song':{k:song[k] for k in ['id','youtubeUrl','title','tags']},'audio':copy.deepcopy(audio)}
        if track['song']!=previous['song']:changed.append(song['id'])
        tracks.append(track)
    if not tracks:raise ValueError('No songs have completed Korean naming review')
    result={'schemaVersion':1,'id':before['id'],'name':before['name'],'songs':tracks}
    if excluded:result['excludedSongs']=excluded
    destination.parent.mkdir(parents=True,exist_ok=True)
    destination.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    return {'id':before['id'],'tracks':len(candidates),'playable':sum(t['audio'] is not None for t in tracks),
            'reviewRequired':len(excluded),'changed':len(changed),'audioRecordsPreserved':True}


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('catalogue',type=Path);parser.add_argument('installed',type=Path);parser.add_argument('output',type=Path)
    args=parser.parse_args();print(json.dumps(prepare(args.catalogue,args.installed,args.output),ensure_ascii=False))
