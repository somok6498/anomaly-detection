---
name: deploy
description: Build the Java project, rebuild the Docker image, restart the app container, and verify health. Use after any backend code change.
disable-model-invocation: true
allowed-tools: Bash, Read
---

Follow these steps in order:

1. **Build the Java project** (skip tests for speed):
   ```
   cd anomaly-detection && ./gradlew build -x test
   ```
   If this fails, stop and fix compilation errors before proceeding.

2. **Rebuild the Docker image**:
   ```
   cd anomaly-detection && docker compose build app
   ```

3. **Restart the app container**:
   ```
   cd anomaly-detection && docker compose up -d app
   ```

4. **Wait for startup** (Spring Boot takes ~10s):
   ```
   sleep 12
   ```

5. **Verify health**:
   - `curl -sf localhost:8080/actuator/health` — must return `{"status":"UP"}`
   - `curl -sf localhost:8080/api/v1/rules | head -c 200` — must return rule data

6. **Report result**: State whether deploy succeeded or failed, and show any errors.
