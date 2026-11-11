"""Assemble the reviewed TJ newest-150 Japan playlist and its isolated exports."""
from __future__ import annotations

import argparse
from contextlib import closing
import hashlib
import json
import math
from pathlib import Path
import re
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import unicodedata
import uuid
from datetime import date, datetime
from urllib.parse import unquote, urlparse


PLAYLIST_ID = 'tj-new-japan-20261110'
PLAYLIST_NAME = 'TJ 일본 신곡 150 - 261110'
NAMESPACE = uuid.UUID('974ae1d1-4ba9-5c21-bde0-8f359ba36340')
HERE = Path(__file__).resolve()
ROOT = HERE.parents[1]
DEFAULT_INPUT = ROOT / '.runtime' / PLAYLIST_ID
DEFAULT_SCRATCH = DEFAULT_INPUT / 'scratch'
DEFAULT_CATALOGUE = ROOT / 'catalogues' / PLAYLIST_ID
DEFAULT_PLAYLIST = ROOT / 'playlists' / PLAYLIST_ID


def normalized(value: str) -> str:
    """Ignore punctuation, spaces and case while preserving all Unicode letters."""
    return ''.join(c.casefold() for c in value if c.isalnum())


def clean_tag(value: str) -> str:
    value = unicodedata.normalize('NFC', str(value or ''))
    value = re.sub(r'[^0-9A-Za-z가-힣ぁ-ゖゝ-ゟァ-ヺー-ヿ々〆〇㐀-䶿一-鿿\s]', '', value)
    return ' '.join(value.split()).strip()


def valid_url(value: str) -> bool:
    parsed = urlparse(value or '')
    return parsed.scheme in {'http', 'https'} and bool(parsed.hostname)


def viewed_source_url(value: str) -> bool:
    if not valid_url(value):
        return False
    parsed = urlparse(value)
    host = (parsed.hostname or '').casefold()
    path = unquote(parsed.path).rstrip('/').casefold()
    return (host not in {'google.com', 'www.google.com', 'search.naver.com', 'm.search.naver.com', 'bing.com', 'www.bing.com'}
            and path not in {'', '/search', '/search.naver'} and '나무위키:대문' not in path)


def canonical_youtube_url(value: str, video_id: str) -> bool:
    parsed = urlparse(value or '')
    if parsed.scheme != 'https' or parsed.hostname not in {
            'youtube.com', 'www.youtube.com', 'youtu.be', 'www.youtu.be', 'youtube-nocookie.com', 'www.youtube-nocookie.com'}:
        return False
    if parsed.hostname in {'youtu.be', 'www.youtu.be'}:
        url_id = parsed.path.strip('/').split('/')[0]
    elif parsed.path == '/watch':
        from urllib.parse import parse_qs
        url_id = parse_qs(parsed.query).get('v', [''])[0]
    else:
        parts = parsed.path.strip('/').split('/')
        url_id = parts[-1] if len(parts) >= 2 and parts[-2] in {'embed', 'shorts', 'live'} else ''
    return url_id == video_id


def split_names(value) -> list[str]:
    if not isinstance(value, str):
        return []
    return list(dict.fromkeys(part.strip() for part in re.split(r'[,、，;；]+', value) if part.strip()))


def load_json(path: Path):
    return json.loads(path.read_text(encoding='utf-8'))


def load_inputs(input_dir: Path):
    selected = load_json(input_dir / 'selected-150.json')
    if not isinstance(selected, list) or len(selected) != 150:
        raise ValueError('selected-150.json must contain exactly 150 official rows')
    indices = [row.get('index') for row in selected]
    if sorted(indices) != list(range(1, 151)):
        raise ValueError('Official selection indices must be exactly 1..150')
    research = []
    for part in range(1, 7):
        rows = load_json(input_dir / f'research-output-{part}.json')
        if not isinstance(rows, list) or len(rows) != 25:
            raise ValueError(f'research-output-{part}.json must contain exactly 25 rows')
        research.extend(rows)
    research_by_index = {}
    for row in research:
        index = row.get('index')
        if index in research_by_index:
            raise ValueError(f'Duplicate researched index: {index}')
        research_by_index[index] = row
    if sorted(research_by_index) != list(range(1, 151)):
        raise ValueError('Research indices must cover exactly 1..150')
    return selected, research_by_index


def match_official_row(official: dict, research: dict) -> list[str]:
    reasons = []
    number = str(official.get('pro', '')).strip()
    if not number.isdigit() or int(number) != int(research.get('tjNumber', -1)):
        reasons.append('Research TJ number does not match the official selection row.')
    raw_official_title = official.get('indexTitle', '').strip()
    official_title = re.split(r'[（(]', raw_official_title, 1)[0].strip()
    official_titles = {normalized(raw_official_title), normalized(official_title), normalized(official_title.split(' - ')[0])}
    researched_titles = {normalized(research.get('originalTitle', ''))}
    if official_titles and researched_titles and not official_titles.intersection(researched_titles):
        reasons.append('Research title does not match the official index title/song; identity needs review.')
    if not research.get('originalTitle') or not research.get('singer'):
        reasons.append('Original title or singer is missing; song identity needs review.')
    elif normalized(research['singer']) != normalized(official.get('indexSong', '')):
        reasons.append('Research singer differs from the official TJ artist; identity needs review.')
    if official.get('categoryVerified') is not True or not valid_url(official.get('categoryUrl', '')):
        reasons.append('Official TJ category source is not verified.')
    return reasons


