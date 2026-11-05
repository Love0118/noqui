import pathlib,subprocess,os,json,hashlib,time
ROOT=pathlib.Path('/home/ubuntu/mc-luck-defense-deploy/audio-playback-fix-20261106');ROOT.mkdir(exist_ok=True)
DEST=pathlib.Path('/opt/noqui/hotfixes/audio-bypass-20261106');DEST.mkdir(parents=True,exist_ok=True)
source=(ROOT/'media_bypass.py').read_bytes().replace(b'\r\n',b'\n');(DEST/'media_bypass.py').write_bytes(source);os.chmod(DEST,0o755);os.chmod(DEST/'media_bypass.py',0o644)
config=pathlib.Path('/etc/nginx/sites-enabled/noqui').resolve();original=config.read_bytes();(ROOT/'nginx-before.conf').write_bytes(original)
block='''    # TEMPORARY: authorize with the live Rust room, deliver the source without interval rendering.
    location ^~ /media/ {
        proxy_pass http://127.0.0.1:8793;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_buffering off;
    }
'''
text=original.decode();marker='    location = /app.js {'
assert marker in text and '127.0.0.1:8793' not in text
unit='''[Unit]
Description=Temporary Noqui authenticated audio bypass
After=network.target noqui-web.service
[Service]
User=ubuntu
Group=ubuntu
ExecStart=/usr/bin/python3 /opt/noqui/hotfixes/audio-bypass-20261106/media_bypass.py
Restart=on-failure
RestartSec=1
MemoryMax=128M
CPUQuota=50%
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=read-only
[Install]
WantedBy=multi-user.target
'''
pathlib.Path('/etc/systemd/system/noqui-media-bypass.service').write_text(unit)
subprocess.run(['systemctl','daemon-reload'],check=True);subprocess.run(['systemctl','enable','--now','noqui-media-bypass.service'],check=True)
temporary=config.with_suffix('.audio-bypass');temporary.write_text(text.replace(marker,block+marker,1));os.chmod(temporary,0o644);os.replace(temporary,config)
try:subprocess.run(['nginx','-t'],check=True,capture_output=True);subprocess.run(['systemctl','reload','nginx'],check=True)
except Exception:
    config.write_bytes(original);raise
receipt={'activated':True,'time':time.time(),'intervalRenderingBypassed':True,'authentication':'existing Rust session and current-room media, checked before delivery','minecraftRestarted':False,'audioServiceRestarted':False,'nginxConfig':str(config),'helperSha256':hashlib.sha256(source).hexdigest()};(ROOT/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');os.chmod(ROOT/'receipt.json',0o644);print(json.dumps(receipt))
