"""只验证执行器本身，使用专属进程内 HTTP 服务；结果不是 WMS 容量测量。"""
import importlib.util
import json
from pathlib import Path
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import unittest

spec = importlib.util.spec_from_file_location('capacity', Path(__file__).resolve().parents[1] / 'capacity-runner.py')
capacity = importlib.util.module_from_spec(spec)
spec.loader.exec_module(capacity)


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(429 if self.path.endswith('/limited') else 200)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        self.wfile.write(b'{"negativeBalances":0,"duplicatePostings":0}')

    def log_message(self, *args):
        pass


class CapacityRunnerTest(unittest.TestCase):
    def setUp(self):
        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.worker = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.worker.start()
        self.directory = tempfile.TemporaryDirectory()
        self.data = {'signedBy': 'runner-unit-fixture', 'signedAt': '2026-09-12T00:00:00Z', 'scenario': 'agreed-peak',
                     'D': 1, 'L': 1, 'P': 1, 'environment': 'isolated', 'targetUrl': f'http://127.0.0.1:{self.server.server_port}',
                     'load': {'durationSeconds': .1, 'ratePerSecond': 20, 'concurrency': 2, 'timeoutSeconds': 1,
                              'maxClientP99Ms': 1000, 'maxErrorRate': 0,
                              'requests': [{'path': '/api/wms/test', 'expectedStatuses': [200]}]},
                     'invariantChecks': [{'path': '/api/wms/check', 'expectedStatuses': [200],
                                          'equals': {'negativeBalances': 0, 'duplicatePostings': 0}}]}

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.worker.join()
        self.directory.cleanup()

    def test_reports_measured_requests_and_checks_invariants(self):
        report = capacity.run(self.data, Path(self.directory.name) / 'success')
        self.assertTrue(report['passed'])
        self.assertEqual(2, report['completed'])
        self.assertGreater(report['clientP99Ms'], 0)
        self.data['invariantChecks'][0]['equals']['negativeBalances'] = 1
        failed = capacity.run(self.data, Path(self.directory.name) / 'invariant-failed')
        self.assertFalse(failed['passed'])
        self.assertFalse(failed['invariantChecks'][0]['valid'])

    def test_rate_limit_is_not_success_and_missing_runner_input_rejects(self):
        self.data['load']['requests'][0]['path'] = '/api/wms/limited'
        report = capacity.run(self.data, Path(self.directory.name) / 'limited')
        self.assertFalse(report['passed'])
        self.assertEqual(2, report['errors'])
        del self.data['invariantChecks']
        with self.assertRaises(ValueError):
            capacity.validate(self.data)


if __name__ == '__main__':
    unittest.main()
