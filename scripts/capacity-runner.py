#!/usr/bin/env python3
"""执行已签署的 HTTP 负载；客户端延迟不冒充服务端 TP99，验收前必须检查业务不变量。"""
from __future__ import annotations
import argparse
import concurrent.futures
import csv
import hashlib
import json
import math
import os
from pathlib import Path
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone


def validate(data):
    for key in ('signedBy', 'signedAt', 'scenario', 'D', 'L', 'P', 'environment', 'targetUrl', 'load', 'invariantChecks'):
        if not data.get(key):
            raise ValueError(f'缺少签署字段：{key}')
    if data['scenario'] != 'agreed-peak' or data['environment'] != 'isolated':
        raise ValueError('仅执行明确标记 isolated 的 agreed-peak 隔离压测')
    datetime.fromisoformat(data['signedAt'].replace('Z', '+00:00'))
    if any(not isinstance(data[k], (int, float)) or isinstance(data[k], bool) or not math.isfinite(data[k]) or data[k] <= 0 for k in ('D', 'L', 'P')):
        raise ValueError('D/L/P 必须是正数')
    url = urllib.parse.urlsplit(data['targetUrl'])
    if url.scheme not in ('http', 'https') or not url.hostname or url.username or url.password or url.query or url.fragment:
        raise ValueError('targetUrl 必须是无凭据的 HTTP(S) 基础地址')
    load = data['load']
    for key in ('durationSeconds', 'ratePerSecond', 'concurrency', 'timeoutSeconds', 'maxClientP99Ms', 'maxErrorRate', 'requests'):
        if key not in load:
            raise ValueError(f'缺少负载字段：{key}')
    for key, maximum in (('durationSeconds', 7200), ('ratePerSecond', 10000), ('concurrency', 256), ('timeoutSeconds', 60), ('maxClientP99Ms', 60000)):
        value = load[key]
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or not 0 < value <= maximum:
            raise ValueError(f'负载字段超预算：{key}')
    if int(load['concurrency']) != load['concurrency'] or not 0 <= load['maxErrorRate'] < 1:
        raise ValueError('并发必须是整数，maxErrorRate 必须在[0,1)')
    if load['durationSeconds'] * load['ratePerSecond'] > 1_000_000:
        raise ValueError('单次最多一百万请求，请拆分压测阶段')
    if not load['requests'] or len(load['requests']) > 100 or not data['invariantChecks'] or len(data['invariantChecks']) > 100:
        raise ValueError('必须提供有界请求分布与最终不变量检查')
    for request in load['requests'] + data['invariantChecks']:
        path = request.get('path', '')
        if not path.startswith('/api/wms/') or path.startswith('//') or '\\' in path or '\n' in path or '\r' in path or urllib.parse.urlsplit(path).netloc:
            raise ValueError('请求必须是目标服务内的 WMS 相对路径')
        if request.get('method', 'GET') not in ('GET', 'POST') or not request.get('expectedStatuses'):
            raise ValueError('请求必须声明 GET/POST 与期望状态码')
    for check in data['invariantChecks']:
        if check.get('method', 'GET') != 'GET' or not check.get('equals'):
            raise ValueError('最终不变量检查必须是带 equals 断言的只读请求')
    return data


