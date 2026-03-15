---
name: test-all
description: Run the full test suite with Gradle testReport and show the executive summary. Use to verify all tests pass after code changes.
disable-model-invocation: true
allowed-tools: Bash, Read
---

1. **Run the test suite**:
   ```
   cd anomaly-detection && ./gradlew testReport
   ```

2. **Check the result**:
   - If all tests pass, read the executive summary:
     ```
     cat anomaly-detection/build/reports/tests/executive-summary.html
     ```
   - Report: total tests, passed, failed, skipped, and duration.

3. **If any tests fail**:
   - Read the detailed failure output from the Gradle console.
   - Identify the failing test class and method.
   - Show the failure reason and suggest a fix.
