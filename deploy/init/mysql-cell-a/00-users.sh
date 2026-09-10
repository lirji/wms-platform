#!/bin/bash
# Cell A 库存物理库；账号不能访问 Cell B。
set -euo pipefail
: "${MYSQL_ROOT_PASSWORD:?MYSQL_ROOT_PASSWORD is required}"
: "${WMS_INVENTORY_A_DB_PASSWORD:?WMS_INVENTORY_A_DB_PASSWORD is required}"

mysql --protocol=socket -uroot -p"${MYSQL_ROOT_PASSWORD}" <<EOSQL
CREATE DATABASE IF NOT EXISTS wms_inventory DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'wms_inventory_a'@'%' IDENTIFIED BY '${WMS_INVENTORY_A_DB_PASSWORD}';
GRANT ALL PRIVILEGES ON wms_inventory.* TO 'wms_inventory_a'@'%';
FLUSH PRIVILEGES;
EOSQL
