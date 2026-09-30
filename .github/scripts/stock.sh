#!/usr/bin/env bash
# Prints the stock of PRODUCT_ID.
set -euo pipefail
curl -sS --fail-with-body http://localhost:8081/products |
  jq -r --arg id "$PRODUCT_ID" '.[] | select(.id == $id) | .quantity'
