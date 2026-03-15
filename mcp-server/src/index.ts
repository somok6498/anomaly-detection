#!/usr/bin/env node

import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";
import { apiGet, apiPost, apiPut, apiDelete, formatResult } from "./api-client.js";

const server = new McpServer({
  name: "anomaly-detection",
  version: "1.0.0",
  description:
    "Banking transaction anomaly detection system — evaluate transactions, review alerts, analyze client risk profiles, and manage detection rules.",
});

// ═══════════════════════════════════════════════════════════
// TRANSACTIONS
// ═══════════════════════════════════════════════════════════

server.tool(
  "evaluate_transaction",
  "Evaluate a banking transaction for anomalies. Returns composite risk score (0-100), action (PASS/ALERT/BLOCK), per-rule breakdown, AI explanation, and attack pattern classification. Use this to check if a transaction is suspicious.",
  {
    txnId: z.string().describe("Unique transaction ID, e.g. TXN-001"),
    clientId: z
      .string()
      .describe("Client ID (uppercase), e.g. CLIENT-001"),
    txnType: z
      .enum(["NEFT", "RTGS", "IMPS", "UPI", "IFT"])
      .describe("Transaction type"),
    amount: z.number().positive().describe("Transaction amount in INR"),
    timestamp: z
      .number()
      .optional()
      .describe("Epoch milliseconds. Defaults to current time if omitted"),
    beneficiaryAccount: z
      .string()
      .optional()
      .describe("Beneficiary bank account number"),
    beneficiaryIfsc: z
      .string()
      .optional()
      .describe("Beneficiary IFSC code, e.g. HDFC0001234"),
  },
  async (params) => {
    const res = await apiPost("/api/v1/transactions/evaluate", params);
    return { content: [{ type: "text", text: formatResult(res) }] };
  }
);

server.tool(
  "get_transaction",
  "Look up a specific transaction by its ID. Returns full transaction details including amount, type, client, beneficiary info.",
  {
    txnId: z.string().describe("Transaction ID to look up"),
  },
  async ({ txnId }) => {
    const res = await apiGet(`/api/v1/transactions/${encodeURIComponent(txnId)}`);
    return { content: [{ type: "text", text: formatResult(res) }] };
  }
);

server.tool(
  "get_client_transactions",
  "List recent transactions for a specific client. Supports cursor-based pagination. Use this to understand a client's transaction history.",
  {
    clientId: z.string().describe("Client ID, e.g. CLIENT-001"),
    limit: z
      .number()
      .int()
      .positive()
      .max(200)
      .optional()
      .describe("Max results to return (default 50)"),
    before: z
      .number()
      .optional()
      .describe("Pagination cursor — epoch millis timestamp"),
  },
  async ({ clientId, limit, before }) => {
    const res = await apiGet(
      `/api/v1/transactions/client/${encodeURIComponent(clientId)}`,
      { limit, before }
    );
    return { content: [{ type: "text", text: formatResult(res) }] };
  }
);

server.tool(
  "get_evaluation_result",
  "Get the anomaly evaluation result for a specific transaction. Shows composite score, action, per-rule scores, AI explanation, and attack pattern.",
  {
    txnId: z.string().describe("Transaction ID"),
  },
  async ({ txnId }) => {
    const res = await apiGet(
      `/api/v1/transactions/results/${encodeURIComponent(txnId)}`
    );
    return { content: [{ type: "text", text: formatResult(res) }] };
  }
);

server.tool(
  "get_client_evaluation_results",
  "List anomaly evaluation results for a client's transactions. Shows risk scores and actions for each evaluated transaction.",
  {
    clientId: z.string().describe("Client ID"),
    limit: z.number().int().positive().max(200).optional(),
    before: z.number().optional().describe("Pagination cursor — epoch millis"),
  },
  async ({ clientId, limit, before }) => {
    const res = await apiGet(
      `/api/v1/transactions/results/client/${encodeURIComponent(clientId)}`,
      { limit, before }
    );
    return { content: [{ type: "text", text: formatResult(res) }] };
  }
);

// ═══════════════════════════════════════════════════════════
// PROFILES
// ═══════════════════════════════════════════════════════════

