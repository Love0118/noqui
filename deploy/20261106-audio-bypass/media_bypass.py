"""Temporary full-source delivery, authorized by the existing live room/session."""
import http.server,json,pathlib,re,sqlite3,threading,urllib.request,urllib.error
ROOT=pathlib.Path('/var/lib/noqui');LIMIT=threading.BoundedSemaphore(32)
LIBRARY={}
for manifest in (ROOT/'library').glob('*/playlist.json'):
    for track in json.loads(manifest.read_text()).get('songs',[]):
        audio=track.get('audio')
        if audio:
            path=(manifest.parent/audio['path']).resolve()
            if path.is_relative_to(manifest.parent.resolve()):LIBRARY[audio['sha256']]=path
class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version='HTTP/1.1'
    def log_message(self,*args):pass
    def error(self,status):
        self.send_response(status);self.send_header('Content-Length','0');self.send_header('Cache-Control','no-store');self.end_headers()
    def state(self):
        request=urllib.request.Request('http://127.0.0.1:8787/api/v1/state',headers={'Cookie':self.headers.get('Cookie','')})
        with urllib.request.urlopen(request,timeout=5) as response:
            data=response.read(65537)
            if len(data)>65536:raise ValueError('state too large')
            state=json.loads(data);media=state.get('media') or {}
            return media.get('url','').split('?',1)[0],(state.get('round') or {}).get('roundId')
    def do_HEAD(self):self.deliver(False)
    def do_GET(self):self.deliver(True)
    def deliver(self,body):
        if not LIMIT.acquire(blocking=False):return self.error(503)
        source=None
        try:
            path=self.path.split('?',1)[0];match=re.fullmatch(r'/media/([a-f0-9]{64})\.ogg',path)
            if not match:return self.error(404)
            permission=self.state()
            if permission[0]!=path:return self.error(404)
            sha=match[1];candidate=ROOT/'media'/(sha+'.ogg')
            if not candidate.is_file():candidate=LIBRARY.get(sha)
            if candidate is None or not candidate.is_file():return self.error(404)
            source=candidate.open('rb');length=candidate.stat().st_size
            if not 0<length<=25*1024*1024:return self.error(422)
            if self.state()!=permission:return self.error(404)
            start,end,status=0,length-1,200
            value=self.headers.get('Range','')
            if value:
                part=re.fullmatch(r'bytes=(\d*)-(\d*)',value)
                if not part or not any(part.groups()):return self.error(416)
                left,right=part.groups()
                if left:start=int(left);end=min(int(right),end) if right else end
                else:start=max(0,length-int(right))
                if start>=length or end<start:return self.error(416)
                status=206
            self.send_response(status)
            self.send_header('Content-Type','audio/ogg; codecs=opus');self.send_header('Content-Length',str(end-start+1));self.send_header('Accept-Ranges','bytes')
            self.send_header('Cache-Control','private, no-store');self.send_header('X-Content-Type-Options','nosniff')
            if status==206:self.send_header('Content-Range',f'bytes {start}-{end}/{length}')
            self.end_headers()
            if body:
                source.seek(start);remaining=end-start+1
                while remaining:
                    chunk=source.read(min(65536,remaining))
                    if not chunk:break
                    self.wfile.write(chunk);remaining-=len(chunk)
        except urllib.error.HTTPError as error:self.error(error.code)
        except (BrokenPipeError,ConnectionResetError):pass
        except Exception:self.error(503)
        finally:
            if source:source.close()
            LIMIT.release()
http.server.ThreadingHTTPServer(('127.0.0.1',8793),Handler).serve_forever()
