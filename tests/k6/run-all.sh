#!/bin/sh

for script in /k6/*.js; do
  echo "▶️ Running $script"
  # k6 run --http-debug=full "$script" # verbose
  k6 run --out "influxdb=http://influxdb:8086/k6" "$script"
done  
