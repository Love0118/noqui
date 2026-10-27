PRAGMA foreign_keys = ON;
PRAGMA user_version = 2;

CREATE TABLE catalogue_info (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE TABLE songs (
    id TEXT PRIMARY KEY,
    playlist_position INTEGER NOT NULL UNIQUE CHECK (playlist_position > 0),
    video_id TEXT NOT NULL UNIQUE,
    title TEXT NOT NULL,
    source_title TEXT,
    channel TEXT,
    source_duration_seconds REAL,
    youtube_url TEXT NOT NULL UNIQUE,
    confidence TEXT NOT NULL CHECK (confidence IN ('high', 'medium', 'low')),
    review_required INTEGER NOT NULL CHECK (review_required IN (0, 1)),
    notes TEXT NOT NULL
);

CREATE TABLE writers (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL UNIQUE CHECK (length(name) > 0)
);

CREATE TABLE song_writers (
    song_id TEXT NOT NULL REFERENCES songs(id),
    writer_id INTEGER NOT NULL REFERENCES writers(id),
    tag_order INTEGER NOT NULL CHECK (tag_order >= 0),
    PRIMARY KEY (song_id, writer_id),
    UNIQUE (song_id, tag_order)
);
CREATE INDEX song_writers_by_writer ON song_writers(writer_id, song_id);
CREATE TABLE song_tags (
    song_id TEXT NOT NULL REFERENCES songs(id),
    tag_order INTEGER NOT NULL,
    name TEXT NOT NULL,
    PRIMARY KEY (song_id, tag_order)
);
CREATE TABLE aliases (
    song_id TEXT NOT NULL REFERENCES songs(id),
    alias_order INTEGER NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('writer', 'title', 'punctuation')),
    name TEXT NOT NULL,
    writer TEXT,
    url TEXT,
    quote TEXT NOT NULL,
    PRIMARY KEY (song_id, alias_order)
);
CREATE VIEW 별칭목록 AS
SELECT s.playlist_position AS 번호, s.title AS 곡명,
       CASE a.kind WHEN 'writer' THEN '작가 별칭' WHEN 'title' THEN '곡명 발음' ELSE '특수문자 제거' END AS 종류,
       a.name AS 태그, a.writer AS 원래작가, a.url AS 출처URL, a.quote AS 확인문구
FROM aliases a JOIN songs s ON s.id=a.song_id ORDER BY s.playlist_position,a.alias_order;

CREATE TABLE audio (
    song_id TEXT PRIMARY KEY REFERENCES songs(id),
    status TEXT NOT NULL CHECK (status IN ('ready', 'failed')),
    path TEXT,
    container TEXT,
    codec TEXT,
    nominal_bitrate INTEGER,
    channels INTEGER,
    duration_seconds REAL,
    byte_length INTEGER,
    sha256 TEXT,
    failure_stage TEXT,
    failure_code TEXT,
    failure_reason TEXT,
    CHECK (status <> 'ready' OR (path IS NOT NULL AND byte_length > 0 AND length(sha256) = 64))
);

CREATE TABLE evidence (
    song_id TEXT NOT NULL REFERENCES songs(id),
    evidence_order INTEGER NOT NULL CHECK (evidence_order >= 0),
    url TEXT NOT NULL,
    quote TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('search_snippet', 'page')),
    retrieved_evidence_matched INTEGER NOT NULL CHECK (retrieved_evidence_matched IN (0, 1)),
    retrieval_url TEXT,
    retrieved_at TEXT,
    PRIMARY KEY (song_id, evidence_order)
);

CREATE TABLE review_issues (
    song_id TEXT NOT NULL REFERENCES songs(id),
    issue_order INTEGER NOT NULL CHECK (issue_order >= 0),
    message TEXT NOT NULL,
    korean_message TEXT NOT NULL,
    PRIMARY KEY (song_id, issue_order)
);
CREATE INDEX songs_by_title ON songs(title);
CREATE INDEX songs_by_review ON songs(review_required, playlist_position);
CREATE INDEX audio_by_status ON audio(status);

