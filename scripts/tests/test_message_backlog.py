"""通过专属HTTP夹具验证告警分级；不代表部署了生产监控。"""
import importlib.util
import json
from pathlib import Path
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import urllib.parse
import unittest

spec = importlib.util.spec_from_file_location('backlog', Path(__file__).resolve().parents[1] / 'check-message-backlog.py')
backlog = importlib.util.module_from_spec(spec)
spec.loader.exec_module(backlog)


class MessageBacklogTest(unittest.TestCase):
    def setUp(self):
        self.mode = 'healthy'
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                if self.headers.get('Authorization') != 'Bearer fixture-only':
                    self.send_response(401)
                    self.end_headers()
                    return
                path = urllib.parse.urlsplit(self.path)
                tags = urllib.parse.parse_qs(path.query).get('tag', [])
                value = 0
                if path.path.endswith('sample.available'):
                    value = 1
                if owner.mode == 'stale' and path.path.endswith('sample.age'):
                    value = 100
                if owner.mode == 'isolated' and path.path.endswith('backlog') and 'state:ISOLATED' in tags:
                    value = 1
                if owner.mode == 'old' and 'state:PENDING' in tags:
                    value = 120 if path.path.endswith('oldest.age') else 1
                self.send_response(200)
                self.end_headers()
                self.wfile.write(json.dumps({'measurements': [{'statistic': 'VALUE', 'value': value}]}).encode())

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.worker = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.worker.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.worker.join()

    def run_check(self, token='fixture-only'):
        return backlog.check(f'http://127.0.0.1:{self.server.server_port}', token, 'INVENTORY_OUTBOX', 10, 60, 15)

    def test_healthy_isolated_old_and_stale_are_distinct(self):
        self.assertEqual(0, self.run_check()[1])
        for mode, code, alert in [('isolated', 1, 'MESSAGE_ISOLATED'), ('old', 1, 'MESSAGE_BACKLOG'), ('stale', 2, 'SAMPLE_STALE')]:
            self.mode = mode
            report, actual = self.run_check()
            self.assertEqual(code, actual)
            self.assertEqual(alert, report['alerts'][0]['code'])

    def test_authentication_and_invalid_thresholds_do_not_report_success(self):
        with self.assertRaises(OSError):
            self.run_check('wrong')
        with self.assertRaises(ValueError):
            backlog.check('http://127.0.0.1', 'fixture-only', 'INVENTORY_OUTBOX', 1001, 60, 15)


if __name__ == '__main__':
    unittest.main()