def title_is_numeric(title: str) -> bool:
    return bool(re.search(r'\d', title)) and all(c.isdigit() or c.isspace() or unicodedata.category(c)[0] == 'P' for c in title)


def publication_date(value: str) -> date:
    text = str(value or '').strip()
    for fmt in ('%Y-%m-%d', '%Y/%m/%d', '%Y.%m.%d', '%Y%m%d'):
        try:
            return datetime.strptime(text, fmt).date()
        except ValueError:
            pass
    raise ValueError(f'Invalid official publish date: {value!r}')


def title_evidence_valid(song: dict, title: str) -> bool:
    if title_is_numeric(title):
        return True
    key = normalized(title)
    if not key or not re.search(r'[가-힣]', title):
        return False
    original = normalized(song.get('originalTitle', ''))
    singer = normalized(song.get('singer', ''))
    for evidence in song.get('evidence', []):
        quote = evidence.get('quote', '')
        q = normalized(quote)
        if (evidence.get('retrievedEvidenceMatched') and viewed_source_url(evidence.get('url', ''))
                and evidence.get('kind') in {'page', 'youtube_description', 'search_snippet'}
                and key in q and ((original and original in q) or (singer and singer in q))):
            return True
    return False


def canonical_creators(official: dict, research: dict) -> tuple[list[str], list[dict], list[str]]:
    canonical = []
    reasons = []
    composer, lyricist = official.get('com', ''), official.get('word', '')
    correction = research.get('creditCorrection') or {}
    if correction:
        corrected = correction.get('canonicalCredits') or {}
        names = split_names(corrected.get('composer', '')) + split_names(corrected.get('lyricist', ''))
        sources = correction.get('evidence') or []
        verified = (str(correction.get('tjNumber')) == str(official.get('pro'))
                    and normalized(correction.get('artist', '')) == normalized(research.get('singer', ''))
                    and normalized(correction.get('originalTitle', '')) == normalized(research.get('originalTitle', ''))
                    and bool(names) and corrected.get('composer') and corrected.get('lyricist')
                    and any(e.get('retrievedEvidenceMatched') and viewed_source_url(e.get('url', ''))
                            and e.get('sourceRole') in {'official_artist_discography', 'official_release_metadata'}
                            and normalized(research.get('originalTitle', '')) in normalized(e.get('quote', ''))
                            and normalized(research.get('singer', '')) in normalized(e.get('quote', ''))
                            and all(normalized(name) in normalized(e.get('quote', '')) for name in names)
                            for e in sources if isinstance(e, dict)))
        if verified:
            composer, lyricist = corrected['composer'], corrected['lyricist']
        else:
            reasons.append('A proposed credit correction lacks matching primary release evidence for this TJ song.')
    for name in split_names(composer) + split_names(lyricist):
        if normalized(name) not in {normalized(old) for old in canonical}:
            canonical.append(name)
    if not canonical:
        return [], [], ['Official TJ composer/lyricist credit is empty; credits need review.']
    aliases = []
    raw_aliases = research.get('writerAliases') or []
    if isinstance(raw_aliases, dict):
        raw_aliases = [raw_aliases]
    for item in raw_aliases:
        if isinstance(item, str):
            aliases.append({'name': item.strip(), 'canonicalName': None})
        elif isinstance(item, dict):
            aliases.append(dict(item))
    matched_aliases = []
    for creator in canonical:
        korean_canonical = bool(re.search('[가-힣]', creator))
        found = []
        for alias in aliases:
            linked = alias.get('canonicalName')
            if not linked:
                # Inference is allowed only when its exact spelling names one official credit.
                linked = creator if normalized(alias.get('name', '')) == normalized(creator) else None
            language_matches = re.search('[A-Za-zぁ-ゖァ-ヺ一-龯]', str(alias.get('name', ''))) if korean_canonical else re.search('[가-힣]', str(alias.get('name', '')))
            quote = normalized(alias.get('quote', ''))
            if (linked and normalized(linked) == normalized(creator) and language_matches and alias['name'].strip() != creator
                    and alias.get('retrievedEvidenceMatched') and viewed_source_url(alias.get('url', ''))
                    and normalized(alias['name']) in quote and normalized(creator) in quote):
                found.append({**alias, 'canonicalName': creator})
        if not found:
            reasons.append(f'Korean and original-name mapping is missing for official creator “{creator}”.')
        matched_aliases.extend(found)
    return canonical, matched_aliases, reasons


