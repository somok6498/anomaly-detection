---
name: verify
description: Run the full post-change verification checklist — health check, API data, Prometheus targets, and dashboard. Use after any deploy or config change.
disable-model-invocation: true
allowed-tools: Bash
---

Run all verification checks and report results as a checklist:

1. **Health check**:
   ```
   curl -sf localhost:8080/actuator/health
   ```
   Expected: `{"status":"UP"}`

2. **API returns data** (rules endpoint):
   ```
   curl -sf localhost:8080/api/v1/rules | python3 -c "import sys,json; data=json.load(sys.stdin); print(f'{len(data)} rules loaded')"
   ```

3. **Prometheus scraping**:
   ```
   curl -sf localhost:9090/api/v1/targets | python3 -c "import sys,json; targets=json.load(sys.stdin)['data']['activeTargets']; [print(f\"  {t['labels'].get('job','?')}: {t['health']}\") for t in targets]"
   ```
   Expected: all targets show `up`.

4. **Dashboard accessible**:
   ```
   curl -sf -o /dev/null -w '%{http_code}' localhost:8080/index.html
   ```
   Expected: `200`

5. **Grafana accessible**:
   ```
   curl -sf -o /dev/null -w '%{http_code}' localhost:3333
   ```
   Expected: `200`

6. **Jaeger accessible**:
   ```
   curl -sf -o /dev/null -w '%{http_code}' localhost:16686
   ```
   Expected: `200`

Report each check as PASS or FAIL with details.
