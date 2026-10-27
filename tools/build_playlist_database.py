"""Build a browsable SQLite inventory of prepared playlists, sources, aliases and audio."""
import argparse
from contextlib import closing
import json
from pathlib import Path
import sqlite3
import tempfile


def build(workspace, destination):
    workspace, destination = Path(workspace), Path(destination)
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(prefix='.playlist-', suffix='.sqlite3', dir=destination.parent, delete=False) as handle:
        temporary = Path(handle.name)
    try:
        with closing(sqlite3.connect(temporary)) as db:
            db.executescript('''
                PRAGMA foreign_keys=ON;
                CREATE TABLE playlists(id TEXT PRIMARY KEY,name TEXT NOT NULL,source_date TEXT,chart_period TEXT,source_path TEXT NOT NULL);
                CREATE TABLE songs(playlist_id TEXT REFERENCES playlists(id),position INTEGER,title TEXT,singer TEXT,writers TEXT,tags TEXT,
                    rank INTEGER,tj_number TEXT,review_required INTEGER,audio_status TEXT,duration_seconds REAL,audio_path TEXT,
                    failure TEXT,youtube_url TEXT,song_id TEXT,source_review_required INTEGER NOT NULL,PRIMARY KEY(playlist_id,position));
                CREATE TABLE evidence(playlist_id TEXT,position INTEGER,url TEXT,quote TEXT,FOREIGN KEY(playlist_id,position) REFERENCES songs(playlist_id,position));
                CREATE TABLE aliases(playlist_id TEXT,position INTEGER,kind TEXT,name TEXT,url TEXT,quote TEXT,FOREIGN KEY(playlist_id,position) REFERENCES songs(playlist_id,position));
                CREATE TABLE naming_reviews(playlist_id TEXT,position INTEGER,reason TEXT,FOREIGN KEY(playlist_id,position) REFERENCES songs(playlist_id,position));
                CREATE TABLE recording_reviews(playlist_id TEXT,position INTEGER,reason TEXT,FOREIGN KEY(playlist_id,position) REFERENCES songs(playlist_id,position));
                CREATE VIEW 플레이리스트 AS SELECT p.name AS 이름,COUNT(*) AS 전체곡수,SUM(s.review_required=0) AS 검수완료곡수,
                    SUM(s.review_required=0 AND s.audio_status='ready' AND s.source_review_required=0) AS 재생가능곡수,
                    SUM(EXISTS(SELECT 1 FROM naming_reviews n WHERE n.playlist_id=s.playlist_id AND n.position=s.position)) AS 이름검토필요곡수,
                    SUM(s.source_review_required=1) AS 음원검토필요곡수,p.source_date AS 기준일,p.chart_period AS 차트기간,p.id AS 목록ID
                    FROM playlists p JOIN songs s ON s.playlist_id=p.id GROUP BY p.id ORDER BY p.id;
                CREATE VIEW 전체곡 AS SELECT p.name AS 플레이리스트,s.position AS 번호,s.rank AS 차트순위,s.tj_number AS TJ번호,s.title AS 곡명,
                    s.singer AS 가수,s.writers AS 작가,s.tags AS 정답태그,CASE s.review_required WHEN 0 THEN '검수 완료' ELSE '검토 필요' END AS 검수상태,
                    CASE s.audio_status WHEN 'ready' THEN '저장 완료' ELSE '저장 불가' END AS 음원상태,s.duration_seconds AS 재생시간초,
                    s.failure AS 확인필요사항,s.youtube_url AS 유튜브,s.audio_path AS 음원파일,p.chart_period AS 차트기간,
                    CASE s.source_review_required WHEN 0 THEN '보류 없음' ELSE '검토 필요' END AS 음원출처검토
                    FROM songs s JOIN playlists p ON p.id=s.playlist_id ORDER BY p.id,s.position;
                CREATE VIEW 재생가능곡 AS SELECT * FROM 전체곡 WHERE 검수상태='검수 완료' AND 음원상태='저장 완료' AND 음원출처검토='보류 없음';
                CREATE VIEW 검토필요곡 AS SELECT * FROM 전체곡 WHERE 검수상태='검토 필요';
                CREATE VIEW 출처목록 AS SELECT p.name AS 플레이리스트,s.position AS 번호,s.title AS 곡명,e.url AS 출처URL,e.quote AS 확인문구
                    FROM evidence e JOIN songs s ON s.playlist_id=e.playlist_id AND s.position=e.position JOIN playlists p ON p.id=s.playlist_id;
                CREATE VIEW 이름검토필요곡 AS SELECT p.name AS 플레이리스트,s.position AS 번호,s.title AS 곡명,s.tags AS 현재태그,n.reason AS 검토사유
                    FROM naming_reviews n JOIN songs s ON s.playlist_id=n.playlist_id AND s.position=n.position JOIN playlists p ON p.id=s.playlist_id;
                CREATE VIEW 별칭목록 AS SELECT p.name AS 플레이리스트,s.position AS 번호,s.title AS 곡명,a.kind AS 종류,a.name AS 별칭,a.url AS 출처URL,a.quote AS 확인문구
                    FROM aliases a JOIN songs s ON s.playlist_id=a.playlist_id AND s.position=a.position JOIN playlists p ON p.id=s.playlist_id;
                CREATE VIEW 음원검토필요곡 AS SELECT p.name AS 플레이리스트,s.position AS 번호,s.title AS 곡명,s.youtube_url AS 현재음원,r.reason AS 검토사유
                    FROM recording_reviews r JOIN songs s ON s.playlist_id=r.playlist_id AND s.position=r.position JOIN playlists p ON p.id=s.playlist_id;
            ''')
            for source in sorted((workspace / 'catalogues').glob('*/catalogue.json')):
                data = json.loads(source.read_text(encoding='utf-8'))
                playlist_id = 'ssip-noqui' if source.parent.name.startswith('vocaloid-') else data['playlistId']
                db.execute('INSERT INTO playlists VALUES (?,?,?,?,?)', (playlist_id,'씹노퀴' if playlist_id=='ssip-noqui' else data['playlistTitle'],data.get('sourceDate'),data.get('chartPeriod'),str(source.relative_to(workspace))))
                for song in data['songs']:
                    if playlist_id=='ssip-noqui' and song['reviewRequired']:
                        continue
                    audio = song.get('audio', {})
                    db.execute('INSERT INTO songs VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)', (
                        playlist_id,song['index'],song.get('title',''),song.get('singer'),', '.join(song.get('writers',song.get('tags',[]))),
                        ', '.join(song.get('tags',[])),song.get('rank'),str(song.get('tjNumber','')),int(song['reviewRequired']),audio.get('status','pending'),
                        audio.get('durationSeconds'),str(source.parent.relative_to(workspace)/audio['path']) if audio.get('path') else None,
                        ' / '.join(filter(None,[audio.get('reason'),song.get('notes'),*song.get('reviewReasons',[])])),song.get('youtubeUrl'),song.get('id'),int(song.get('audioSourceReviewRequired',False))))
                    for evidence in song.get('evidence',[]):
                        db.execute('INSERT INTO evidence VALUES (?,?,?,?)',(playlist_id,song['index'],evidence.get('url'),evidence.get('quote')))
                    for alias in song.get('aliases',[]):
                        db.execute('INSERT INTO aliases VALUES (?,?,?,?,?,?)',(playlist_id,song['index'],alias['kind'],alias['name'],alias.get('url'),alias.get('quote')))
                    if song.get('namingReviewRequired'):
                        for reason in song.get('namingReviewReasons') or ['통용 제목·별칭 출처 추가 확인 필요']:
                            db.execute('INSERT INTO naming_reviews VALUES (?,?,?)',(playlist_id,song['index'],reason))
                    if song.get('audioSourceReviewRequired'):
                        for reason in song.get('audioSourceReviewReasons') or ['공식 음원·앨범 버전 및 MV 대사·인트로 확인 필요']:
                            db.execute('INSERT INTO recording_reviews VALUES (?,?,?)',(playlist_id,song['index'],reason))
            db.commit()
            if db.execute('PRAGMA integrity_check').fetchall()!=[('ok',)] or db.execute('PRAGMA foreign_key_check').fetchall():
                raise ValueError('Playlist database failed integrity checks')
            counts=db.execute('SELECT * FROM 플레이리스트').fetchall()
        temporary.replace(destination)
        return counts
    finally:
        temporary.unlink(missing_ok=True)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--workspace',type=Path,default=Path(__file__).resolve().parents[1])
    parser.add_argument('--output',type=Path)
    args=parser.parse_args()
    print(json.dumps(build(args.workspace,args.output or args.workspace/'playlists/catalogue.sqlite3'),ensure_ascii=False))