server.tool(
  "get_client_profile",
  "Get a client's behavioral profile built from historical transactions. Includes EWMA statistics (average amount, hourly TPS), transaction type distribution, per-type averages, and variance. Use this to understand what is 'normal' for a client.",
  {
    clientId: z.string().describe("Client ID, e.g. CLIENT-001"),
  },
  async ({ clientId }) => {
    const res = await apiGet(
      `/api/v1/profiles/${encodeURIComponent(clientId)}`
    );
    return { content: [{ type: "text", text: formatResult(res) }] };
  }
);

// ═══════════════════════════════════════════════════════════
// RULES
// ═══════════════════════════════════════════════════════════

server.tool(
  "list_rules",
  "List all anomaly detection rules with their configuration. Shows rule type, risk weight, variance threshold, enabled status, and parameters. There are 16 rule types including Isolation Forest ML model.",
  {},
  async () => {
    const res = await apiGet("/api/v1/rules");
    return { content: [{ type: "text", text: formatResult(res) }] };
  }
);

server.tool(
  "get_rule",
  "Get details of a specific anomaly detection rule by ID.",
  {
    ruleId: z.string().describe("Rule ID, e.g. RULE-IF for Isolation Forest"),
  },
  async ({ ruleId }) => {
    const res = await apiGet(`/api/v1/rules/${encodeURIComponent(ruleId)}`);
    return { content: [{ type: "text", text: formatResult(res) }] };
  }
);

// ═══════════════════════════════════════════════════════════
// MODELS (Isolation Forest)
// ═══════════════════════════════════════════════════════════

server.tool(
  "get_model_metadata",
  "Get Isolation Forest ML model metadata for a client. Shows tree count, feature count, training samples, and when the model was last trained.",
  {
    clientId: z.string().describe("Client ID"),
  },
  async ({ clientId }) => {
    const res = await apiGet(
      `/api/v1/models/${encodeURIComponent(clientId)}`
    );
    return { content: [{ type: "text", text: formatResult(res) }] };
  }
);

server.tool(
  "train_model",
  "Train the Isolation Forest anomaly detection model for a specific client. Requires sufficient transaction history. Returns model metadata on success.",
  {
    clientId: z.string().describe("Client ID to train model for"),
    numTrees: z
      .number()
      .int()
      .optional()
      .describe("Number of trees (default 100)"),
    sampleSize: z
      .number()
      .int()
      .optional()
      .describe("Sample size per tree (default 256)"),
  },
  async ({ clientId, numTrees, sampleSize }) => {
    const res = await apiPost(
      `/api/v1/models/train/${encodeURIComponent(clientId)}`,
      undefined,
      { numTrees, sampleSize }
    );
    return { content: [{ type: "text", text: formatResult(res) }] };
  }
);

// ═══════════════════════════════════════════════════════════
// REVIEW QUEUE
// ═══════════════════════════════════════════════════════════

server.tool(
  "get_review_queue",
  "List items in the ops review queue. These are transactions flagged as ALERT or BLOCK that need human review. Supports filtering by action, client, date range, rule, and feedback status.",
  {
    action: z
      .enum(["ALERT", "BLOCK"])
      .optional()
      .describe("Filter by action type"),
    clientId: z.string().optional().describe("Filter by client ID"),
    fromDate: z
      .number()
      .optional()
      .describe("Start of time range (epoch millis)"),
    toDate: z
      .number()
      .optional()
      .describe("End of time range (epoch millis)"),
    ruleId: z.string().optional().describe("Filter by triggering rule ID"),
    feedbackStatus: z
      .enum(["PENDING", "TRUE_POSITIVE", "FALSE_POSITIVE", "AUTO_ACCEPTED"])
      .optional()
      .describe("Filter by feedback status"),
    limit: z.number().int().positive().max(200).optional(),
    before: z.number().optional().describe("Pagination cursor"),
  },
  async (params) => {
    const res = await apiGet("/api/v1/review/queue", params as Record<string, string | number | undefined>);
    return { content: [{ type: "text", text: formatResult(res) }] };
  }
);

server.tool(
  "get_review_queue_detail",
  "Get full detail for a specific review queue item. Returns the queue item, evaluation result with per-rule breakdown, original transaction, and client profile — everything needed to make a review decision.",
  {
    txnId: z.string().describe("Transaction ID of the queue item"),
  },
  async ({ txnId }) => {
    const res = await apiGet(
      `/api/v1/review/queue/${encodeURIComponent(txnId)}`
    );
    return { content: [{ type: "text", text: formatResult(res) }] };
  }
);

