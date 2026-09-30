#!/usr/bin/env bash
# Waits until an order's history holds exactly the given statuses (a JSON array,
# sorted), then prints the history.
# Usage: await-order-history.sh ORDER_ID '["APPROVED","CREATED"]'
set -euo pipefail
url="http://localhost:8080/orders/$1/history"
for _ in $(seq 1 30); do
  if curl -sS "$url" | jq -e --argjson want "$2" '[.[].status] | sort == $want' > /dev/null; then
    curl -sS "$url" | jq -c '[.[].status]'
    exit 0
  fi
  sleep 2
done
echo "Order $1 history did not become $2:" >&2
curl -sS "$url" >&2
exit 1