CREATE VIEW 전체곡 AS
SELECT s.playlist_position AS 번호,
       COALESCE(NULLIF(REPLACE(TRIM(s.title), CHAR(8204), ''), ''),
                NULLIF(REPLACE(TRIM(s.source_title), CHAR(8204), ''), ''),
                '제목 미확인') AS 곡명,
       COALESCE((
           SELECT GROUP_CONCAT(name, ', ') FROM (
               SELECT w.name FROM song_writers sw JOIN writers w ON w.id = sw.writer_id
               WHERE sw.song_id = s.id ORDER BY sw.tag_order
           )
       ), '작가 미확인') AS 작가,
       (SELECT GROUP_CONCAT(name, ', ') FROM (SELECT name FROM song_tags WHERE song_id=s.id ORDER BY tag_order)) AS 정답태그,
       CASE s.review_required WHEN 0 THEN '검수 완료' ELSE '검토 필요' END AS 검수상태,
       CASE a.status WHEN 'ready' THEN '저장 완료' ELSE '다운로드 불가' END AS 음원상태,
       CASE WHEN COALESCE(a.duration_seconds, s.source_duration_seconds) IS NULL THEN NULL
            ELSE PRINTF('%d:%02d',
                        CAST(COALESCE(a.duration_seconds, s.source_duration_seconds) AS INTEGER) / 60,
                        CAST(COALESCE(a.duration_seconds, s.source_duration_seconds) AS INTEGER) % 60)
       END AS 재생시간,
       ROUND(a.byte_length / 1000000.0, 2) AS 용량MB,
       s.youtube_url AS 유튜브,
       a.path AS 음원파일,
       CASE a.failure_code
           WHEN 'PRIVATE_VIDEO' THEN '비공개 영상'
           WHEN 'UPLOADER_ACCOUNT_UNAVAILABLE' THEN '업로더 계정 삭제·종료'
           WHEN 'AGE_RESTRICTED' THEN '연령 확인 필요'
           WHEN 'RIGHTSHOLDER_BLOCKED' THEN '권리자 차단'
           WHEN 'REGION_UNAVAILABLE' THEN '지역 제한'
           ELSE a.failure_reason
       END AS 다운로드불가사유,
       COALESCE((SELECT GROUP_CONCAT(korean_message, ' / ') FROM (
           SELECT korean_message FROM review_issues ri
           WHERE ri.song_id = s.id ORDER BY ri.issue_order
       )), CASE WHEN s.review_required = 1 THEN '조사 메모 확인' END) AS 검토사유,
       s.source_title AS 원래영상제목,
       s.channel AS 업로드채널,
       s.notes AS 조사메모,
       s.video_id AS 영상ID,
       s.id AS 곡ID
FROM songs s JOIN audio a ON a.song_id = s.id
ORDER BY s.playlist_position;

CREATE VIEW 검수완료곡 AS SELECT * FROM 전체곡 WHERE 검수상태 = '검수 완료';
CREATE VIEW 검토필요곡 AS SELECT * FROM 전체곡 WHERE 검수상태 = '검토 필요';
CREATE VIEW 다운로드불가곡 AS SELECT * FROM 전체곡 WHERE 음원상태 = '다운로드 불가';

CREATE VIEW 작가별곡 AS
SELECT w.name AS 작가, c.번호, c.곡명, c.검수상태, c.음원상태, c.재생시간, c.유튜브, c.음원파일
FROM song_writers sw JOIN writers w ON w.id = sw.writer_id
JOIN 전체곡 c ON c.곡ID = sw.song_id
ORDER BY w.name, c.번호;

CREATE VIEW 작가별집계 AS
SELECT w.name AS 작가, COUNT(*) AS 전체곡수,
       SUM(s.review_required = 0) AS 검수완료곡수,
       SUM(s.review_required = 1) AS 검토필요곡수,
       SUM(a.status = 'ready') AS 저장완료곡수
FROM writers w JOIN song_writers sw ON sw.writer_id = w.id
JOIN songs s ON s.id = sw.song_id JOIN audio a ON a.song_id = s.id
GROUP BY w.id ORDER BY 전체곡수 DESC, w.name;

CREATE VIEW 출처목록 AS
SELECT c.번호, c.곡명, c.작가, e.evidence_order + 1 AS 출처번호,
       e.url AS 출처URL, e.quote AS 확인문구,
       CASE e.kind WHEN 'page' THEN '원문 페이지' ELSE '검색 결과 인용' END AS 확인방법,
       CASE e.retrieved_evidence_matched WHEN 1 THEN '일치' ELSE '미확인' END AS 원문대조,
       e.retrieval_url AS 조회URL, e.retrieved_at AS 조회시각
FROM evidence e JOIN 전체곡 c ON c.곡ID = e.song_id
ORDER BY c.번호, e.evidence_order;

CREATE VIEW 집계 AS
SELECT '전체 곡' AS 항목, COUNT(*) AS 개수 FROM songs
UNION ALL SELECT '검수 완료', COUNT(*) FROM songs WHERE review_required = 0
UNION ALL SELECT '검토 필요', COUNT(*) FROM songs WHERE review_required = 1
UNION ALL SELECT '음원 저장 완료', COUNT(*) FROM audio WHERE status = 'ready'
UNION ALL SELECT '다운로드 불가', COUNT(*) FROM audio WHERE status = 'failed'
UNION ALL SELECT '등록 작가명', COUNT(*) FROM writers
UNION ALL SELECT '확인 출처', COUNT(*) FROM evidence;