def make_tags(original_title: str, creators: list[str], creator_aliases: list[dict],
              singer: str = '', singer_aliases: list[dict] | None = None,
              additional_aliases: list[dict] | None = None, display_title: str = '') -> tuple[list[str], list[str]]:
    values = [original_title]
    for creator in creators:
        values.append(creator)
        values.extend(alias['name'] for alias in creator_aliases if alias.get('canonicalName') == creator or
                      (not alias.get('canonicalName') and normalized(alias.get('name', '')) == normalized(creator)))
    values.append(singer)
    values.extend(alias.get('name', '') for alias in singer_aliases or [] if isinstance(alias, dict))
    values.extend(alias.get('name', '') for alias in additional_aliases or [] if isinstance(alias, dict)
                  and alias.get('kind') in {'title', 'writer'}
                  and (alias.get('retrievedEvidenceMatched') or alias.get('origin') == 'user_authorized_writer_alias')
                  and normalized(alias.get('name', '')) != normalized(display_title))
    # Credits and recording titles retain affiliations/CV/version labels in the DB;
    # the literal name before that annotation is also an answer.
    values.extend(str(value).split('(', 1)[0].strip() for value in list(values) if '(' in str(value))
    if '(' in display_title:
        values.append(display_title.split('(', 1)[0].strip())
    tags, seen = [], set()
    for value in values:
        tag = clean_tag(value)
        key = normalized(tag)
        if not tag or not key or key in seen:
            continue
        seen.add(key)
        tags.append(tag)
    reasons = []
    if len(tags) > 16 or any(len(tag) > 32 for tag in tags):
        reasons.append('Required original-title/creator tags exceed the 16-tag or 32-character limits.')
    return tags[:16], reasons


def evidence_rows(research: dict, reasons: list[str]) -> list[dict]:
    rows = []
    for evidence in research.get('evidence') or []:
        url, quote = evidence.get('url', ''), evidence.get('quote', '')
        if not valid_url(url) or not quote:
            reasons.append('A research evidence item has no valid source URL or quote.')
        if not evidence.get('retrievedEvidenceMatched'):
            reasons.append('A research source has not been independently matched to its quoted text.')
        rows.append({
            'url': url, 'quote': quote, 'kind': evidence.get('kind') or 'page',
            'retrievedEvidenceMatched': bool(evidence.get('retrievedEvidenceMatched')),
        })
    if not rows:
        reasons.append('No per-song source evidence was supplied.')
    return rows


