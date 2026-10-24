"""Build the one-line GSit 3.7.0 crawl-toggle fix without rebuilding other MC adapters.

Requires JDK 25, Python 3 and a Paper 26.3 libraries directory. No downloads,
permission changes or live-server writes are performed by this script.
"""
from pathlib import Path
import argparse
import difflib
import hashlib
import json
import os
import re
import subprocess
import zipfile

ORIGINAL_JAR_SHA256 = '93214c232fe535a6457ad8711cb1e05d0e730a491ce303e39e30c76f88dd2e30'
ORIGINAL_CLASS_SHA256 = '71bdd74194c1019ec3940681f60e3958e2d2f5189cb1feb6295d368adf4f843b'
ORIGINAL_SOURCE_SHA256 = '46c358aab521acd7bbf53bb74aa6826ed78e28d54ca9ba52bbc86cefcad7807f'
CLASS_NAME = 'dev.geco.gsit.cmd.GCrawlCommand'
CLASS_ENTRY = CLASS_NAME.replace('.', '/') + '.class'
OLD = 'if(gSitMain.getPermissionService().hasPermission(sender, "CrawlToggle", "Crawl.*")) {'
NEW = 'if(!gSitMain.getPermissionService().hasPermission(sender, "CrawlToggle", "Crawl.*")) {'
def sha(data):
    return hashlib.sha256(data).hexdigest()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--original-jar', type=Path, required=True)
    parser.add_argument('--paper-libraries', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--javac', default='javac')
    parser.add_argument('--javap', default='javap')
    args = parser.parse_args()
    original = args.original_jar.resolve()
    output = args.output.resolve()
    source = Path(__file__).parent / 'gsit-source/GCrawlCommand.java'
    raw = source.read_bytes()
    assert sha(original.read_bytes()) == ORIGINAL_JAR_SHA256, 'Unexpected upstream jar'
    assert sha(raw) == ORIGINAL_SOURCE_SHA256, 'Unexpected pinned source'
    text = raw.decode('utf-8')
    assert text.count(OLD) == 1, 'Expected permission branch is not unique'
    patched = text.replace(OLD, NEW)
    output.mkdir(parents=True, exist_ok=True)
    artifact = output / 'GSit-3.7.0-noqui.1.jar'
    assert artifact != original
    libraries = sorted(args.paper_libraries.resolve().rglob('*.jar'))
    assert any('paper-api' in path.name for path in libraries), 'Paper 26.3 API is required'
    assert any(path.name.startswith('annotations-') for path in libraries), 'JetBrains annotations are required'
    compiler = subprocess.check_output([args.javac, '-version'], stderr=subprocess.STDOUT, text=True).strip()
    assert compiler.startswith('javac 25'), compiler
    classpath = os.pathsep.join([str(original), *map(str, libraries)])
    classes = {}
    for name, content in [('original', text), ('patched', patched)]:
        work = output / name
        java = work / 'src/dev/geco/gsit/cmd/GCrawlCommand.java'
        java.parent.mkdir(parents=True, exist_ok=True)
        java.write_bytes(content.encode('utf-8'))
        target = work / 'classes'
        target.mkdir(exist_ok=True)
        subprocess.run([args.javac, '--release', '16', '-g', '-encoding', 'UTF-8', '-cp', classpath, '-d', str(target), str(java)], check=True)
        classes[name] = (target / CLASS_ENTRY).read_bytes()
    differences = [index for index, (left, right) in enumerate(zip(classes['original'], classes['patched'])) if left != right]
    assert len(classes['original']) == len(classes['patched']) and len(differences) == 1, 'Patch changed more than one compiled opcode'
    assert sha(classes['original']) == ORIGINAL_CLASS_SHA256, 'Pinned source does not reproduce upstream command class bytes'
    offset = differences[0]
    assert classes['original'][offset] == 0x99 and classes['patched'][offset] == 0x9a, 'Expected IFEQ to IFNE'
    def disassemble(path):
        result = subprocess.check_output([args.javap, '-classpath', str(path), '-c', '-p', CLASS_NAME], text=True)
        return re.sub(r'#\d+', '#', result).replace('\r\n', '\n')
    assert disassemble(original) == disassemble(output / 'original/classes'), 'Pinned source does not reproduce upstream command bytecode'
    with zipfile.ZipFile(original) as archive:
        assert sha(archive.read(CLASS_ENTRY)) == ORIGINAL_CLASS_SHA256, 'Unexpected upstream command class'
        descriptor = archive.read('plugin.yml')
        version = re.compile(rb'(?m)^version: 3\.7\.0(\r?)$')
        assert len(version.findall(descriptor)) == 1
        descriptor = version.sub(rb'version: 3.7.0.1\1', descriptor)
        old_description = b'description: Relax with other players on nice seats!'
        assert descriptor.count(old_description) == 1
        descriptor = descriptor.replace(old_description, old_description + b' [Noqui local compatibility patch 1]')
        with zipfile.ZipFile(artifact, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as fixed:
            for info in archive.infolist():
                value = classes['patched'] if info.filename == CLASS_ENTRY else descriptor if info.filename == 'plugin.yml' else archive.read(info.filename)
                fixed.writestr(info, value, compress_type=info.compress_type, compresslevel=9)
    with zipfile.ZipFile(original) as before, zipfile.ZipFile(artifact) as after:
        assert before.namelist() == after.namelist()
        changed = [name for name in before.namelist() if before.read(name) != after.read(name)]
        assert sorted(changed) == sorted([CLASS_ENTRY, 'plugin.yml'])
        changed_entries = [{'path': name, 'beforeSha256': sha(before.read(name)), 'afterSha256': sha(after.read(name))} for name in changed]
    (output / 'source.patch').write_text(''.join(difflib.unified_diff(text.splitlines(True), patched.splitlines(True), fromfile='a/core/src/main/java/dev/geco/gsit/cmd/GCrawlCommand.java', tofile='b/core/src/main/java/dev/geco/gsit/cmd/GCrawlCommand.java')), encoding='utf-8')
    proof = {'upstreamVersion': '3.7.0', 'localPatch': 'noqui.1', 'pluginVersion': '3.7.0.1', 'compiler': compiler, 'releaseTarget': 16,
             'originalJarSha256': ORIGINAL_JAR_SHA256, 'originalClassSha256': ORIGINAL_CLASS_SHA256, 'originalSourceSha256': ORIGINAL_SOURCE_SHA256,
             'compiledOriginalClassSha256': sha(classes['original']), 'patchedClassSha256': sha(classes['patched']), 'singleOpcodeDifference': {'classFileOffset': offset, 'before': 'IFEQ (0x99)', 'after': 'IFNE (0x9a)'},
             'upstreamDisassemblyReproduced': True, 'changedJarEntries': changed_entries, 'artifact': str(artifact), 'artifactSha256': sha(artifact.read_bytes()), 'artifactBytes': artifact.stat().st_size,
             'versionNote': 'Numeric 3.7.0.1 preserves upstream numeric update comparison; filename/description explicitly identify the local compatibility patch.'}
    (output / 'patch-proof.json').write_text(json.dumps(proof, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(proof, indent=2))

if __name__ == '__main__':
    main()
