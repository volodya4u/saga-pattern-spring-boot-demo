#!/usr/bin/env bash
# Places an order for PRODUCT_ID and prints its ID.
# Usage: place-order.sh QUANTITY
set -euo pipefail
curl -sS --fail-with-body -H 'Content-Type: application/json' \
  -d "{\"customerId\":\"$CUSTOMER_ID\",\"productId\":\"$PRODUCT_ID\",\"productQuantity\":$1}" \
  http://localhost:8080/orders | jq -r .orderId