class NoRedirect(urllib.request.HTTPRedirectHandler):
    # 不向重定向目标发送签署目标的令牌，也不把登录页200算作业务成功。
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def request_once(base, spec, token, sequence, run_id, timeout):
    started = time.perf_counter()
    status = 0
    valid = False
    try:
        key = f'capacity-{run_id}-{sequence}'
        text = json.dumps(spec.get('body'), ensure_ascii=False).replace('{{commandId}}', key)
        body = text.encode() if 'body' in spec else None
        path = spec['path'].replace('{{commandId}}', key)
        headers = {'Accept': 'application/json', 'Content-Type': 'application/json', 'Idempotency-Key': key, 'X-Request-Id': key}
        if token:
            headers['Authorization'] = f'Bearer {token}'
        request = urllib.request.Request(base.rstrip('/') + path, data=body, headers=headers, method=spec.get('method', 'GET'))
        try:
            response = urllib.request.build_opener(NoRedirect()).open(request, timeout=timeout)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            status = response.code
            raw = response.read(1_048_577)
        valid = status in spec['expectedStatuses'] and len(raw) <= 1_048_576
        if valid and spec.get('equals'):
            payload = json.loads(raw)
            for dotted, expected in spec['equals'].items():
                actual = payload
                for segment in dotted.split('.'):
                    actual = actual[int(segment)] if isinstance(actual, list) else actual[segment]
                valid = valid and actual == expected
    except Exception:
        valid = False
    return {'sequence': sequence, 'status': status, 'valid': valid, 'latencyMs': (time.perf_counter() - started) * 1000}


def run(data, output, token=''):
    validate(data)
    output.mkdir(parents=True, exist_ok=False)
    load = data['load']
    total = math.ceil(load['durationSeconds'] * load['ratePerSecond'])
    pending = set()
    samples = []
    rejected = 0
    started = time.monotonic()
    run_id = str(time.time_ns())
    with concurrent.futures.ThreadPoolExecutor(max_workers=int(load['concurrency'])) as pool:
        for sequence in range(total):
            due = started + sequence / load['ratePerSecond']
            time.sleep(max(0, due - time.monotonic()))
            done = {future for future in pending if future.done()}
            samples.extend(future.result() for future in done)
            pending -= done
            if len(pending) >= load['concurrency']:
                rejected += 1
                continue
            spec = load['requests'][sequence % len(load['requests'])]
            pending.add(pool.submit(request_once, data['targetUrl'], spec, token, sequence, run_id, load['timeoutSeconds']))
        samples.extend(future.result() for future in concurrent.futures.as_completed(pending))
    elapsed = time.monotonic() - started
    checks = [request_once(data['targetUrl'], check, token, total + i, run_id, load['timeoutSeconds'])
              for i, check in enumerate(data['invariantChecks'])]
    latencies = sorted(sample['latencyMs'] for sample in samples)
    percentile = lambda p: latencies[min(len(latencies) - 1, math.ceil(len(latencies) * p) - 1)] if latencies else None
    errors = sum(not sample['valid'] for sample in samples)
    p99 = percentile(.99)
    passed = bool(samples) and rejected == 0 and errors / len(samples) <= load['maxErrorRate'] and p99 <= load['maxClientP99Ms'] and all(check['valid'] for check in checks)
    try:
        commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=Path(__file__).resolve().parents[1], text=True).strip()
    except subprocess.SubprocessError:
        commit = 'unknown'
    report = {'passed': passed, 'scope': 'HTTP客户端延迟；不代表服务端TP99或完整容量验收', 'measuredAt': datetime.now(timezone.utc).isoformat(),
              'commit': commit, 'signedInputSha256': hashlib.sha256(json.dumps(data, sort_keys=True).encode()).hexdigest(),
              'scheduled': total, 'completed': len(samples), 'loadGeneratorRejected': rejected, 'errors': errors,
              'elapsedSeconds': elapsed, 'achievedRequestsPerSecond': len(samples) / max(elapsed, load['durationSeconds']),
              'clientP95Ms': percentile(.95), 'clientP99Ms': p99, 'invariantChecks': checks}
    (output / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    with (output / 'requests.csv').open('w') as stream:
        writer = csv.DictWriter(stream, fieldnames=('sequence', 'status', 'valid', 'latencyMs'))
        writer.writeheader()
        writer.writerows(samples)
    return report


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--input', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    try:
        data = validate(json.loads(Path(args.input).read_text()))
        report = run(data, Path(args.output), os.environ.get('WMS_CAPACITY_TOKEN', ''))
        print(json.dumps(report, ensure_ascii=False))
        return 0 if report['passed'] else 1
    except (ValueError, OSError, KeyError, TypeError) as error:
        print(f'容量执行拒绝：{error}', file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