server.tool(
  "submit_feedback",
  "Submit review feedback on a flagged transaction. Mark as TRUE_POSITIVE (confirmed fraud/anomaly) or FALSE_POSITIVE (legitimate transaction). This feeds the auto-tuning system that adjusts rule weights.",
  {
    txnId: z.string().describe("Transaction ID to provide feedback on"),
    status: z
      .enum(["TRUE_POSITIVE", "FALSE_POSITIVE"])
      .describe("Feedback verdict"),
    feedbackBy: z
      .string()
      .optional()
      .describe("Identifier of the reviewer, e.g. analyst-01"),
  },
  async ({ txnId, status, feedbackBy }) => {
    const res = await apiPost(
      `/api/v1/review/queue/${encodeURIComponent(txnId)}/feedback`,
      { status, feedbackBy }
    );
    return { content: [{ type: "text", text: formatResult(res) }] };
  }
);

server.tool(
  "bulk_submit_feedback",
  "Submit feedback on multiple review queue items at once. Useful for batch-reviewing similar transactions.",
  {
    txnIds: z
      .array(z.string())
      .min(1)
      .describe("Array of transaction IDs"),
    status: z
      .enum(["TRUE_POSITIVE", "FALSE_POSITIVE"])
      .describe("Feedback verdict to apply to all"),
    feedbackBy: z.string().optional().describe("Reviewer identifier"),
  },
  async ({ txnIds, status, feedbackBy }) => {
    const res = await apiPost("/api/v1/review/queue/bulk-feedback", {
      txnIds,
      status,
      feedbackBy,
    });
    return { content: [{ type: "text", text: formatResult(res) }] };
  }
);

server.tool(
  "get_review_stats",
  "Get review queue statistics — counts of pending, true positive, false positive, and auto-accepted items. Optionally filter by date range.",
  {
    fromDate: z.number().optional().describe("Start epoch millis"),
    toDate: z.number().optional().describe("End epoch millis"),
  },
  async ({ fromDate, toDate }) => {
    const res = await apiGet("/api/v1/review/stats", { fromDate, toDate });
    return { content: [{ type: "text", text: formatResult(res) }] };
  }
);

server.tool(
  "smart_triage",
  "LLM-powered smart alert triage. Analyzes up to 15 pending review items and ranks them by urgency (CRITICAL, HIGH, MEDIUM, LOW) with reasoning. Use this to prioritize which alerts to review first.",
  {},
  async () => {
    const res = await apiGet("/api/v1/review/queue/triage");
    return { content: [{ type: "text", text: formatResult(res) }] };
  }
);

server.tool(
  "get_weight_history",
  "Get the history of rule weight changes from the auto-tuning feedback loop. Shows how rule weights have been adjusted based on TP/FP feedback.",
  {
    ruleId: z
      .string()
      .optional()
      .describe("Filter by rule ID"),
    limit: z.number().int().positive().optional(),
    before: z.number().optional().describe("Pagination cursor"),
  },
  async ({ ruleId, limit, before }) => {
    const res = await apiGet("/api/v1/review/weight-history", {
      ruleId,
      limit,
      before,
    });
    return { content: [{ type: "text", text: formatResult(res) }] };
  }
);

// ═══════════════════════════════════════════════════════════
// ANALYTICS
// ═══════════════════════════════════════════════════════════

server.tool(
  "get_rule_performance",
  "Get per-rule performance analytics — true positive count, false positive count, total triggers, and precision. Use this to identify which rules are accurate and which generate noise.",
  {
    fromDate: z.number().optional().describe("Start epoch millis"),
    toDate: z.number().optional().describe("End epoch millis"),
  },
  async ({ fromDate, toDate }) => {
    const res = await apiGet("/api/v1/analytics/rules/performance", {
      fromDate,
      toDate,
    });
    return { content: [{ type: "text", text: formatResult(res) }] };
  }
);

server.tool(
  "get_ai_feedback_stats",
  "Get statistics on AI explanation quality — how many explanations were rated helpful vs not helpful by operators.",
  {},
  async () => {
    const res = await apiGet("/api/v1/analytics/ai-feedback/stats");
    return { content: [{ type: "text", text: formatResult(res) }] };
  }
);

server.tool(
  "get_client_network",
  "Get the beneficiary network graph for a client. Returns nodes (client + beneficiaries) and edges (transactions) for visualization. Useful for detecting mule account patterns.",
  {
    clientId: z.string().describe("Client ID, e.g. CLIENT-007"),
  },
  async ({ clientId }) => {
    const res = await apiGet(
      `/api/v1/analytics/graph/client/${encodeURIComponent(clientId)}/network`
    );
    return { content: [{ type: "text", text: formatResult(res) }] };
  }
);