def build_song(official: dict, research: dict, scratch: Path) -> dict:
    reasons = match_official_row(official, research)
    tj_number = int(research.get('tjNumber') or 0)
    song_id = str(uuid.uuid5(NAMESPACE, f'{PLAYLIST_ID}:{tj_number}'))
    canonical, writer_aliases, credit_reasons = canonical_creators(official, research)
    reasons.extend(credit_reasons)
    title = str(research.get('title') or '').strip()
    original = str(research.get('originalTitle') or '').strip()
    singer = str(research.get('singer') or '').strip()
    evidence = evidence_rows(research, reasons)
    is_japanese = bool(re.search(r'[ぁ-ゖァ-ヺ一-龯]', original))
    pronunciation = research.get('englishPronunciationFallback')
    title_origin = 'evidence'
    title_verified = title_evidence_valid({**research, 'originalTitle': original, 'singer': singer}, title)
    fallback_verified = False
    if not title_verified:
        authorized = (not is_japanese and re.search('[A-Za-z]', original)
                      and ((isinstance(pronunciation, dict) and pronunciation.get('authorized') is True
                            and pronunciation.get('origin') == 'user_authorized_english_pronunciation'
                            and pronunciation.get('searches'))
                           or (pronunciation is True and research.get('searches'))))
        if authorized:
            title = str(pronunciation.get('title') or title).strip() if isinstance(pronunciation, dict) else title
            title_origin = 'user_authorized_english_pronunciation'
            fallback_verified = True
        else:
            reasons.append('Korean title is not literally verified in matched, viewed per-song evidence for this song and artist.')
    if not title or len(title) > 160 or (not re.search(r'[가-힣]', title) and not title_is_numeric(title)):
        reasons.append('Display title is missing or does not meet the Korean-title/length rule.')
    if title_origin == 'user_authorized_english_pronunciation' and (is_japanese or not (pronunciation.get('searches') if isinstance(pronunciation, dict) else research.get('searches'))):
        reasons.append('English pronunciation fallback is unauthorized for this original title.')
    naming_review_required = (not (title_verified or fallback_verified) or not title or len(title) > 160
                              or (not re.search(r'[가-힣]', title) and not title_is_numeric(title)))
    if not original:
        reasons.append('Original Japanese or English title is missing.')
    elif (not (re.search(r'[A-Za-z]', original) or re.search(r'[ぁ-ゖァ-ヺ一-龯]', original))
          or re.search(r'[가-힣]', original)):
        reasons.append('Original title is not clearly Japanese or English.')
    if research.get('reviewRequired'):
        reasons.extend(research.get('reviewReasons') or ['Song identity or creator credits remain under review.'])
    # A sourced actual-use Korean name resolves a naming-only hold, including conventional phonetic names.
    if research.get('namingReviewRequired') and not (title_verified or fallback_verified):
        reasons.extend(research.get('namingReviewReasons') or ['Research marked the Korean title for review.'])
    alias_records = list(research.get('aliases') or [])
    for item in research.get('titleAliases') or []:
        name = item.get('name') if isinstance(item, dict) else str(item)
        if normalized(name or '') == normalized(title) or any(a.get('kind') == 'title' and normalized(a.get('name', '')) == normalized(name or '') for a in alias_records if isinstance(a, dict)):
            continue
        if isinstance(item, dict):
            alias_records.append({**item, 'kind': 'title'})
        else:
            source = next((e for e in evidence if normalized(name) in normalized(e.get('quote', '')) and e.get('retrievedEvidenceMatched')), None)
            if source:
                alias_records.append({'kind': 'title', 'name': name, **source})
    for alias in alias_records + writer_aliases:
        if not isinstance(alias, dict):
            continue
        if not alias.get('retrievedEvidenceMatched'):
            alias['retrievedEvidenceMatched'] = any(e.get('retrievedEvidenceMatched') and e.get('url') == alias.get('url')
                and normalized(alias.get('quote', '')) and normalized(alias.get('quote', '')) in normalized(e.get('quote', '')) for e in evidence)
    for alias in alias_records:
        if not isinstance(alias, dict) or alias.get('kind') not in {'title', 'writer', 'singer'}:
            reasons.append('Research contains an invalid alias record.')
            continue
        if alias.get('origin') == 'user_authorized_writer_alias':
            if (alias['kind'] != 'writer' or alias.get('canonicalName') not in canonical or alias.get('url')
                    or not alias.get('quote') or not re.search('[가-힣]', alias.get('name', ''))):
                reasons.append('A user-provided writer alias has invalid provenance or does not identify a credited writer.')
            continue
        if alias.get('kind') == 'title' and alias.get('name') and normalized(alias['name']) != normalized(title):
            quote = normalized(str(alias.get('quote', '')))
            if not (viewed_source_url(alias.get('url', '')) and alias.get('retrievedEvidenceMatched')
                    and normalized(alias['name']) in quote
                    and (normalized(original) in quote or normalized(singer) in quote)):
                reasons.append('A title alias lacks matched per-song evidence.')
                naming_review_required = True
        if alias.get('kind') in {'writer', 'singer'}:
            canonical_name = alias.get('canonicalName')
            if not canonical_name:
                exact = [name for name in canonical if normalized(name) == normalized(alias.get('name', ''))]
                canonical_name = exact[0] if len(exact) == 1 else None
            quote = normalized(str(alias.get('quote', '')))
            if alias['kind'] == 'singer':
                canonical_name = canonical_name or singer
            if (not canonical_name or (alias['kind'] == 'writer' and normalized(canonical_name) not in {normalized(n) for n in canonical}) or not viewed_source_url(alias.get('url', ''))
                    or not alias.get('retrievedEvidenceMatched') or not normalized(str(alias.get('name', '')))
                    or not (re.search(r'[가-힣]', str(alias.get('name', ''))) or (alias['kind'] == 'writer' and re.search(r'[가-힣]', canonical_name)))
                    or normalized(str(alias.get('name', ''))) not in quote or normalized(canonical_name) not in quote):
                reasons.append(f'A {alias["kind"]} alias needs matched evidence for both its name and canonical credit.')
    for alias in writer_aliases:
        quote = normalized(str(alias.get('quote', '')))
        name = normalized(str(alias.get('name', '')))
        canonical_name = alias.get('canonicalName')
        if (not canonical_name or not viewed_source_url(alias.get('url', '')) or not alias.get('retrievedEvidenceMatched')
                or not name or name not in quote or normalized(canonical_name) not in quote):
            reasons.append(f'Korean/original-name mapping for “{alias.get("canonicalName", "creator")}" lacks matched evidence quoting both names.')
    singer_aliases = [alias for alias in alias_records if isinstance(alias, dict) and alias.get('kind') == 'singer']
    tags, tag_reasons = make_tags(original, canonical, writer_aliases, singer, singer_aliases, alias_records, title)
    reasons.extend(tag_reasons)
    youtube_url = research.get('youtubeUrl')
    video_id = research.get('videoId')
    if video_id and (not isinstance(video_id, str) or not re.fullmatch(r'[A-Za-z0-9_-]{11}', video_id)):
        reasons.append('Video ID is malformed; canonical audio source needs review.')
        video_id = None
    if video_id and not canonical_youtube_url(youtube_url, video_id):
        reasons.append('Canonical YouTube URL is missing or invalid.')
        youtube_url = None
    if not video_id or not youtube_url:
        reasons.append('No valid canonical YouTube source was supplied.')
    state_file = scratch / 'download-state' / f'{video_id}.json' if video_id else None
    audio = {'status': 'pending', 'path': None, 'durationSeconds': None, 'sha256': None}
    if state_file and state_file.exists():
        state = load_json(state_file)
        if state.get('status') == 'ready':
            source = (scratch / 'audio' / f'{video_id}.ogg') if not state.get('path') else (scratch / state['path'])
            source = source.resolve()
            duration = state.get('durationSeconds')
            if (source.is_relative_to(scratch.resolve()) and source.is_file() and source.suffix.casefold() == '.ogg'
                    and isinstance(duration, (int, float)) and math.isfinite(duration) and duration > 0):
                digest = hashlib.sha256(source.read_bytes()).hexdigest()
                if not state.get('sha256') or state['sha256'] == digest:
                    audio = {'status': 'ready', 'path': str(source), 'durationSeconds': duration, 'sha256': digest}
        else:
            audio = {'status': 'failed', 'path': None, 'durationSeconds': None, 'sha256': None,
                     'failureReason': state.get('failureReason') or state.get('reason') or 'Download did not complete.'}
    audio_evidence = research.get('audioEvidence') or []
    if isinstance(audio_evidence, dict):
        audio_evidence = [audio_evidence]
    audio_verified = (research.get('audioSourceType') in {'official_album_audio', 'official_audio', 'topic_audio', 'subtitled_audio'}
                      and any(isinstance(e, dict) and e.get('retrievedEvidenceMatched') and e.get('quote')
                              and video_id and canonical_youtube_url(e.get('url'), video_id) for e in audio_evidence))
    if not audio_verified:
        reasons.append('Audio source type/evidence has not been verified as canonical.')
    reasons = list(dict.fromkeys(str(reason) for reason in reasons if str(reason).strip()))
    original_writers = [next((a['name'] for a in writer_aliases if a['canonicalName'] == writer), writer)
                        if re.search('[가-힣]', writer) else writer for writer in canonical]
    return {
        'id': song_id, 'index': official['index'], 'tjNumber': tj_number,
        'publishDate': official.get('publishdate'), 'title': title, 'titleOrigin': title_origin,
        'originalTitle': original, 'originalTitleExpanded': research.get('originalTitleExpanded'),
        'singer': singer, 'singerAliases': research.get('singerAliases') or [],
        'writers': canonical, 'writerOriginalNames': list(dict.fromkeys(original_writers)),
        'officialCredits': {'composer': official.get('com'), 'lyricist': official.get('word')},
        'creditCorrection': research.get('creditCorrection'),
        'namingSearches': research.get('searches', []),
        'writerAliases': writer_aliases, 'tags': tags, 'aliases': alias_records,
        'evidence': evidence, 'youtubeUrl': youtube_url, 'videoId': video_id,
        'audioSourceType': research.get('audioSourceType'), 'audioEvidence': research.get('audioEvidence'),
        'audioSourceReviewRequired': not audio_verified,
        'audio': audio, 'reviewRequired': bool(reasons), 'reviewReasons': reasons,
        'namingReviewRequired': bool(naming_review_required),
    }


