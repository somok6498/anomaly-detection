#!/usr/bin/env bash
# Generate realistic evaluation data across multiple clients, amounts, and scenarios
set -e
BASE="http://localhost:8080/api/v1"
NOW_MS=$(python3 -c "import time; print(int(time.time()*1000))")

eval_txn() {
  local txn_id="$1" client="$2" type="$3" amount="$4" bene_acct="$5" bene_ifsc="$6" ts="$7"
  local body="{\"txnId\":\"$txn_id\",\"clientId\":\"$client\",\"txnType\":\"$type\",\"amount\":$amount,\"timestamp\":$ts,\"beneficiaryAccount\":\"$bene_acct\",\"beneficiaryIfsc\":\"$bene_ifsc\"}"
  local result
  result=$(curl -s -w "\n%{http_code}" -X POST "$BASE/transactions/evaluate" \
    -H "Content-Type: application/json" -d "$body")
  local code=$(echo "$result" | tail -1)
  local resp=$(echo "$result" | head -n -1)
  local score=$(echo "$resp" | python3 -c "import sys,json; d=json.load(sys.stdin); print(f'{d[\"compositeScore\"]:.1f} → {d[\"action\"]}')" 2>/dev/null || echo "error")
  printf "  %-30s | %-10s | %-6s | %10.2f | Score: %s\n" "$txn_id" "$client" "$type" "$amount" "$score"
}

echo "═══════════════════════════════════════════════════════════════"
echo "  SCENARIO 1: Normal transactions (should PASS)"
echo "═══════════════════════════════════════════════════════════════"
# Small routine transactions for clients with established profiles
for i in $(seq 1 8); do
  client="CLIENT-00$((i % 5 + 1))"
  types=("NEFT" "IMPS" "UPI" "RTGS")
  type=${types[$((i % 4))]}
  amount=$(python3 -c "import random; print(round(random.uniform(500, 5000), 2))")
  ts=$((NOW_MS - i * 3600000))  # spread over last 8 hours
  eval_txn "NORMAL-${type}-$(printf '%03d' $i)" "$client" "$type" "$amount" \
    "10$(printf '%08d' $((RANDOM % 99999999)))" "HDFC000$(printf '%04d' $((RANDOM % 9999)))" "$ts"
done

echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "  SCENARIO 2: Large amount anomalies (should trigger AMOUNT rules)"
echo "═══════════════════════════════════════════════════════════════"
# Abnormally large transactions
eval_txn "BIGAMT-NEFT-001" "CLIENT-001" "NEFT" 250000.00 "9999888877" "ICIC0001234" "$((NOW_MS - 100000))"
eval_txn "BIGAMT-RTGS-002" "CLIENT-002" "RTGS" 500000.00 "8888777766" "SBIN0005678" "$((NOW_MS - 200000))"
eval_txn "BIGAMT-IMPS-003" "CLIENT-003" "IMPS" 175000.50 "7777666655" "UTIB0009012" "$((NOW_MS - 300000))"
eval_txn "BIGAMT-UPI-004"  "CLIENT-004" "UPI"  95000.00  "6666555544" "KKBK0003456" "$((NOW_MS - 400000))"
eval_txn "BIGAMT-NEFT-005" "CLIENT-005" "NEFT" 450000.00 "5555444433" "CNRB0007890" "$((NOW_MS - 500000))"

echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "  SCENARIO 3: Unusual transaction types (should trigger TYPE rules)"
echo "═══════════════════════════════════════════════════════════════"
# Clients using unusual channels
eval_txn "ODDTYPE-RTGS-001" "CLIENT-001" "RTGS" 15000.00  "1234567890" "BARB0001111" "$((NOW_MS - 600000))"
eval_txn "ODDTYPE-RTGS-002" "CLIENT-001" "RTGS" 22000.00  "1234567891" "BARB0001112" "$((NOW_MS - 700000))"
eval_txn "ODDTYPE-SWIFT-003" "CLIENT-003" "SWIFT" 80000.00 "9012345678" "BARB0002222" "$((NOW_MS - 800000))"

echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "  SCENARIO 4: Rapid-fire to same beneficiary (BENE_RAPID_REPEAT)"
echo "═══════════════════════════════════════════════════════════════"
# Same beneficiary hit multiple times quickly
SAME_BENE="4444333322"
SAME_IFSC="HDFC0009999"
for i in $(seq 1 5); do
  ts=$((NOW_MS - (5 - i) * 60000))  # 1 minute apart
  eval_txn "RAPID-BENE-00$i" "CLIENT-002" "IMPS" $((3000 + i * 500)).00 "$SAME_BENE" "$SAME_IFSC" "$ts"
done

echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "  SCENARIO 5: New beneficiary velocity (many new benes quickly)"
echo "═══════════════════════════════════════════════════════════════"
for i in $(seq 1 6); do
  ts=$((NOW_MS - (6 - i) * 120000))  # 2 minutes apart
  eval_txn "NEWBENE-00$i" "CLIENT-004" "NEFT" $((2000 + RANDOM % 3000)).00 \
    "NEW$(printf '%07d' $((RANDOM)))" "AXIS000$(printf '%04d' $((RANDOM % 9999)))" "$ts"
