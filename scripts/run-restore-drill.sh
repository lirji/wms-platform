#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
if [[ -z "${WMS_RESTORE_LIVE_JDBC_URL:-}" || -z "${WMS_RESTORE_BACKUP_JDBC_URL:-}" ]]; then
  echo "S9-04 生产隔离恢复未授权。本地演练请跑 IsolatedRestoreIT。需要 WMS_RESTORE_LIVE_JDBC_URL 与 WMS_RESTORE_BACKUP_JDBC_URL 才执行外部库恢复。" >&2
  cd "$root"
  exec ./mvnw -B -ntp -pl wms-inventory -am -Dsurefire.failIfNoSpecifiedTests=false \
    -Dfailsafe.failIfNoSpecifiedTests=false -Dtest=IsolatedRestoreIT \
    -Dit.test=IsolatedRestoreIT verify
fi
echo "外部隔离恢复入口尚未实现完整 mysqldump 编排；拒绝在未授权库上猜测命令。" >&2
exit 2