def export_audio(song: dict, destination: Path, ffmpeg: str, source_root: Path | None = None) -> dict | None:
    audio = song['audio']
    if audio['status'] != 'ready':
        return None
    source = Path(audio['path'])
    if not source.is_absolute() and source_root:
        source = source_root / source
    if not source.is_file():
        raise ValueError(f'Ready scratch audio is missing: {song["tjNumber"]}')
    target = destination / 'audio' / f'{song["videoId"]}.ogg'
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.tj-remux-', dir=destination) as temp_dir:
        tagged = Path(temp_dir) / 'tagged.ogg'
        clean = Path(temp_dir) / 'clean.ogg'
        try:
            from mutagen.oggopus import OggOpus
        except ImportError:
            dep = ROOT / '.venv' / 'Lib' / 'site-packages'
            if dep.exists():
                sys.path.insert(0, str(dep))
            from mutagen.oggopus import OggOpus
        source_info = OggOpus(source).info
        if source_info.channels != 2:
            raise ValueError(f'Expected stereo Opus source: {song["tjNumber"]}')
        if audio.get('durationSeconds') and abs(source_info.length - float(audio['durationSeconds'])) > 2:
            raise ValueError(f'Scratch audio duration does not match download state: {song["tjNumber"]}')
        shutil.copyfile(source, tagged)
        opus = OggOpus(tagged)
        opus['TITLE'] = song['title']
        opus['COMPOSER'] = ', '.join(song['writers'])
        opus['ANSWER_TAG'] = ', '.join(song['tags'])
        opus.save()
        subprocess.run([ffmpeg, '-nostdin', '-v', 'error', '-y', '-i', str(tagged), '-map', '0:a:0',
                        '-map_metadata', '-1', '-c:a', 'copy', '-fflags', '+bitexact', str(clean)], check=True)
        # Ensure answer-bearing comments are absent from the server-delivered copy.
        delivered = OggOpus(clean)
        for key in list(delivered.keys()):
            if key.casefold() in {'answer_tag', 'comment', 'comments'} or key.startswith('COMM::'):
                del delivered[key]
        delivered.save()
        clean.replace(target)
    payload = target.read_bytes()
    return {'path': f'audio/{target.name}', 'sha256': hashlib.sha256(payload).hexdigest(),
            'byteLength': len(payload), 'durationMs': round(float(audio['durationSeconds']) * 1000)}