done

echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "  SCENARIO 6: High cumulative daily amount"
echo "═══════════════════════════════════════════════════════════════"
# Many medium transactions adding up to a large daily total
for i in $(seq 1 6); do
  ts=$((NOW_MS - (6 - i) * 180000))  # 3 minutes apart
  eval_txn "CUMUL-00$i" "CLIENT-005" "UPI" $((8000 + RANDOM % 5000)).00 \
    "CUM$(printf '%07d' $((i * 1111)))" "SBIN000$(printf '%04d' $((i * 111)))" "$ts"
done

echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "  SCENARIO 7: Dormant client reactivation"
echo "═══════════════════════════════════════════════════════════════"
# CLIENT-008 through CLIENT-010 may have dormancy patterns
eval_txn "DORMANT-REACT-001" "CLIENT-008" "NEFT" 35000.00 "8080808080" "PUNB0001234" "$NOW_MS"
eval_txn "DORMANT-REACT-002" "CLIENT-009" "IMPS" 42000.00 "9090909090" "CBIN0005678" "$NOW_MS"
eval_txn "DORMANT-REACT-003" "CLIENT-010" "UPI"  28000.00 "1010101010" "BKID0009012" "$NOW_MS"

echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "  SCENARIO 8: Mixed — moderate risk (should ALERT)"
echo "═══════════════════════════════════════════════════════════════"
eval_txn "MODERATE-001" "CLIENT-001" "NEFT" 25000.00 "3333222211" "HDFC0004567" "$((NOW_MS - 1000000))"
eval_txn "MODERATE-002" "CLIENT-003" "IMPS" 30000.00 "2222111100" "ICIC0007890" "$((NOW_MS - 1100000))"
eval_txn "MODERATE-003" "CLIENT-006" "RTGS" 55000.00 "1111000099" "SBIN0001234" "$((NOW_MS - 1200000))"
eval_txn "MODERATE-004" "CLIENT-007" "NEFT" 45000.00 "0000999988" "UTIB0005678" "$((NOW_MS - 1300000))"

echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "  SCENARIO 9: Same-amount repetition pattern"
echo "═══════════════════════════════════════════════════════════════"
# Exact same amount to same beneficiary — structuring/smurfing indicator
for i in $(seq 1 4); do
  ts=$((NOW_MS - (4 - i) * 300000))  # 5 minutes apart
  eval_txn "SAMEAMT-00$i" "CLIENT-003" "NEFT" 49999.00 "7777777777" "BARB0003333" "$ts"
done

echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "  SCENARIO 10: Cross-channel same beneficiary"
echo "═══════════════════════════════════════════════════════════════"
CROSS_BENE="5555666677"
CROSS_IFSC="HDFC0008888"
eval_txn "XCHAN-NEFT-001" "CLIENT-001" "NEFT" 20000.00 "$CROSS_BENE" "$CROSS_IFSC" "$((NOW_MS - 50000))"
eval_txn "XCHAN-IMPS-002" "CLIENT-001" "IMPS" 20000.00 "$CROSS_BENE" "$CROSS_IFSC" "$((NOW_MS - 40000))"
eval_txn "XCHAN-UPI-003"  "CLIENT-001" "UPI"  20000.00 "$CROSS_BENE" "$CROSS_IFSC" "$((NOW_MS - 30000))"
eval_txn "XCHAN-RTGS-004" "CLIENT-001" "RTGS" 20000.00 "$CROSS_BENE" "$CROSS_IFSC" "$((NOW_MS - 20000))"

echo ""
echo "Total transactions evaluated."
echo ""

# ─── Now submit feedback on items in the review queue ───
echo "═══════════════════════════════════════════════════════════════"
echo "  Submitting feedback on review queue items..."
echo "═══════════════════════════════════════════════════════════════"

# Get current queue items
QUEUE=$(curl -s "$BASE/review-queue?limit=50")
TXNS=$(echo "$QUEUE" | python3 -c "
import sys, json, random
d = json.load(sys.stdin)
items = d.get('data', d) if isinstance(d, dict) else d
pending = [i['txnId'] for i in items if i.get('feedbackStatus','PENDING') == 'PENDING']
# Pick up to 20 items to give feedback on
random.shuffle(pending)
for txn in pending[:20]:
    # 60% true positive, 40% false positive for realistic precision
    status = 'TRUE_POSITIVE' if random.random() < 0.6 else 'FALSE_POSITIVE'
    print(f'{txn}|{status}')
" 2>/dev/null)

count=0
while IFS='|' read -r txn_id status; do
  [ -z "$txn_id" ] && continue
  code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/review-queue/$txn_id/feedback" \
    -H "Content-Type: application/json" -d "{\"status\":\"$status\"}")
  printf "  %-35s → %-16s (HTTP %s)\n" "$txn_id" "$status" "$code"
  count=$((count + 1))
done <<< "$TXNS"

echo ""
echo "Feedback submitted on $count items."
echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "  Done! Check:"
echo "    Dashboard:  http://localhost:8080"
echo "    Grafana:    http://localhost:3333"
echo "═══════════════════════════════════════════════════════════════"