server.tool(
  "get_client_narrative",
  "Generate an AI-powered plain-English risk narrative for a client. The LLM analyzes the client's transaction patterns, rule triggers, and EWMA profile to produce a behavioral summary. Use this for investigation reports.",
  {
    clientId: z.string().describe("Client ID, e.g. CLIENT-001"),
  },
  async ({ clientId }) => {
    const res = await apiGet(
      `/api/v1/analytics/client/${encodeURIComponent(clientId)}/narrative`
    );
    return { content: [{ type: "text", text: formatResult(res) }] };
  }
);

// ═══════════════════════════════════════════════════════════
// SILENCE DETECTION
// ═══════════════════════════════════════════════════════════

server.tool(
  "get_silent_clients",
  "Get a list of currently silent clients — clients who normally transact but have stopped. Returns EWMA TPS, expected gap, silence duration, and last transaction time. This can indicate account takeover or frozen accounts.",
  {},
  async () => {
    const res = await apiGet("/api/v1/silence");
    return { content: [{ type: "text", text: formatResult(res) }] };
  }
);

server.tool(
  "trigger_silence_check",
  "Trigger an immediate silence detection scan across all clients. Returns how many clients were scanned and how many are silent.",
  {},
  async () => {
    const res = await apiPost("/api/v1/silence/check");
    return { content: [{ type: "text", text: formatResult(res) }] };
  }
);

// ═══════════════════════════════════════════════════════════
// BENEFICIARY GRAPH
// ═══════════════════════════════════════════════════════════

server.tool(
  "get_graph_status",
  "Get beneficiary graph metadata — total beneficiaries, total clients, last refresh time, and readiness status.",
  {},
  async () => {
    const res = await apiGet("/api/v1/graph/status");
    return { content: [{ type: "text", text: formatResult(res) }] };
  }
);

server.tool(
  "get_beneficiary_fan_in",
  "Get fan-in data for a specific beneficiary account — how many different clients send money to this account. High fan-in can indicate a mule account.",
  {
    ifsc: z.string().describe("Beneficiary IFSC code, e.g. HDFC0001234"),
    account: z.string().describe("Beneficiary account number"),
  },
  async ({ ifsc, account }) => {
    const res = await apiGet(
      `/api/v1/graph/beneficiary/${encodeURIComponent(ifsc)}/${encodeURIComponent(account)}`
    );
    return { content: [{ type: "text", text: formatResult(res) }] };
  }
);

server.tool(
  "get_client_graph_metrics",
  "Get graph-based metrics for a client — shared beneficiary count, shared beneficiary ratio, and network density. High values can indicate mule network participation.",
  {
    clientId: z.string().describe("Client ID"),
  },
  async ({ clientId }) => {
    const res = await apiGet(
      `/api/v1/graph/client/${encodeURIComponent(clientId)}`
    );
    return { content: [{ type: "text", text: formatResult(res) }] };
  }
);

// ═══════════════════════════════════════════════════════════
// RULE MANAGEMENT (create / update / delete)
// ═══════════════════════════════════════════════════════════

server.tool(
  "create_rule",
  "Create a new anomaly detection rule. Specify the rule type, risk weight, variance threshold, and optional parameters. The rule becomes active immediately.",
  {
    ruleId: z.string().describe("Unique rule ID, e.g. RULE-CUSTOM-01"),
    name: z.string().describe("Human-readable rule name"),
    description: z.string().optional().describe("What this rule detects"),
    ruleType: z.string().describe("Rule type, e.g. AMOUNT_ANOMALY, TPS_SPIKE, etc."),
    variancePct: z.number().optional().describe("Variance threshold percentage (default varies by type)"),
    riskWeight: z.number().min(0).max(1).describe("Risk weight 0.0-1.0"),
    enabled: z.boolean().optional().describe("Whether rule is active (default true)"),
    params: z.record(z.string(), z.string()).optional().describe("Additional rule-specific parameters"),
  },
  async (params) => {
    const res = await apiPost("/api/v1/rules", params);
    return { content: [{ type: "text", text: formatResult(res) }] };
  }
);

