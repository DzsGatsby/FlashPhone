import importlib.util
import contextlib
import hashlib
import io
import json
from pathlib import Path
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('flashphone', Path(__file__).with_name('flashphone.py'))
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
TOKEN = 'synthetic-test-token-' + 'x' * 32
BODY = b'RIFF' + bytes(range(96))
RECORDINGS = {
    '00000000-0000-4000-8000-000000000001': (BODY, len(BODY)),
    '00000000-0000-4000-8000-000000000002': (BODY[:40], len(BODY)),
    '00000000-0000-4000-8000-000000000003': (b'z' * len(BODY), len(BODY)),
    '00000000-0000-4000-8000-000000000004': (BODY[:40], 40),
}

class Handler(BaseHTTPRequestHandler):
    count = 0
    tool_error = False
    def log_message(self, *args): pass
    def do_POST(self):
        type(self).count += 1
        assert self.headers['Authorization'] == 'Bearer ' + TOKEN
        data = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        if data.get('method') == 'redirect':
            self.send_response(302); self.send_header('Location', 'http://127.0.0.1:1/steal'); self.end_headers(); return
        if data.get('method') == 'unavailable':
            self.send_response(503); self.end_headers(); return
        params = data.get('params') or {}
        if params.get('name') == 'flashphone_recording':
            task = params.get('arguments', {}).get('task_id')
            assert task in RECORDINGS
            result = {'isError': False, 'structuredContent': {'recording': {'bytes': len(BODY), 'sha256': hashlib.sha256(BODY).hexdigest()}}}
        elif self.tool_error:
            result = {'isError': True, 'content': [{'type': 'text', 'text': json.dumps({'error': {'code': 'device_not_ready', 'message': '设备未就绪'}}, ensure_ascii=False)}]}
        else:
            result = {'received': data.get('params')}
        payload = json.dumps({'jsonrpc':'2.0','id':data.get('id'),'result':result}).encode()
        self.send_response(200); self.send_header('Content-Type','application/json'); self.end_headers(); self.wfile.write(payload)

    def do_GET(self):
        assert self.headers['Authorization'] == 'Bearer ' + TOKEN
        task = self.path.split('/')[3]
        body, declared = RECORDINGS[task]
        self.send_response(200)
        self.send_header('Content-Type', 'audio/wav')
        self.send_header('Content-Length', str(declared))
        self.end_headers()
        self.wfile.write(body)
        self.close_connection = True

class ClientTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(('127.0.0.1',0),Handler)
        cls.thread=threading.Thread(target=cls.server.serve_forever,daemon=True); cls.thread.start()
        cls.client=m.FlashClient('http://127.0.0.1:'+str(cls.server.server_port),TOKEN,allow_loopback=True)
    @classmethod
    def tearDownClass(cls): cls.server.shutdown(); cls.server.server_close(); cls.thread.join()
    def test_origin_and_secret_storage_fail_closed(self):
        for url in ['http://example.com','https://user:secret@example.com','https://example.com/query?token=x','https://example.com/#token']:
            with self.assertRaises(m.ClientError): m.FlashClient(url,TOKEN)
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/'config.json'; path.write_text(json.dumps({'origin':'https://example.com','token':TOKEN})); path.chmod(0o644)
            with self.assertRaises(m.ClientError): m.FlashClient.from_file(path)
            path.chmod(0o600); self.assertEqual(m.FlashClient.from_file(path).origin,'https://example.com')
    def test_tools_return_same_structured_result(self):
        result=self.client.tool('flashphone_task_status',{'task_id':'synthetic'})
        self.assertEqual(result['received']['arguments']['task_id'],'synthetic')
    def test_redirect_never_forwards_credentials(self):
        with self.assertRaises(m.ClientError): self.client.rpc({'id':'test','method':'redirect'})
    def test_failure_never_retries_a_possible_dial(self):
        before=Handler.count
        with self.assertRaises(m.ClientError): self.client.rpc({'id':'test','method':'unavailable'})
        self.assertEqual(Handler.count,before+1)

    def test_truncated_recording_never_commits_final_or_temporary_file(self):
        with tempfile.TemporaryDirectory() as temp:
            target=Path(temp)/'call.wav'
            with self.assertRaises(m.ClientError):
                self.client.recording('00000000-0000-4000-8000-000000000002',target)
            self.assertFalse(target.exists());self.assertEqual(list(Path(temp).iterdir()),[])
    def test_wrong_sha_recording_never_commits_final_or_temporary_file(self):
        with tempfile.TemporaryDirectory() as temp:
            target=Path(temp)/'call.wav'
            with self.assertRaises(m.ClientError):
                self.client.recording('00000000-0000-4000-8000-000000000003',target)
            self.assertFalse(target.exists());self.assertEqual(list(Path(temp).iterdir()),[])
    def test_wrong_header_length_never_commits_recording(self):
        with tempfile.TemporaryDirectory() as temp:
            target=Path(temp)/'call.wav'
            with self.assertRaises(m.ClientError):
                self.client.recording('00000000-0000-4000-8000-000000000004',target)
            self.assertFalse(target.exists());self.assertEqual(list(Path(temp).iterdir()),[])
    def test_verified_recording_commits_same_bytes_privately(self):
        with tempfile.TemporaryDirectory() as temp:
            target=Path(temp)/'call.wav'
            result=self.client.recording('00000000-0000-4000-8000-000000000001',target)
            self.assertEqual(target.read_bytes(),BODY)
            self.assertEqual(result['sha256'],hashlib.sha256(BODY).hexdigest())
            self.assertEqual(target.stat().st_mode & 0o777,0o600)
            self.assertEqual(list(Path(temp).iterdir()),[target])
    def test_cli_tool_error_is_nonzero_and_keeps_readable_server_error(self):
        output=io.StringIO()
        with patch.object(Handler,'tool_error',True),patch.object(m.FlashClient,'from_file',return_value=self.client),patch('sys.argv',['flashphone','devices']),contextlib.redirect_stdout(output):
            status=m.main()
        result=json.loads(output.getvalue())
        self.assertNotEqual(status,0)
        self.assertTrue(result['isError'])
        self.assertEqual(json.loads(result['content'][0]['text'])['error']['message'],'设备未就绪')
        self.assertNotIn(TOKEN,output.getvalue())

if __name__=='__main__': unittest.main()
