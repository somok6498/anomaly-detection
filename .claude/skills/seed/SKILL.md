---
name: seed
description: Re-seed the Aerospike database with demo data by restarting the app with the seed Spring profile. Use when you need fresh test data.
disable-model-invocation: true
allowed-tools: Bash
---

1. **Restart the app container with the seed profile**:
   ```
   cd anomaly-detection && SPRING_PROFILES_ACTIVE=seed docker compose up -d app
   ```

2. **Wait for seeding to complete** (~15-20 seconds):
   ```
   sleep 20
   ```

3. **Verify seeded data exists**:
   ```
   curl -sf localhost:8080/api/v1/rules | python3 -m json.tool | head -20
   ```
   Should return a list of 16 anomaly rules.

4. **Restart without seed profile** (so normal operation resumes):
   ```
   cd anomaly-detection && docker compose up -d app
   ```

5. **Report**: Confirm whether seeding succeeded and data is accessible.
