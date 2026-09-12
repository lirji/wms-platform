#!/bin/bash
# 仅初始化本项目独立登记库，不读取或授权inventory分库。
set -euo pipefail
: "${MYSQL_ROOT_PASSWORD:?MYSQL_ROOT_PASSWORD is required}"
: "${WMS_SERIAL_DB_PASSWORD:?WMS_SERIAL_DB_PASSWORD is required}"
# 固定当前会话不解释反斜杠，单引号翻倍后作为SQL字符串值，不拼接原始口令。
serial_password_sql="${WMS_SERIAL_DB_PASSWORD//\'/\'\'}"
MYSQL_PWD="${MYSQL_ROOT_PASSWORD}" mysql --protocol=socket -uroot <<EOSQL
SET SESSION sql_mode='NO_BACKSLASH_ESCAPES';
CREATE DATABASE IF NOT EXISTS wms_registry DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'wms_registry'@'%' IDENTIFIED BY '${serial_password_sql}';
GRANT ALL PRIVILEGES ON wms_registry.* TO 'wms_registry'@'%';
EOSQL