def _write_database(path: Path, catalogue: dict):
    path.parent.mkdir(parents=True, exist_ok=True)
    with closing(sqlite3.connect(path)) as db:
        db.executescript('''
        PRAGMA foreign_keys=ON;
        CREATE TABLE catalogue_info (key TEXT PRIMARY KEY, value TEXT NOT NULL);
        CREATE TABLE songs (id TEXT PRIMARY KEY, playlist_position INTEGER UNIQUE NOT NULL, tj_number INTEGER UNIQUE NOT NULL,
          publish_date TEXT, title TEXT NOT NULL, original_title TEXT NOT NULL, singer TEXT NOT NULL,
          youtube_url TEXT, review_required INTEGER NOT NULL, naming_review_required INTEGER NOT NULL,
          audio_source_review_required INTEGER NOT NULL, review_reasons TEXT NOT NULL);
        CREATE TABLE writers (id INTEGER PRIMARY KEY, canonical_name TEXT UNIQUE NOT NULL);
        CREATE TABLE song_writers (song_id TEXT NOT NULL REFERENCES songs(id), writer_id INTEGER NOT NULL REFERENCES writers(id),
          writer_order INTEGER NOT NULL, korean_alias TEXT, original_name TEXT, PRIMARY KEY(song_id, writer_id));
        CREATE TABLE song_tags (song_id TEXT NOT NULL REFERENCES songs(id), tag_order INTEGER NOT NULL, name TEXT NOT NULL,
          PRIMARY KEY(song_id, tag_order));
        CREATE TABLE evidence (song_id TEXT NOT NULL REFERENCES songs(id), evidence_order INTEGER NOT NULL, url TEXT NOT NULL,
          quote TEXT NOT NULL, kind TEXT NOT NULL, retrieved_evidence_matched INTEGER NOT NULL,
          PRIMARY KEY(song_id,evidence_order));
        CREATE TABLE audio (song_id TEXT PRIMARY KEY REFERENCES songs(id), status TEXT NOT NULL, path TEXT,
          duration_seconds REAL, sha256 TEXT);
        CREATE TABLE aliases (song_id TEXT NOT NULL REFERENCES songs(id), alias_order INTEGER NOT NULL, kind TEXT NOT NULL,
          name TEXT NOT NULL, canonical_name TEXT, url TEXT, quote TEXT, origin TEXT, PRIMARY KEY(song_id,alias_order));
        CREATE VIEW 이중언어곡목록 AS SELECT s.playlist_position AS 번호, s.title AS 한국어곡명, s.original_title AS 원곡명,
          (SELECT a.name FROM aliases a WHERE a.song_id=s.id AND a.kind='singer' ORDER BY a.alias_order LIMIT 1) AS 가수한국어명,
          s.singer AS 가수원문명, s.publish_date AS TJ등록일, s.tj_number AS TJ번호,
          (SELECT GROUP_CONCAT(COALESCE(sw.korean_alias, '[미확인] '||w.canonical_name), ', ') FROM song_writers sw
            JOIN writers w ON w.id=sw.writer_id WHERE sw.song_id=s.id ORDER BY sw.writer_order) AS 작가한국어명,
          (SELECT GROUP_CONCAT(sw.original_name, ', ') FROM song_writers sw
            WHERE sw.song_id=s.id ORDER BY sw.writer_order) AS 작가원문명,
          CASE s.review_required WHEN 0 THEN '검토 완료' ELSE '검토 필요' END AS 종합검토,
          CASE s.naming_review_required WHEN 0 THEN '검토 완료' ELSE '검토 필요' END AS 곡명검토,
          (SELECT a.status FROM audio a WHERE a.song_id=s.id) AS 음원상태
          FROM songs s ORDER BY s.playlist_position;
        CREATE VIEW 출처목록 AS SELECT s.playlist_position AS 번호,e.evidence_order+1 AS 출처번호,e.url AS 출처URL,
          e.quote AS 확인문구,e.kind AS 종류 FROM evidence e JOIN songs s ON s.id=e.song_id ORDER BY 번호,출처번호;
        CREATE VIEW 태그목록 AS SELECT s.playlist_position AS 번호,s.title AS 곡명,t.tag_order+1 AS 태그번호,t.name AS 태그
          FROM song_tags t JOIN songs s ON s.id=t.song_id ORDER BY 번호,태그번호;
        CREATE VIEW 검토대기곡 AS SELECT s.playlist_position AS 번호,s.title AS 곡명,s.tj_number AS TJ번호,
          CASE s.naming_review_required WHEN 1 THEN '곡명 통용 표기 확인' ELSE '' END AS 곡명작업,
          (SELECT GROUP_CONCAT(w.canonical_name, ', ') FROM song_writers sw JOIN writers w ON w.id=sw.writer_id
            WHERE sw.song_id=s.id AND sw.korean_alias IS NULL) AS 한글표기미확인작가,
          (SELECT GROUP_CONCAT(w.canonical_name, ', ') FROM song_writers sw JOIN writers w ON w.id=sw.writer_id
            WHERE sw.song_id=s.id AND sw.original_name IS NULL) AS 원문표기미확인작가,
          CASE s.audio_source_review_required WHEN 1 THEN '정규 음원 확인' ELSE '' END AS 음원작업,
          s.review_reasons AS 상세사유 FROM songs s WHERE s.review_required=1 ORDER BY 번호;
        CREATE VIEW 별칭목록 AS SELECT s.playlist_position AS 번호,s.title AS 곡명,a.kind AS 구분,
          a.name AS 별칭,a.canonical_name AS 원문이름,a.url AS 출처URL,a.quote AS 확인문구,a.origin AS 출처종류
          FROM aliases a JOIN songs s ON s.id=a.song_id ORDER BY 번호,a.alias_order;
        ''')
        db.executemany('INSERT INTO catalogue_info VALUES (?,?)', [('playlistId', catalogue['playlistId']), ('name', catalogue['name'])])
        for position, song in enumerate(catalogue['songs'], 1):
            db.execute('INSERT INTO songs VALUES (?,?,?,?,?,?,?,?,?,?,?,?)', (song['id'], position, song['tjNumber'],
              song['publishDate'], song['title'], song['originalTitle'], song['singer'], song.get('youtubeUrl'),
              int(song['reviewRequired']), int(song['namingReviewRequired']), int(song['audioSourceReviewRequired']),
              json.dumps(song['reviewReasons'], ensure_ascii=False)))
            for order, writer in enumerate(song['writers']):
                db.execute('INSERT INTO writers(canonical_name) VALUES (?) ON CONFLICT(canonical_name) DO NOTHING', (writer,))
                writer_id = db.execute('SELECT id FROM writers WHERE canonical_name=?', (writer,)).fetchone()[0]
                alias = next((a.get('name') for a in song['writerAliases'] if a.get('canonicalName') == writer), None)
                korean_name, original_name = (writer, alias) if re.search('[가-힣]', writer) else (alias, writer)
                db.execute('INSERT INTO song_writers VALUES (?,?,?,?,?)', (song['id'], writer_id, order, korean_name, original_name))
            aliases = list(song['aliases'])
            for alias in song['writerAliases']:
                if not any(a.get('kind')=='writer' and normalized(a.get('name',''))==normalized(alias['name'])
                           and a.get('canonicalName')==alias['canonicalName'] for a in aliases if isinstance(a,dict)):
                    aliases.append({**alias,'kind':'writer'})
            db.executemany('INSERT INTO aliases VALUES (?,?,?,?,?,?,?,?)', [
                (song['id'], n, alias['kind'], alias['name'], alias.get('canonicalName'), alias.get('url'), alias.get('quote'), alias.get('origin','retrieved_source'))
                for n, alias in enumerate(aliases) if isinstance(alias, dict) and alias.get('kind') and alias.get('name')])
            db.executemany('INSERT INTO song_tags VALUES (?,?,?)', [(song['id'], n, tag) for n, tag in enumerate(song['tags'])])
            db.executemany('INSERT INTO evidence VALUES (?,?,?,?,?,?)', [
                (song['id'], n, ev['url'], ev['quote'], ev['kind'], int(ev['retrievedEvidenceMatched']))
                for n, ev in enumerate(song['evidence']) if ev['url'] and ev['quote']])
            audio = song['audio']
            db.execute('INSERT INTO audio VALUES (?,?,?,?,?)', (song['id'], audio['status'], audio.get('path'),
                       audio.get('durationSeconds'), audio.get('sha256')))
        if db.execute('PRAGMA integrity_check').fetchone() != ('ok',):
            raise ValueError('SQLite integrity check failed')
        if db.execute('PRAGMA foreign_key_check').fetchall():
            raise ValueError('SQLite foreign key check failed')
        db.commit()


