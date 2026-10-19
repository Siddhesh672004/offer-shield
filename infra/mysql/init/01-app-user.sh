#!/bin/bash
# Runs once, when the MySQL data volume is empty (docker-entrypoint-initdb.d).
# Creates the app schema and a least-privilege app user. Root is used only here.
set -eo pipefail

if [ -z "${DB_PASSWORD}" ]; then
    echo "01-app-user.sh: DB_PASSWORD is not set" >&2
    exit 1
fi

backslash='\'
double_quote='"'
single_quote="'"

# SQL string literal: backslash is an escape character in MySQL, so double it first, then double single quotes.
app_password_sql=${DB_PASSWORD//"$backslash"/"$backslash$backslash"}
app_password_sql=${app_password_sql//"$single_quote"/"$single_quote$single_quote"}

# Option-file value inside double quotes: escape backslashes, then double quotes.
root_password_opt=${MYSQL_ROOT_PASSWORD//"$backslash"/"$backslash$backslash"}
root_password_opt=${root_password_opt//"$double_quote"/"$backslash$double_quote"}

# The root password reaches the client through a private (0600) temp file, never an environment variable or argv.
# The work runs in a subshell whose EXIT trap deletes that file however the subshell ends (success or any failure).
# A subshell, because the image's entrypoint *sources* init scripts that aren't executable: a trap set here directly
# would replace the entrypoint's own EXIT trap. errexit is switched off only around the subshell, to read its status;
# inside it, set -e still stops at the first failing command (it would be ignored inside "( ... ) || ...").
set +e
(
    set -e
    defaults_file=""
    trap 'rm -f "${defaults_file}"' EXIT
    defaults_file=$(mktemp /tmp/offershield-init.XXXXXX)
    chmod 600 "${defaults_file}"
    printf '[client]\nuser=root\npassword="%s"\n' "${root_password_opt}" > "${defaults_file}"

    mysql --defaults-extra-file="${defaults_file}" --protocol=socket <<SQL
CREATE DATABASE IF NOT EXISTS offershield_db CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'offershield_app'@'%' IDENTIFIED BY '${app_password_sql}';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES
    ON offershield_db.* TO 'offershield_app'@'%';
SQL
)
status=$?
set -e
if [ "${status}" -ne 0 ]; then
    echo "01-app-user.sh: creating the app user failed" >&2
    exit "${status}"
fi
