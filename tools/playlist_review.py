"""Shared completion criteria for curated Korean song names and playable exports."""
import re
import unicodedata
from urllib.parse import unquote, urlparse


def normalize(value):
    return re.sub('[^0-9A-Za-z가-힣]', '', value or '').lower()


def numeric_title(title):
    return bool(re.search('[0-9]',title)) and all(c in '0123456789' or c.isspace() or unicodedata.category(c)[0] in 'PS' for c in title)


def title_usage_verified(song):
    title = song.get('title', '')
    original = song.get('originalTitle', '')
    if (song.get('titleOrigin') == 'user_authorized_english_pronunciation' and song.get('namingSearches')
            and re.search('[가-힣]', title) and re.search('[A-Za-z]', original)
            and not re.search('[ぁ-ゖァ-ヺ一-龯가-힣]', original)):
        return True
    numeric = numeric_title(title)
    if not re.search('[가-힣]', title) and not numeric:
        return False
    key = normalize(title)
    for evidence in song.get('evidence', []):
        url = urlparse(evidence.get('url', ''))
        if (url.scheme in {'http', 'https'} and url.hostname
                and url.hostname not in {'search.naver.com', 'm.search.naver.com', 'www.google.com', 'www.bing.com'}
                and url.path not in {'', '/', '/search', '/search.naver'}
                and '나무위키:대문' not in unquote(url.path)
                and evidence.get('retrievedEvidenceMatched')
                and key in normalize(evidence.get('quote', ''))):
            if not numeric or re.search(r'(?<!\d)'+re.escape(''.join(title.split()))+r'(?!\d)', ''.join(evidence.get('quote','').split())):
                return True
    return False


def naming_review_reasons(song):
    reasons = list(song.get('namingReviewReasons') or ['통용 한국어 곡명·발음 표기 검토가 완료되지 않았습니다.']) if song.get('namingReviewRequired') else []
    title=song.get('title','')
    numeric=numeric_title(title)
    if not re.search('[가-힣]', title) and not numeric:
        reasons.append('표시 곡명의 통용 한국어 이름·발음을 확인하지 않았습니다.')
    elif not title_usage_verified(song):
        reasons.append('표시한 한국어 곡명이 실제 사용된 개별 출처가 필요합니다.')
    return list(dict.fromkeys(reasons))


def review_reasons(song):
    reasons = list(song.get('reviewReasons') or [song.get('notes') or '곡 정체·작가 크레딧 검토가 완료되지 않았습니다.']) if song.get('reviewRequired') else []
    return list(dict.fromkeys(reasons + naming_review_reasons(song)))


def reviewed(song):
    return not review_reasons(song)


def playable(song):
    return reviewed(song) and song.get('audio', {}).get('status') == 'ready' and not song.get('audioSourceReviewRequired', False)