def create_database(path: Path, catalogue: dict):
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=path.parent, prefix='.tj-database-', suffix='.sqlite3', delete=False) as handle:
        temporary = Path(handle.name)
    try:
        _write_database(temporary, catalogue)
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def store_audio(song: dict, catalogue_dir: Path):
    if song['audio']['status'] != 'ready':
        return
    from mutagen.oggopus import OggOpus
    source = Path(song['audio']['path'])
    relative = Path('audio') / f'{song["videoId"]}.ogg'
    target = catalogue_dir / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix('.pending.ogg')
    shutil.copyfile(source, temporary)
    opus = OggOpus(temporary)
    opus['TITLE'] = song['title'] or song['originalTitle']
    opus['ARTIST'] = song['singer']
    opus['COMPOSER'] = song['writers']
    opus['ANSWER_TAG'] = song['tags']
    opus.save()
    temporary.replace(target)
    song['audio'].update(path=relative.as_posix(), byteLength=target.stat().st_size,
                         sha256=hashlib.sha256(target.read_bytes()).hexdigest(), format='ogg', codec='opus',
                         nominalBitrate=48000, channels=2)


def atomic_json(path: Path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name('.' + path.name + '.tmp')
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    temporary.replace(path)


def assemble(input_dir: Path = DEFAULT_INPUT, scratch: Path = DEFAULT_SCRATCH,
             catalogue_dir: Path = DEFAULT_CATALOGUE, playlist_dir: Path = DEFAULT_PLAYLIST,
             ffmpeg: str = 'ffmpeg', metadata_only: bool = False):
    selected, researched = load_inputs(input_dir)
    songs = [build_song(row, researched[row['index']], scratch) for row in selected]
    # Official chronology is the sole ordering authority; TJ number breaks same-day ties.
    songs.sort(key=lambda s: (publication_date(s['publishDate']), s['tjNumber']), reverse=True)
    if len({song['tjNumber'] for song in songs}) != 150:
        raise ValueError('Official selection contains duplicate TJ numbers')
    catalogue = {'schemaVersion': 1, 'playlistId': PLAYLIST_ID, 'name': PLAYLIST_NAME, 'playlistTitle': PLAYLIST_NAME,
                 'sourceDate': '2026-11-10', 'selectionKind': 'latest_registrations',
                 'chartSources': ['https://www.tjmedia.com/song/recent_song', 'https://www.tjmedia.com/legacy/api/newSongOfMonth'],
                 'songs': songs, 'summary': {'selected': 150,
                 'reviewed': sum(not song['reviewRequired'] for song in songs),
                 'namingReviewed': sum(not song['namingReviewRequired'] for song in songs),
                 'audioSourceReviewed': sum(not song['audioSourceReviewRequired'] for song in songs),
                 'reviewRequired': sum(song['reviewRequired'] for song in songs),
                 'audioReady': sum(song['audio']['status'] == 'ready' for song in songs)}}
    official_by_index = {row['index']: row for row in selected}
    source_selection = {'playlistId': PLAYLIST_ID, 'source': 'official TJ newest150 Japan selection',
                        'songs': [{'index': s['index'], 'tjNumber': s['tjNumber'], 'publishDate': s['publishDate'],
                                   'pro': official_by_index[s['index']].get('pro'),
                                   'indexTitle': official_by_index[s['index']].get('indexTitle'),
                                   'indexSong': official_by_index[s['index']].get('indexSong'),
                                   'word': official_by_index[s['index']].get('word'),
                                   'com': official_by_index[s['index']].get('com'),
                                   'categoryUrl': official_by_index[s['index']].get('categoryUrl'),
                                   'categoryVerified': official_by_index[s['index']].get('categoryVerified')}
                                  for s in songs]}
    review = {'playlistId': PLAYLIST_ID, 'count': catalogue['summary']['reviewRequired'],
              'songs': [{'index': s['index'], 'tjNumber': s['tjNumber'], 'title': s['title'], 'originalTitle': s['originalTitle'],
                         'reasons': s['reviewReasons']} for s in songs if s['reviewRequired']]}
    catalogue_dir.mkdir(parents=True, exist_ok=True)
    playlist_dir.mkdir(parents=True, exist_ok=True)
    (catalogue_dir / '.gitignore').write_text('audio/\n', encoding='utf-8')
    for song in songs:
        store_audio(song, catalogue_dir)
    atomic_json(catalogue_dir / 'catalogue.json', catalogue)
    atomic_json(catalogue_dir / 'source-selection.json', source_selection)
    atomic_json(catalogue_dir / 'summary.json', catalogue['summary'])
    atomic_json(catalogue_dir / 'review-required.json', review)
    create_database(catalogue_dir / 'catalogue.sqlite3', catalogue)
    (playlist_dir / '.gitignore').write_text('audio/\n', encoding='utf-8')
    (playlist_dir / 'audio').mkdir(exist_ok=True)
    manifest_songs, excluded = [], []
    for song in songs:
        if song['reviewRequired']:
            excluded.append({'index': song['index'], 'title': (song['title'] or song['originalTitle'])[:160], 'reasons': song['reviewReasons']})
            continue
        if not metadata_only and song['audio']['status'] == 'ready':
            exported_audio = export_audio(song, playlist_dir, ffmpeg, catalogue_dir)
        else:
            exported_audio = None
        manifest_songs.append({'song': {'id': song['id'], 'youtubeUrl': song['youtubeUrl'],
                                        'title': song['title'], 'tags': song['tags']}, 'audio': exported_audio})
    manifest = {'schemaVersion': 1, 'id': PLAYLIST_ID, 'name': PLAYLIST_NAME, 'songs': manifest_songs}
    if excluded:
        manifest['excludedSongs'] = excluded
    atomic_json(playlist_dir / 'playlist.json', manifest)
    catalogue['summary']['playable'] = sum(s['audio'] is not None for s in manifest_songs)
    atomic_json(catalogue_dir / 'catalogue.json', catalogue)
    atomic_json(catalogue_dir / 'summary.json', catalogue['summary'])
    return {'playlistId': PLAYLIST_ID, **catalogue['summary'], 'playable': sum(s['audio'] is not None for s in manifest_songs)}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input-dir', type=Path, default=DEFAULT_INPUT)
    parser.add_argument('--scratch', type=Path, default=DEFAULT_SCRATCH)
    parser.add_argument('--catalogue', type=Path, default=DEFAULT_CATALOGUE)
    parser.add_argument('--playlist', type=Path, default=DEFAULT_PLAYLIST)
    parser.add_argument('--ffmpeg', default=shutil.which('ffmpeg') or 'ffmpeg')
    parser.add_argument('--metadata-only', action='store_true', help='Build metadata exports without requiring audio readiness.')
    args = parser.parse_args(argv)
    print(json.dumps(assemble(args.input_dir, args.scratch, args.catalogue, args.playlist, args.ffmpeg, args.metadata_only), ensure_ascii=False))


if __name__ == '__main__':
    main()
