---
name: status
description: Show the current status of all Docker services, API health, and system overview. Use to quickly check what's running.
disable-model-invocation: true
allowed-tools: Bash
---

Gather and report system status:

1. **Docker containers**:
   ```
   cd anomaly-detection && docker compose ps --format "table {{.Name}}\t{{.Status}}\t{{.Ports}}"
   ```

2. **API health** (if app is running):
   ```
   curl -sf localhost:8080/actuator/health 2>/dev/null || echo "App not reachable"
   ```

3. **System overview** (if API is healthy):
   ```
   curl -sf localhost:8080/api/v1/advanced/system-overview 2>/dev/null | python3 -m json.tool || echo "System overview unavailable"
   ```

4. **Review queue stats** (if API is healthy):
   ```
   curl -sf localhost:8080/api/v1/review/stats 2>/dev/null | python3 -m json.tool || echo "Review stats unavailable"
   ```

Present results in a concise dashboard format.
