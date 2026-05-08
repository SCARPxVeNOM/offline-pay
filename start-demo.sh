#!/usr/bin/env bash
# OfflinePay one-click demo launcher (macOS / Linux / Git Bash on Windows).
set -e
ROOT="$(cd "$(dirname "$0")" && pwd)"

echo "OfflinePay demo launcher"
echo "------------------------"

pkill -f "hardhat node" 2>/dev/null || true
pkill -f "node src/server.js" 2>/dev/null || true
sleep 1

echo "[1/4] starting local chain..."
( cd "$ROOT/contracts" && npx hardhat node --port 8545 > /tmp/offlinepay-hardhat.log 2>&1 & )
sleep 5

echo "[2/4] deploying contracts..."
( cd "$ROOT/contracts" && npx hardhat run scripts/deploy.js --network localhost ) | tail -4

echo "[3/4] starting backend..."
rm -f "$ROOT/backend/data/offlinepay.db"
[ -f "$ROOT/backend/.env" ] || cp "$ROOT/backend/.env.example" "$ROOT/backend/.env"
( cd "$ROOT/backend" && node src/server.js > /tmp/offlinepay-backend.log 2>&1 & )
sleep 4

echo "[4/4] dashboard ready at: file://$ROOT/dashboard/index.html"
echo ""
echo "Open it in your browser, then click 'Run end-to-end demo'."
echo "CLI alternative: cd simulator && node demo.js"
echo ""
echo "Logs: /tmp/offlinepay-hardhat.log and /tmp/offlinepay-backend.log"
echo "Stop:  pkill -f 'hardhat node'; pkill -f 'src/server.js'"
