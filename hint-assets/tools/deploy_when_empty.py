#!/usr/bin/env python3
"""One reviewed HUD deployment, guarded by an in-process main-thread empty check."""
import argparse
import fcntl
import hashlib
import json
from pathlib import Path
import shutil
import socket
import struct
import subprocess
import time

SERVER = Path('/home/ubuntu/mc-luck-defense')
SERVICE = 'mc-luck-defense.service'
JAVA = '/usr/lib/jvm/java-25-openjdk-arm64/bin/java'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(*args, check=True, timeout=120):
    return subprocess.run(args, check=check, capture_output=True, text=True, timeout=timeout)


def varint(value):
    data = bytearray()
    while value > 127:
        data.append((value & 127) | 128)
        value >>= 7
    return bytes(data + bytes([value]))


def players():
    with socket.create_connection(('10.0.0.217', 25566), timeout=4) as stream:
        def exact(size):
            out = bytearray()
            while len(out) < size:
                part = stream.recv(size - len(out))
                if not part:
                    raise OSError('Truncated status')
                out.extend(part)
            return bytes(out)
        def integer():
            value = 0
            for i in range(5):
                byte = exact(1)[0]
                value |= (byte & 127) << (i * 7)
                if byte < 128:
                    return value
            raise ValueError('Invalid status integer')
        host = b'mud.danta.qzz.io'
        packet = b'\0' + varint(777) + varint(len(host)) + host + struct.pack('>H', 25566) + b'\1'
        stream.sendall(varint(len(packet)) + packet + b'\1\0')
        size = integer()
        if not 3 <= size <= 2 * 1024 * 1024 or integer() != 0:
            raise ValueError('Invalid status response')
        count = json.loads(exact(integer()))['players']['online']
        if type(count) is not int or count < 0:
            raise ValueError('Invalid player count')
        return count


def health():
    for _ in range(60):
        try:
            if run('systemctl', 'is-active', SERVICE, check=False).stdout.strip() == 'active':
                count = players()
                log = (SERVER / 'logs/latest.log').read_text(errors='replace')
                if any('Noqui' in line and any(error in line for error in ('Error occurred while enabling', 'Could not load', 'Disabling Noqui', 'startup failed', 'failed to start')) for line in log.splitlines()):
                    raise RuntimeError('Plugin startup failed')
                if '[Noqui] Noqui enabled;' in log and 'Done (' in log:
                    return count
        except (OSError, ValueError):
            pass
        time.sleep(2)
    raise RuntimeError('Minecraft startup did not complete')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--deploy', action='store_true')
    args = parser.parse_args()
    stage = Path(__file__).resolve().parent
    config = json.loads((stage / 'deployment.json').read_text())
    lock = (stage.parent / '.noqui-deploy.lock').open('w')
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    current = SERVER / 'plugins/Noqui.jar'
    candidate = stage / 'Noqui-hud.jar'
    for name, expected in config['artifacts'].items():
        if Path(name).name != name or sha(stage / name) != expected:
            raise ValueError('Reviewed artifact changed: ' + name)
    if (stage / 'completed.json').exists():
        print('already-completed')
        return
    if sha(current) != config['baselineSha256']:
        raise RuntimeError('Live plugin changed since review; refusing to overwrite another deployment')
    public_pack = Path('/var/www/noqui/packs') / config['packName']
    if sha(public_pack) != config['artifacts'][config['packName']]:
        raise ValueError('Public HUD pack differs from review')
    count = players()
    if not args.deploy or count:
        print(json.dumps({'status': 'waiting-for-empty' if count else 'ready', 'online': count, 'restartRequested': False}))
        return
    attempt = stage / ('attempt-' + str(time.time_ns()))
    attempt.mkdir(mode=0o700)
    pid = run('systemctl', 'show', '-p', 'MainPID', '--value', SERVICE).stdout.strip()
    if not pid.isdecimal() or int(pid) <= 0:
        raise RuntimeError('Minecraft process unavailable')
    attached = run(JAVA, '--add-modules', 'jdk.attach', '-cp', str(stage), 'AttachLive', pid,
                   str(stage / 'empty-hint-restart-agent-v1.jar'), str(attempt), check=False, timeout=35)
    marker = attempt / 'empty-check.txt'
    if not marker.exists():
        raise RuntimeError('No main-thread empty check completed; no service stop requested')
    result = marker.read_text().strip()
    if result.startswith('busy='):
        print(result)
        return
    if result != 'shutdown-requested; players=0; main-thread=true':
        raise RuntimeError('Invalid shutdown receipt')
    # Suppress Restart=always while the already-requested graceful shutdown saves.
    installed = False
    try:
        run('sudo', 'systemctl', 'stop', SERVICE, timeout=150)
        if sha(current) != config['baselineSha256']:
            raise RuntimeError('Plugin changed during shutdown; installation cancelled')
        shutil.copy2(current, attempt / 'Noqui-before.jar')
        temporary = current.with_name('Noqui-hud-install.tmp')
        shutil.copy2(candidate, temporary)
        temporary.replace(current)
        installed = True
        # Only plugin bytes changed; wallets, tickets, inventories and worlds stay in place.
        run('sudo', 'systemctl', 'start', SERVICE)
        online = health()
        receipt = {'status': 'completed', 'baselineSha256': config['baselineSha256'],
                   'pluginSha256': sha(current), 'onlineAtFinalCheck': 0, 'mainThreadCheck': True,
                   'onlineAfterStart': online, 'packSha256': sha(public_pack), 'completedAtUnix': time.time()}
        (stage / 'completed.json').write_text(json.dumps(receipt, indent=2) + '\n')
        print(json.dumps(receipt))
    except BaseException:
        if installed:
            run('sudo', 'systemctl', 'stop', SERVICE, check=False, timeout=150)
            shutil.copy2(attempt / 'Noqui-before.jar', current)
        run('sudo', 'systemctl', 'start', SERVICE, check=False)
        raise


if __name__ == '__main__':
    main()
