---
name: rebuild-dashboard
description: Rebuild the Flutter web dashboard and copy static assets to Spring Boot resources. Use after any dashboard UI changes.
disable-model-invocation: true
allowed-tools: Bash
---

1. **Build the Flutter web dashboard**:
   ```
   cd anomaly-detection/dashboard_ui && flutter build web
   ```
   If this fails, stop and report the Flutter build errors.

2. **Copy built assets to Spring Boot static resources**:
   ```
   cp -r anomaly-detection/dashboard_ui/build/web/* anomaly-detection/src/main/resources/static/
   ```

3. **Verify files were copied**:
   ```
   ls -la anomaly-detection/src/main/resources/static/index.html
   ```

4. **Remind user**: The Docker image needs to be rebuilt for changes to appear in the containerized app. Suggest running `/deploy` next.
