#!/usr/bin/env python3
"""启动本次构建的独立进程，验证健康与默认拒绝访问；不代表业务验收。"""
import json
import re
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def main():
    running = []
    output = ROOT / ".local" / "smoke"
    output.mkdir(parents=True, exist_ok=True)
    try:
        for service in ("inbound", "outbound", "inventory", "fulfillment"):
            jar = ROOT / f"wms-{service}/target/wms-{service}-0.1.0-SNAPSHOT.jar"
            if not jar.is_file():
                raise RuntimeError(f"先执行构建，缺少 {jar.name}")
            log_path = output / f"{service}.log"
            log = log_path.open("w")
            proc = subprocess.Popen(["java", "-jar", str(jar), "--server.port=0"],
                                    cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
            running.append((service, proc, log, log_path))
        for service, proc, log, path in running:
            deadline = time.monotonic() + 60
            port = None
            while time.monotonic() < deadline:
                if proc.poll() is not None:
                    raise RuntimeError(f"{service} 启动失败，查看 {path}")
                match = re.search(r"Tomcat started on port (\d+)", path.read_text())
                if match:
                    port = int(match.group(1))
                    break
                time.sleep(0.2)
            if port is None:
                raise RuntimeError(f"{service} 启动超时")
            base = f"http://127.0.0.1:{port}"
            with urllib.request.urlopen(base + "/actuator/health", timeout=5) as response:
                assert json.load(response)["status"] == "UP"
            try:
                urllib.request.urlopen(base + "/internal/unimplemented", timeout=5)
                raise AssertionError("业务路径不应开放")
            except urllib.error.HTTPError as error:
                assert error.code in (401, 403), error.code
            print(f"PASS {service}: independent PID, health UP, business access denied")
    finally:
        # 只停止本脚本创建的进程，不按名字批量结束其他项目。
        for _, proc, log, _ in running:
            if proc.poll() is None:
                proc.terminate()
                try:
                    proc.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    proc.kill()
                    proc.wait(timeout=5)
            log.close()

if __name__ == "__main__":
    main()
