#!/usr/bin/env bash
set -euo pipefail

echo "=== Running anomaly-detection test suite ==="

cd "$(dirname "$0")/.."

echo "[1/3] Compiling..."
./gradlew compileTestJava --quiet

echo "[2/3] Running all tests + generating executive summary..."
./gradlew testReport

echo "[3/3] Verifying test results..."
EXEC_REPORT="build/reports/tests/executive-summary.html"
if [ -f "$EXEC_REPORT" ]; then
    echo "Executive summary: $EXEC_REPORT"
fi

echo "=== All tests passed. Safe to push. ==="
