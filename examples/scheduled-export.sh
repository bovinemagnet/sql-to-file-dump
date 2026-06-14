#!/usr/bin/env bash
#
# Scheduled SQL export — EXAMPLE wrapper script.
#
# The daemon has a built-in scheduler, but you can also run an export on a schedule
# WITHOUT keeping the daemon running by invoking the one-shot CLI from an OS scheduler
# (cron, a systemd timer, or launchd) through a wrapper like this. It writes a dated
# Parquet file on each run. Replace the URL, user, and SQL with your own.
#
# Examples:
#   crontab:   0 2 * * *  /path/to/examples/scheduled-export.sh >> /tmp/jdbc-export.log 2>&1
#   manual:    ./examples/scheduled-export.sh
#
set -euo pipefail

# --- configuration (override via the environment) -----------------------------
JAR="${JDBC_EXPORT_JAR:-build/quarkus-app/quarkus-run.jar}"
EXPORT_DIR="${EXPORT_DIR:-exports}"
DB_URL="${DB_URL:-jdbc:postgresql://localhost:5432/appdb}"
DB_USER="${DB_USER:-app_ro}"

# Password is read from an environment variable BY NAME -- never embedded here.
# Set the variable before the scheduler runs the script, and name it in PASSWORD_ENV:
#   export DB_PASSWORD='...'
PASSWORD_ENV="${PASSWORD_ENV:-}"        # e.g. DB_PASSWORD (leave empty if no password)

# --- run ----------------------------------------------------------------------
DATE="$(date +%F)"
mkdir -p "$EXPORT_DIR"

# Alias every column explicitly: the SQL result shape is the export contract.
SQL="select o.id as order_id,
            o.customer_id as customer_id,
            o.total as total,
            o.created_at as created_at
     from orders o
     order by o.id"

args=(
  -jar "$JAR"
  --url "$DB_URL"
  --user "$DB_USER"
  --sql "$SQL"
  --format parquet
  --parquet-compression zstd
  --output "$EXPORT_DIR/orders_${DATE}.parquet"
  --overwrite
)

# Append the password-env flag only when one is configured.
if [[ -n "$PASSWORD_ENV" ]]; then
  args+=(--password-env "$PASSWORD_ENV")
fi

java "${args[@]}"