server.tool(
  "update_rule",
  "Update an existing anomaly detection rule. Change its weight, threshold, enabled status, or parameters. Use this to tune rule sensitivity.",
  {
    ruleId: z.string().describe("Rule ID to update, e.g. RULE-IF"),
    name: z.string().optional().describe("Updated rule name"),
    description: z.string().optional().describe("Updated description"),
    ruleType: z.string().optional().describe("Rule type"),
    variancePct: z.number().optional().describe("Updated variance threshold"),
    riskWeight: z.number().min(0).max(1).optional().describe("Updated risk weight 0.0-1.0"),
    enabled: z.boolean().optional().describe("Enable or disable the rule"),
    params: z.record(z.string(), z.string()).optional().describe("Updated parameters"),
  },
  async ({ ruleId, ...updates }) => {
    const res = await apiPut(`/api/v1/rules/${encodeURIComponent(ruleId)}`, { ruleId, ...updates });
    return { content: [{ type: "text", text: formatResult(res) }] };
  }
);

server.tool(
  "delete_rule",
  "Delete an anomaly detection rule by ID. The rule is removed permanently and will no longer be evaluated.",
  {
    ruleId: z.string().describe("Rule ID to delete"),
  },
  async ({ ruleId }) => {
    const res = await apiDelete(`/api/v1/rules/${encodeURIComponent(ruleId)}`);
    return { content: [{ type: "text", text: formatResult(res) }] };
  }
);

// ═══════════════════════════════════════════════════════════
// CONFIGURATION
// ═══════════════════════════════════════════════════════════

server.tool(
  "get_thresholds",
  "Get current risk score thresholds — the PASS/ALERT boundary and ALERT/BLOCK boundary scores.",
  {},
  async () => {
    const res = await apiGet("/api/v1/config/thresholds");
    return { content: [{ type: "text", text: formatResult(res) }] };
  }
);

server.tool(
  "update_thresholds",
  "Update the risk score thresholds. alertThreshold is the PASS→ALERT boundary (default 30), blockThreshold is the ALERT→BLOCK boundary (default 70).",
  {
    alertThreshold: z.number().min(0).max(100).optional().describe("Score above which action becomes ALERT"),
    blockThreshold: z.number().min(0).max(100).optional().describe("Score above which action becomes BLOCK"),
  },
  async (params) => {
    const res = await apiPut("/api/v1/config/thresholds", params);
    return { content: [{ type: "text", text: formatResult(res) }] };
  }
);

server.tool(
  "get_feedback_config",
  "Get feedback and auto-tuning configuration — auto-accept timeout, tuning interval, min samples, weight floor/ceiling.",
  {},
  async () => {
    const res = await apiGet("/api/v1/config/feedback");
    return { content: [{ type: "text", text: formatResult(res) }] };
  }
);

server.tool(
  "update_feedback_config",
  "Update feedback and auto-tuning configuration. Control auto-accept timeout, tuning frequency, and weight bounds.",
  {
    autoAcceptTimeoutMs: z.number().optional().describe("Ms before ALERT items auto-accept (default 300000)"),
    tuningIntervalHours: z.number().optional().describe("Hours between auto-tune runs (default 6)"),
    minSamplesForTuning: z.number().optional().describe("Min feedback samples before tuning (default 5)"),
    weightFloor: z.number().optional().describe("Minimum rule weight after tuning"),
    weightCeiling: z.number().optional().describe("Maximum rule weight after tuning"),
  },
  async (params) => {
    const res = await apiPut("/api/v1/config/feedback", params);
    return { content: [{ type: "text", text: formatResult(res) }] };
  }
);

server.tool(
  "get_silence_config",
  "Get silence detection configuration — enabled status, check interval, silence multiplier, minimum TPS threshold.",
  {},
  async () => {
    const res = await apiGet("/api/v1/config/silence");
    return { content: [{ type: "text", text: formatResult(res) }] };
  }
);

server.tool(
  "update_silence_config",
  "Update silence detection configuration. Control how sensitive silence detection is.",
  {
    enabled: z.boolean().optional().describe("Enable/disable silence detection"),
    checkIntervalMinutes: z.number().optional().describe("Minutes between silence checks"),
    silenceMultiplier: z.number().optional().describe("Multiplier on expected gap to trigger silence"),
    minExpectedTps: z.number().optional().describe("Minimum TPS to consider a client active"),
    minCompletedHours: z.number().optional().describe("Min profile hours before silence detection applies"),
  },
  async (params) => {
    const res = await apiPut("/api/v1/config/silence", params);
    return { content: [{ type: "text", text: formatResult(res) }] };
  }
);

