#!/usr/bin/env python3
"""读取受鉴权的队列指标并输出可供现有调度/监控采集的告警，不自行发送通知。"""
import argparse
import json
import math
import os
import time
import urllib.error
import urllib.parse
import urllib.request


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # 监控令牌只能提交给显式目标，不能跟随重定向送往其他主机。
        return None


def check(base_url, token, outbox, max_depth, max_age, max_sample_age):
    url = urllib.parse.urlsplit(base_url)
    if url.scheme not in ('http', 'https') or not url.hostname or url.username or url.password or url.query or url.fragment:
        raise ValueError('目标必须是无内嵌凭据的HTTP(S)服务地址')
    if not token or '\n' in token or '\r' in token:
        raise ValueError('必须配置WMS_MONITOR_TOKEN')
    if outbox not in ('INVENTORY_OUTBOX', 'SOURCE_OUTBOX'):
        raise ValueError('未知Outbox类别')
    if not 0 <= max_depth <= 1000 or not 0 < max_age <= 86400 or not 0 < max_sample_age <= 300:
        raise ValueError('告警阈值超出支持范围')
    opener = urllib.request.build_opener(NoRedirect())
    deadline = time.monotonic() + 30

    def metric(name, queue=None, state=None):
        suffix = '' if queue is None else '?' + urllib.parse.urlencode([('tag', 'queue:' + queue), ('tag', 'state:' + state)])
        request = urllib.request.Request(base_url.rstrip('/') + '/actuator/metrics/' + name + suffix,
                                         headers={'Authorization': 'Bearer ' + token})
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise ValueError('指标读取超过总截止时间')
        try:
            with opener.open(request, timeout=min(2, remaining)) as response:
                body = bytearray()
                while len(body) <= 65536:
                    if time.monotonic() >= deadline:
                        raise ValueError('指标读取超过总截止时间')
                    chunk = response.read1(min(4096, 65537 - len(body)))
                    if not chunk:
                        break
                    body.extend(chunk)
        except urllib.error.HTTPError as failure:
            failure.close()
            raise
        if len(body) > 65536:
            raise ValueError('指标响应超过上限')
        data = json.loads(body)
        measurements = data.get('measurements', [])
        if len(measurements) != 1 or measurements[0].get('statistic') != 'VALUE':
            raise ValueError('指标缺失或类型不匹配')
        value = measurements[0].get('value')
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value < 0:
            raise ValueError('指标未完成有效采样')
        return value

    alerts = []
    if metric('wms.messaging.sample.available') != 1:
        return {'status': 'UNKNOWN', 'alerts': [{'code': 'SAMPLE_UNAVAILABLE'}]}, 2
    age = metric('wms.messaging.sample.age')
    if age > max_sample_age:
        return {'status': 'UNKNOWN', 'alerts': [{'code': 'SAMPLE_STALE', 'seconds': age}]}, 2
    for queue in ('INBOX', outbox):
        for state in ('PENDING', 'CLAIMED', 'ISOLATED'):
            depth = metric('wms.messaging.backlog', queue, state)
            oldest = metric('wms.messaging.oldest.age', queue, state)
            if state == 'ISOLATED' and depth > 0:
                alerts.append({'code': 'MESSAGE_ISOLATED', 'queue': queue, 'countAtLeast': depth})
            elif depth > max_depth or (depth > 0 and oldest > max_age):
                alerts.append({'code': 'MESSAGE_BACKLOG', 'queue': queue, 'state': state,
                               'countAtLeast': depth, 'oldestSeconds': oldest})
    if metric('wms.messaging.sample.age') > max_sample_age:
        return {'status': 'UNKNOWN', 'alerts': [{'code': 'SAMPLE_STALE'}]}, 2
    return {'status': 'ALERT' if alerts else 'OK', 'alerts': alerts}, 1 if alerts else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', required=True)
    parser.add_argument('--outbox', required=True, choices=('INVENTORY_OUTBOX', 'SOURCE_OUTBOX'))
    parser.add_argument('--max-depth', required=True, type=int)
    parser.add_argument('--max-age-seconds', required=True, type=float)
    parser.add_argument('--max-sample-age-seconds', required=True, type=float)
    args = parser.parse_args()
    try:
        report, code = check(args.base_url, os.environ.get('WMS_MONITOR_TOKEN', ''), args.outbox,
                             args.max_depth, args.max_age_seconds, args.max_sample_age_seconds)
    except (ValueError, KeyError, TypeError, OSError, urllib.error.URLError):
        # 不回显HTTP响应、目标URL或异常原文，避免日志泄漏凭据与内部地址。
        report, code = {'status': 'UNKNOWN', 'alerts': [{'code': 'METRICS_UNAVAILABLE'}]}, 2
    print(json.dumps(report, ensure_ascii=False))
    return code


if __name__ == '__main__':
    raise SystemExit(main())
