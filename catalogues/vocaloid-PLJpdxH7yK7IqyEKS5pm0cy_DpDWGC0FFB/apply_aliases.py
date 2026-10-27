"""Apply evidence-backed Korean aliases without treating title aliases as writers."""
import argparse
import json
from pathlib import Path
import re
import unicodedata

ROOT = Path(__file__).resolve().parent


def normalized(value):
    return re.sub('[^0-9A-Za-z가-힣]', '', value).lower()


def apply(research):
    catalogue = json.loads((ROOT / 'catalogue.json').read_text(encoding='utf-8'))
    by_writer, by_title = {}, {}
    report = {'research': [], 'unresolved': [], 'tagLimit': []}
    for path in sorted(Path(research).glob('output-*.json')):
        entries = json.loads(path.read_text(encoding='utf-8'))
        target = by_writer if 'writers' in path.name else by_title
        input_path = path.with_name(path.name.replace('output-', 'input-'))
        inputs = json.loads(input_path.read_text(encoding='utf-8'))
        expected = [item if isinstance(item, str) else item['videoId'] for item in inputs]
        if [entry['key'] for entry in entries] != expected:
            raise ValueError(f'Research batch is incomplete or reordered: {path.name}')
        for entry in entries:
            accepted = []
            for alias in entry['aliases']:
                name = unicodedata.normalize('NFC', alias['name']).strip()
                if not re.search('[가-힣]', name) or not 0 < len(name) <= 32 or normalized(name) not in normalized(alias['quote']):
                    raise ValueError(f'Alias lacks matching Korean usage: {entry["key"]}: {name}')
                if not alias['url'].startswith(('http://', 'https://')) or 'search.naver.com' in alias['url']:
                    raise ValueError('Alias needs a specific source URL')
                accepted.append({**alias, 'name': name})
            target[entry['key']] = accepted
            if not accepted:
                report['unresolved'].append({'kind': 'writer' if target is by_writer else 'title', **entry})
        report['research'].append({'batch': path.name, 'entries': len(entries), 'acceptedNames': sum(len(e['aliases']) for e in entries)})
    for song in catalogue['songs']:
        song.setdefault('writers', list(song['tags']))
        aliases = []
        for writer in song['writers']:
            aliases.extend({'kind': 'writer', 'writer': writer, **alias} for alias in by_writer.get(writer, []))
        aliases.extend({'kind': 'title', **alias} for alias in by_title.get(song['videoId'], []))
        stripped = ''.join(c for c in song['title'] if unicodedata.category(c)[0] not in 'PS')
        stripped = ' '.join(stripped.split())
        if stripped != song['title'] and normalized(stripped):
            aliases.append({'kind': 'punctuation', 'name': stripped, 'url': None, 'quote': song['title']})
        tags = list(song['writers'])
        seen = {normalized(tag) for tag in tags}
        for alias in aliases:
            if normalized(alias['name']) in seen:
                continue
            if len(alias['name']) > 32 or len(tags) >= 10:
                report['tagLimit'].append({'videoId': song['videoId'], 'alias': alias['name']})
                continue
            seen.add(normalized(alias['name']))
            tags.append(alias['name'])
        song['aliases'], song['tags'] = aliases, tags
    report['songsWithAliases'] = sum(bool(song['aliases']) for song in catalogue['songs'])
    report['writerAliasSongs'] = sum(any(a['kind'] == 'writer' for a in s['aliases']) for s in catalogue['songs'])
    report['titleAliasSongs'] = sum(any(a['kind'] == 'title' for a in s['aliases']) for s in catalogue['songs'])
    report['punctuationSongs'] = sum(any(a['kind'] == 'punctuation' for a in s['aliases']) for s in catalogue['songs'])
    write('catalogue.json', catalogue)
    write('review-required.json', [s for s in catalogue['songs'] if s['reviewRequired']])
    write('download-failures.json', [s for s in catalogue['songs'] if s['audio']['status'] != 'ready'])
    verified = [{k: s[k] for k in ('id', 'youtubeUrl', 'title', 'tags')} for s in catalogue['songs'] if not s['reviewRequired']]
    for start in range(0, len(verified), 50):
        write(f'import-batch-{start // 50 + 1:02d}.json', {'schemaVersion': 1, 'songs': verified[start:start+50]})
    write('alias-research.json', report)
    print(json.dumps({k: v for k, v in report.items() if k not in ('unresolved', 'research')}, ensure_ascii=False))


def write(name, data):
    (ROOT / name).write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('research', type=Path)
    apply(parser.parse_args().research)
