#!/bin/bash
# Runs once, when the MySQL data volume is empty (docker-entrypoint-initdb.d).
# Creates the app schema and a least-privilege app user. Root is used only here.
set -eo pipefail

if [ -z "${DB_PASSWORD}" ]; then
    echo "01-app-user.sh: DB_PASSWORD is not set" >&2
    exit 1
fi
app_password_sql=${DB_PASSWORD//\'/\'\'}

MYSQL_PWD="${MYSQL_ROOT_PASSWORD}" mysql --protocol=socket -uroot <<SQL
CREATE DATABASE IF NOT EXISTS offershield_db CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'offershield_app'@'%' IDENTIFIED BY '${app_password_sql}';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES
    ON offershield_db.* TO 'offershield_app'@'%';
SQL
