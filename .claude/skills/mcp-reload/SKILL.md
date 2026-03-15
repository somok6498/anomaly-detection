---
name: mcp-reload
description: Rebuild the MCP server TypeScript code. Use after modifying mcp-server/src/ files.
disable-model-invocation: true
allowed-tools: Bash
---

1. **Compile TypeScript**:
   ```
   cd anomaly-detection/mcp-server && npm run build
   ```
   If this fails, stop and report the TypeScript compilation errors.

2. **Verify compiled output**:
   ```
   ls -la anomaly-detection/mcp-server/dist/index.js anomaly-detection/mcp-server/dist/api-client.js
   ```

3. **Count tools** (quick sanity check):
   ```
   grep -c 'server.tool(' anomaly-detection/mcp-server/src/index.ts
   ```
   Report the tool count.

4. **Remind user**: Reload the VS Code window (`Cmd+Shift+P` > "Developer: Reload Window") to pick up MCP server changes, then run `/mcp` to verify tools are connected.
