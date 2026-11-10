"""Apply researched naming corrections while preserving recordings, credits and review history."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
from collections import defaultdict
from playlist_review import title_usage_verified

ROOT=Path(__file__).resolve().parents[1]


def load(path):return json.loads(path.read_text(encoding='utf-8'))
def save(path,data):path.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def normalize(value):return re.sub('[^0-9A-Za-z가-힣]','',value).lower()
def apply(source,corrections,packages=None):
    source,corrections=Path(source),Path(corrections)
    old=load(source);proposed=load(corrections)
    if old['playlistId']!=proposed['playlistId']:raise ValueError('Playlist identity changed')
    if [s['id'] for s in old['songs']]!=[s['id'] for s in proposed['songs']]:raise ValueError('Song order or identity changed')
    if packages:sys.path.insert(0,str(packages))
    from mutagen.oggopus import OggOpus
    changes=[]
    files=defaultdict(list)
    protected=['index','videoId','youtubeUrl','sourceTitle','singer','writers','audio','reviewRequired','reviewReasons','reviewNotes','confidence']
    for before,after in zip(old['songs'],proposed['songs']):
        for key in protected:
            if before.get(key)!=after.get(key):raise ValueError(f'{before["index"]}: correction unexpectedly changed {key}')
        if any(e not in after.get('evidence',[]) for e in before.get('evidence',[])):
            raise ValueError(f'{before["index"]}: correction removed existing evidence')
        naming_changed=after['title']!=before['title'] or after['tags']!=before['tags']
        if naming_changed and (len(after['title'])>160 or not normalize(after['title']) or len(after['tags'])>10 or any(len(t)>32 or not normalize(t) for t in after['tags'])):
            raise ValueError(f'{before["index"]}: invalid game naming')
        if naming_changed and len({normalize(t) for t in after['tags']})!=len(after['tags']):
            raise ValueError(f'{before["index"]}: duplicate normalized tags')
        prior_aliases={json.dumps(a,ensure_ascii=False,sort_keys=True) for a in before.get('aliases',[])}
        for alias in after.get('aliases',[]):
            if json.dumps(alias,ensure_ascii=False,sort_keys=True) in prior_aliases:continue
            if alias['kind']!='punctuation' and (not alias.get('url') or not alias.get('quote') or normalize(alias['name']) not in normalize(alias['quote'])):
                raise ValueError(f'{before["index"]}: new alias lacks matching usage evidence')
        if after['title']!=before['title']:
            if not title_usage_verified(after):
                raise ValueError(f'{before["index"]}: replacement requires a sourced Korean display title')
            after.setdefault('originalTitle',before.get('originalTitle',before['title']))
            for alias in after.get('aliases',[]):
                if alias.get('origin')=='user_authorized_pronunciation' and alias.get('language','en')=='en':
                    alias.setdefault('originalTitle',before['title'])
            history=after.setdefault('namingHistory',[])
            history.append({'previousTitle':before['title'],'previousTags':before['tags'],'reason':'Replace prior naming with independently sourced common usage'})
        if before['audio']['status']=='ready':
            path=(source.parent/before['audio']['path']).resolve()
            if not path.is_relative_to(source.parent.resolve()):raise ValueError('Audio path escapes catalogue')
            files[path].append((before,after,naming_changed))
        if after['title']!=before['title'] or after['tags']!=before['tags']:
            changes.append({'index':before['index'],'previousTitle':before['title'],'title':after['title'],'tags':after['tags']})
    retag=[]
    for path,rows in files.items():
        if not any(changed for _,_,changed in rows):continue
        if len({(after['title'],tuple(after['tags'])) for _,after,_ in rows})!=1:
            raise ValueError(f'Shared recording requires consistent title and tags: {path.name}')
        retag.append((path,rows))
    for path,rows in retag:
        after=rows[0][1];audio=OggOpus(path);audio['TITLE']=[after['title']]
        if after['tags']:audio['ANSWER_TAG']=after['tags']
        elif 'ANSWER_TAG' in audio:del audio['ANSWER_TAG']
        audio.save()
        size=path.stat().st_size;sha256=hashlib.sha256(path.read_bytes()).hexdigest()
        for before,after,_ in rows:
            after['audio']={**before['audio'],'byteLength':size,'sha256':sha256}
    save(source,proposed)
    for file,predicate in [('review-required.json',lambda s:s['reviewRequired']),('download-failures.json',lambda s:s['audio']['status']=='failed')]:
        path=source.parent/file
        if path.exists():save(path,[s for s in proposed['songs'] if predicate(s)])
    save(source.parent/'naming-corrections.json',{'changes':changes,'remainingNamingReview':[
        {'index':s['index'],'title':s['title'],'reasons':s.get('namingReviewReasons',[])} for s in proposed['songs'] if s.get('namingReviewRequired')]})
    return {'playlistId':old['playlistId'],'changed':len(changes),'namingReviewRequired':sum(s.get('namingReviewRequired',False) for s in proposed['songs'])}


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source',type=Path);parser.add_argument('corrections',type=Path);parser.add_argument('--packages',type=Path)
    args=parser.parse_args();print(json.dumps(apply(args.source,args.corrections,args.packages),ensure_ascii=False))
