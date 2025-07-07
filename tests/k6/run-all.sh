#!/bin/sh

set -e

for script in /k6/*.js; do
  echo "▶️ Running $script in background"
  k6 run --out "influxdb=http://influxdb:8086/k6" "$script" &
done

# Wait for all background jobs to finish
wait
echo "✅ All scripts finished."