server.tool(
  "get_ollama_config",
  "Get Ollama LLM configuration — host, model, timeout.",
  {},
  async () => {
    const res = await apiGet("/api/v1/config/ollama");
    return { content: [{ type: "text", text: formatResult(res) }] };
  }
);

server.tool(
  "update_ollama_config",
  "Update Ollama LLM configuration — change model, host, or timeout.",
  {
    host: z.string().optional().describe("Ollama host URL"),
    model: z.string().optional().describe("Model name, e.g. llama3.2:1b"),
    timeoutSeconds: z.number().optional().describe("Request timeout in seconds"),
  },
  async (params) => {
    const res = await apiPut("/api/v1/config/ollama", params);
    return { content: [{ type: "text", text: formatResult(res) }] };
  }
);

// ═══════════════════════════════════════════════════════════
// BATCH MODEL TRAINING
// ═══════════════════════════════════════════════════════════

server.tool(
  "batch_train_models",
  "Train Isolation Forest models for ALL clients that have sufficient transaction history. Returns a summary of which clients were trained.",
  {
    numTrees: z.number().int().optional().describe("Number of trees per model (default 100)"),
    sampleSize: z.number().int().optional().describe("Sample size per tree (default 256)"),
  },
  async ({ numTrees, sampleSize }) => {
    const res = await apiPost("/api/v1/models/train", undefined, { numTrees, sampleSize });
    return { content: [{ type: "text", text: formatResult(res) }] };
  }
);

// ═══════════════════════════════════════════════════════════
// ADVANCED ANALYTICS
// ═══════════════════════════════════════════════════════════

server.tool(
  "get_top_risk_clients",
  "Get a ranked list of highest-risk clients by average score, max score, alert count, or block count. Use this to prioritize which clients need investigation.",
  {
    limit: z.number().int().positive().max(100).optional().describe("Number of clients to return (default 10)"),
    sortBy: z
      .enum(["avgScore", "maxScore", "blockCount", "alertCount"])
      .optional()
      .describe("Sort criteria (default avgScore)"),
  },
  async ({ limit, sortBy }) => {
    const res = await apiGet("/api/v1/advanced/top-risk-clients", { limit, sortBy });
    return { content: [{ type: "text", text: formatResult(res) }] };
  }
);

server.tool(
  "get_system_overview",
  "Get a comprehensive system status dashboard — total clients, total transactions, review queue depth by status, silent client count, and beneficiary graph health. Use this to understand the overall state of the system.",
  {},
  async () => {
    const res = await apiGet("/api/v1/advanced/system-overview");
    return { content: [{ type: "text", text: formatResult(res) }] };
  }
);

server.tool(
  "search_transactions",
  "Search transactions across ALL clients with flexible filters: time range, client, transaction type, amount range, beneficiary account. Use this for cross-client investigations.",
  {
    fromDate: z.number().optional().describe("Start time (epoch millis)"),
    toDate: z.number().optional().describe("End time (epoch millis)"),
    clientId: z.string().optional().describe("Filter by client ID"),
    txnType: z.enum(["NEFT", "RTGS", "IMPS", "UPI", "IFT"]).optional().describe("Filter by transaction type"),
    minAmount: z.number().optional().describe("Minimum transaction amount"),
    maxAmount: z.number().optional().describe("Maximum transaction amount"),
    beneficiaryAccount: z.string().optional().describe("Filter by beneficiary account number"),
    limit: z.number().int().positive().max(200).optional().describe("Max results (default 50)"),
  },
  async (params) => {
    const res = await apiGet("/api/v1/advanced/search-transactions", params as Record<string, string | number | undefined>);
    return { content: [{ type: "text", text: formatResult(res) }] };
  }
);

server.tool(
  "simulate_transaction",
  "Simulate evaluating a transaction WITHOUT persisting it. Dry-run mode — runs all 16 rules and returns the score, action, and per-rule breakdown, but does NOT save the transaction, update the profile, or create a review queue entry. Use this for what-if analysis.",
  {
    clientId: z.string().describe("Client ID (uppercase), e.g. CLIENT-001"),
    txnType: z.enum(["NEFT", "RTGS", "IMPS", "UPI", "IFT"]).describe("Transaction type"),
    amount: z.number().positive().describe("Transaction amount in INR"),
    timestamp: z.number().optional().describe("Epoch milliseconds (defaults to now)"),
    beneficiaryAccount: z.string().optional().describe("Beneficiary account number"),
    beneficiaryIfsc: z.string().optional().describe("Beneficiary IFSC code"),
  },
  async (params) => {
    const body = {
      txnId: "SIM-" + Date.now(),
      ...params,
    };
    const res = await apiPost("/api/v1/advanced/simulate", body);
    return { content: [{ type: "text", text: formatResult(res) }] };
  }
);

