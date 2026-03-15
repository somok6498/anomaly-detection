---
name: smoke-test
description: Run a quick end-to-end smoke test — evaluate a transaction, check it appears in the review queue, and verify the evaluation result. Use to confirm the full pipeline works.
disable-model-invocation: true
allowed-tools: Bash
---

Run a quick end-to-end test of the anomaly detection pipeline:

1. **Evaluate a high-amount transaction** (should trigger ALERT or BLOCK):
   ```
   curl -sf -X POST localhost:8080/api/v1/transactions/evaluate \
     -H "Content-Type: application/json" \
     -d "{
       \"txnId\": \"SMOKE-$(date +%s)\",
       \"clientId\": \"CLIENT-001\",
       \"txnType\": \"NEFT\",
       \"amount\": 500000,
       \"timestamp\": $(date +%s000),
       \"beneficiaryAccount\": \"9999999999\",
       \"beneficiaryIfsc\": \"HDFC0009999\"
     }" | python3 -m json.tool
   ```
   Capture the `txnId` from the response.

2. **Check evaluation result**:
   ```
   curl -sf localhost:8080/api/v1/transactions/results/SMOKE-<txnId> | python3 -m json.tool
   ```
   Verify: compositeScore > 0, action is PASS/ALERT/BLOCK, ruleResults array is populated.

3. **Check review queue** (if action was ALERT or BLOCK):
   ```
   curl -sf "localhost:8080/api/v1/review/queue?clientId=CLIENT-001&limit=1" | python3 -m json.tool
   ```

4. **Try simulation** (dry-run):
   ```
   curl -sf -X POST localhost:8080/api/v1/advanced/simulate \
     -H "Content-Type: application/json" \
     -d "{
       \"txnId\": \"SIM-SMOKE\",
       \"clientId\": \"CLIENT-001\",
       \"txnType\": \"UPI\",
       \"amount\": 999999
     }" | python3 -m json.tool
   ```
   Verify: `simulated: true` in response.

5. **Report results**: Summarize what worked and what didn't. Show the composite score, action, and which rules triggered.
