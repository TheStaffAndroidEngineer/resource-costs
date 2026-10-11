#!/usr/bin/env python3
"""Local-only deterministic HTTP fixture. Logs received/emitted HTTP bytes, not radio bytes."""
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from pathlib import Path
import argparse, base64, hashlib, json, threading, time

PRODUCTS = [('camera',1299900),('tea',45000),('bag',249000),('lamp',199000),('mug',69000),('headphones',599000)]
LOCK = threading.Lock()
STATE = {'revision':1,'status':200,'delay_ms':0,'malformed':False}
RECORDS = []

class HeaderReader:
    def __init__(self, stream): self.stream=stream; self.lines=[]
    def readline(self, *args):
        value=self.stream.readline(*args); self.lines.append(value); return value
    def __getattr__(self, name): return getattr(self.stream,name)

class Handler(BaseHTTPRequestHandler):
    protocol_version='HTTP/1.1'
    def log_message(self, *args): pass
    def parse_request(self):
        original=self.rfile; wrapped=HeaderReader(original); self.rfile=wrapped
        try: return super().parse_request()
        finally: self.raw_request_headers=self.raw_requestline+b''.join(wrapped.lines); self.rfile=original
    def send(self, code, body=b'', etag=None, record=False):
        self.send_response(code)
        self.send_header('Content-Type','application/json; charset=utf-8')
        if code != 304: self.send_header('Content-Length',str(len(body)))
        self.send_header('Cache-Control','private, max-age=300')
        if etag: self.send_header('ETag',etag)
        self.send_header('Connection','close')
        headers=b''.join(self._headers_buffer)+b'\r\n'
        self.end_headers()
        if body: self.wfile.write(body)
        self.wfile.flush(); self.close_connection=True
        if record:
            row={'sequence':0,'path':self.path,'status':code,'validator':self.headers.get('If-None-Match'),
                 'request_http_bytes':len(self.raw_request_headers),'response_header_bytes':len(headers),
                 'response_entity_bytes':len(body),'entity_sha256':hashlib.sha256(body).hexdigest(),
                 'request_base64':base64.b64encode(self.raw_request_headers).decode(),
                 'response_headers_base64':base64.b64encode(headers).decode(),
                 'entity_base64':base64.b64encode(body).decode()}
            with LOCK:
                row['sequence']=len(RECORDS)+1; RECORDS.append(row)
                if self.server.log_path:
                    with self.server.log_path.open('a') as f:f.write(json.dumps(row)+'\n')
    def do_GET(self):
        if self.path=='/admin/records':
            with LOCK: body=json.dumps({'state':STATE.copy(),'records':list(RECORDS)}).encode()
            self.send(200,body); return
        with LOCK: settings=STATE.copy()
        if not (self.path=='/prices' or self.path.startswith('/prices/')):self.send(404,b'{}');return
        time.sleep(settings['delay_ms']/1000)
        if settings['status']!=200:self.send(settings['status'],b'{"error":"controlled fixture failure"}',record=True);return
        etag='"prices-'+str(settings['revision'])+'"'
        if self.path=='/prices' and self.headers.get('If-None-Match')==etag:
            self.send(304,etag=etag,record=True);return
        rows=[{'id':name,'cents':price-(100000 if name=='camera' and settings['revision']>1 else 0),'available':True} for name,price in PRODUCTS]
        if self.path!='/prices':rows=[r for r in rows if r['id']==self.path.removeprefix('/prices/')]
        document={'revision':settings['revision'],'prices':rows,'editorial':' '.join((r['id']+': Considered goods for everyday living. ')*18 for r in rows)}
        body=b'{broken' if settings['malformed'] else json.dumps(document,separators=(',',':'),ensure_ascii=False).encode()
        self.send(200,body,etag,record=True)
    def do_POST(self):
        size=int(self.headers.get('Content-Length','0'))
        if size>4096:self.send(413,b'{}');return
        try: payload=json.loads(self.rfile.read(size) or b'{}')
        except Exception:self.send(400,b'{}');return
        with LOCK:
            if self.path=='/admin/reset':
                RECORDS.clear();STATE.update(revision=1,status=200,delay_ms=0,malformed=False)
            elif self.path=='/admin/config':
                if not set(payload)<=set(STATE):self.send(400,b'{}');return
                STATE.update(payload)
            else:self.send(404,b'{}');return
            result=json.dumps(STATE).encode()
        self.send(200,result)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--port',type=int,default=8874);p.add_argument('--log',type=Path);a=p.parse_args()
    if a.log:
        a.log.parent.mkdir(parents=True,exist_ok=True)
        if a.log.exists():raise SystemExit('Use a new log path; existing evidence is never replaced.')
    server=ThreadingHTTPServer(('127.0.0.1',a.port),Handler);server.log_path=a.log
    print(f'Common Goods fixture: http://127.0.0.1:{a.port}',flush=True)
    try:server.serve_forever()
    except KeyboardInterrupt:pass
    finally:server.server_close()