server.tool(
  "get_anomaly_trends",
  "Get anomaly count and score trends over time. Returns time-bucketed data showing PASS/ALERT/BLOCK counts and average scores. Use this to answer questions like 'are anomalies increasing?' or 'what happened last night?'",
  {
    fromDate: z.number().optional().describe("Start time epoch millis (default: 7 days ago)"),
    toDate: z.number().optional().describe("End time epoch millis (default: now)"),
    bucketSize: z.enum(["15m", "1h", "6h", "1d"]).optional().describe("Time bucket size (default 1h)"),
  },
  async ({ fromDate, toDate, bucketSize }) => {
    const res = await apiGet("/api/v1/advanced/anomaly-trends", { fromDate, toDate, bucketSize });
    return { content: [{ type: "text", text: formatResult(res) }] };
  }
);

server.tool(
  "get_mule_candidates",
  "Get a ranked list of potential mule accounts — beneficiaries with the highest fan-in (most distinct senders). High fan-in indicates money from many clients converging on one account.",
  {
    limit: z.number().int().positive().max(100).optional().describe("Number of candidates (default 20)"),
    minFanIn: z.number().int().positive().optional().describe("Minimum fan-in to qualify (default 2)"),
  },
  async ({ limit, minFanIn }) => {
    const res = await apiGet("/api/v1/advanced/mule-candidates", { limit, minFanIn });
    return { content: [{ type: "text", text: formatResult(res) }] };
  }
);

server.tool(
  "generate_investigation_report",
  "Generate a comprehensive investigation report for a client. Includes profile summary, evaluation statistics, top triggered rules, beneficiary network metrics, and an AI-generated risk narrative. Use this for compliance or investigation documentation.",
  {
    clientId: z.string().describe("Client ID to investigate, e.g. CLIENT-007"),
  },
  async ({ clientId }) => {
    const res = await apiGet(`/api/v1/advanced/investigation-report/${encodeURIComponent(clientId)}`);
    return { content: [{ type: "text", text: formatResult(res) }] };
  }
);

server.tool(
  "get_rule_correlations",
  "Get a rule co-occurrence matrix showing which rules tend to fire together. Includes Jaccard similarity index for each pair. Use this to find compound attack patterns or redundant rules.",
  {
    fromDate: z.number().optional().describe("Start time epoch millis"),
    toDate: z.number().optional().describe("End time epoch millis"),
  },
  async ({ fromDate, toDate }) => {
    const res = await apiGet("/api/v1/advanced/rule-correlations", { fromDate, toDate });
    return { content: [{ type: "text", text: formatResult(res) }] };
  }
);

// ═══════════════════════════════════════════════════════════
// DEMO DATA
// ═══════════════════════════════════════════════════════════

server.tool(
  "generate_demo_data",
  "Generate demo transactions and client profiles with realistic anomaly patterns. Creates 10 clients with varied transaction histories including injected anomalies. Data is immediately visible in the dashboard.",
  {},
  async () => {
    const res = await apiPost("/api/v1/demo/generate");
    return { content: [{ type: "text", text: formatResult(res) }] };
  }
);

// ═══════════════════════════════════════════════════════════
// HEALTH CHECK (resource)
// ═══════════════════════════════════════════════════════════

server.tool(
  "health_check",
  "Check if the Anomaly Detection API is running and healthy. Returns the Spring Boot actuator health status.",
  {},
  async () => {
    const res = await apiGet("/actuator/health");
    return { content: [{ type: "text", text: formatResult(res) }] };
  }
);

// ═══════════════════════════════════════════════════════════
// START SERVER
// ═══════════════════════════════════════════════════════════

async function main() {
  const transport = new StdioServerTransport();
  await server.connect(transport);
  console.error("Anomaly Detection MCP server running on stdio");
}

main().catch((err) => {
  console.error("Fatal error:", err);
  process.exit(1);
});
