package com.bank.anomaly.service;

import com.bank.anomaly.config.OllamaConfig;
import com.bank.anomaly.model.ChatIntent;
import com.bank.anomaly.model.ClientProfile;
import com.bank.anomaly.model.EvaluationResult;
import com.bank.anomaly.model.ReviewQueueItem;
import com.bank.anomaly.model.RuleResult;
import com.bank.anomaly.model.Transaction;
import com.bank.anomaly.repository.AiFeedbackRepository;
import com.bank.anomaly.repository.RiskResultRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class OllamaService {

    private static final Logger log = LoggerFactory.getLogger(OllamaService.class);

    private static final Pattern JSON_BLOCK = Pattern.compile(
            "\\{[^{}]*\"queryType\"[^{}]*\\}", Pattern.DOTALL);

    private static final int MAX_NEGATIVE_EXAMPLES = 3;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final OllamaConfig ollamaConfig;
    private final AiFeedbackRepository aiFeedbackRepository;
    private final RiskResultRepository riskResultRepository;

    public OllamaService(
            OllamaConfig ollamaConfig,
            AiFeedbackRepository aiFeedbackRepository,
            RiskResultRepository riskResultRepository) {
        this.ollamaConfig = ollamaConfig;
        this.aiFeedbackRepository = aiFeedbackRepository;
        this.riskResultRepository = riskResultRepository;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    private static final String SYSTEM_PROMPT = """
            You are a banking data query assistant. Convert the user's natural language question
            into a structured JSON intent. Respond with ONLY valid JSON, no explanation.

            Valid queryTypes:
            - COUNT_CLIENTS: count distinct clients matching filters
            - COUNT_TRANSACTIONS: count transactions matching filters
            - LIST_TRANSACTIONS: list transaction records matching filters
            - LIST_CLIENTS: list client IDs matching filters
            - COUNT_RULES: count anomaly detection rules
            - LIST_RULES: list all anomaly detection rules
            - REVIEW_STATS: review queue statistics (pending, tp, fp, auto-accepted counts)
            - SILENT_CLIENTS: clients who have not transacted in a time window

            Valid filter fields (all optional):
            - txnType: one of "UPI", "NEFT", "RTGS", "IMPS", "IFT"
            - timeRangeMinutes: integer (e.g. 60 for last 1 hour, 15 for last 15 minutes)
            - clientId: specific client ID string
            - riskLevel: one of "LOW", "MEDIUM", "HIGH", "CRITICAL"
            - action: one of "PASS", "ALERT", "BLOCK"
            - feedbackStatus: one of "PENDING", "TRUE_POSITIVE", "FALSE_POSITIVE", "AUTO_ACCEPTED"

            Return JSON like:
            {"queryType":"COUNT_CLIENTS","filters":{"txnType":"UPI","timeRangeMinutes":15},"groupBy":null,"limit":100}

            Examples:
            - "how many clients have done txn in last hr" -> {"queryType":"COUNT_CLIENTS","filters":{"timeRangeMinutes":60},"groupBy":null,"limit":100}
            - "how many clients violated rules" -> {"queryType":"COUNT_CLIENTS","filters":{"action":"ALERT"},"groupBy":null,"limit":100}
            - "how many rules are there" -> {"queryType":"COUNT_RULES","filters":{},"groupBy":null,"limit":100}
            - "clients doing UPI in last 15 mins" -> {"queryType":"LIST_CLIENTS","filters":{"txnType":"UPI","timeRangeMinutes":15},"groupBy":null,"limit":100}
            - "how many clients are not doing txn in last hr" -> {"queryType":"SILENT_CLIENTS","filters":{"timeRangeMinutes":60},"groupBy":null,"limit":100}
            - "show me review queue stats" -> {"queryType":"REVIEW_STATS","filters":{},"groupBy":null,"limit":100}
            - "list all rules" -> {"queryType":"LIST_RULES","filters":{},"groupBy":null,"limit":100}
            - "transactions blocked in last 30 mins" -> {"queryType":"LIST_TRANSACTIONS","filters":{"action":"BLOCK","timeRangeMinutes":30},"groupBy":null,"limit":100}
            """;

    public ChatIntent parseIntent(String userMessage) {
        String ollamaResponse = callOllama(userMessage);
        return extractAndParseIntent(ollamaResponse);
    }

    private String callOllama(String userMessage) {
        try {
            Map<String, Object> body = Map.of(
                    "model", ollamaConfig.getModel(),
                    "prompt", userMessage,
                    "system", SYSTEM_PROMPT,
                    "stream", false,
                    "options", Map.of(
                            "temperature", 0.1,
                            "num_predict", 200
                    )
            );
            String bodyJson = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaConfig.getHost() + "/api/generate"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(ollamaConfig.getTimeoutSeconds()))
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new OllamaUnavailableException(
                        "Ollama returned HTTP " + response.statusCode());
            }

            JsonNode root = objectMapper.readTree(response.body());
            return root.path("response").asText("");

        } catch (OllamaUnavailableException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to call Ollama: {}", e.getMessage());
            throw new OllamaUnavailableException("Ollama not reachable: " + e.getMessage());
        }
    }

    private ChatIntent extractAndParseIntent(String llmOutput) {
        // First try: direct parse
        try {
            return objectMapper.readValue(llmOutput.trim(), ChatIntent.class);
        } catch (Exception ignored) {}

        // Second try: extract JSON object using regex
        Matcher m = JSON_BLOCK.matcher(llmOutput);
        String jsonStr = m.find() ? m.group() : null;

        if (jsonStr == null) {
            log.warn("Could not extract JSON from LLM output: {}", llmOutput);
            throw new IntentParseException(
                    "I couldn't understand that question. Please try rephrasing it. " +
                    "Example: 'how many clients did UPI in last 15 mins'");
        }

        try {
            return objectMapper.readValue(jsonStr, ChatIntent.class);
        } catch (Exception e) {
            log.warn("Failed to parse extracted JSON: {} | Error: {}", jsonStr, e.getMessage());
            throw new IntentParseException(
                    "I couldn't parse the query structure. Please try a simpler question.");
        }
    }

    private static final String EXPLANATION_SYSTEM_PROMPT_BASE = """
            You are a banking fraud analyst AI. Given a transaction and its anomaly evaluation results,
            write a clear, concise explanation in plain English for a bank operations reviewer.

            Your explanation should:
            - State what happened in 1-2 sentences (the transaction details)
            - Explain WHY the system flagged it (which rules triggered and what they mean)
            - Highlight the most concerning factor
            - Be 3-5 sentences total, professional tone
            - Use actual numbers from the data (amounts, scores, deviations)
            - Do NOT use technical jargon like "EWMA", "z-score", or "isolation forest" — translate to plain language

            Respond with ONLY the explanation text, no JSON, no markdown, no bullet points.
            """;

    /**
     * Builds the system prompt, appending negative examples from operator feedback
     * so the LLM avoids patterns that reviewers found unhelpful.
     */
    private String buildExplanationSystemPrompt() {
        try {
            List<String> notHelpfulTxnIds = aiFeedbackRepository.findRecentNotHelpfulTxnIds(MAX_NEGATIVE_EXAMPLES);
            if (notHelpfulTxnIds.isEmpty()) {
                return EXPLANATION_SYSTEM_PROMPT_BASE;
            }

            List<String> badExplanations = notHelpfulTxnIds.stream()
                    .map(txnId -> {
                        EvaluationResult evalResult = riskResultRepository.findByTxnId(txnId);
                        return evalResult != null ? evalResult.getAiExplanation() : null;
                    })
                    .filter(Objects::nonNull)
                    .filter(e -> !e.isBlank())
                    .toList();

            if (badExplanations.isEmpty()) {
                return EXPLANATION_SYSTEM_PROMPT_BASE;
            }

            StringBuilder sb = new StringBuilder(EXPLANATION_SYSTEM_PROMPT_BASE);
            sb.append("\n\nIMPORTANT: The following explanations were rated NOT HELPFUL by reviewers. ");
            sb.append("Avoid writing explanations similar to these. Learn from what went wrong:\n\n");
            for (int i = 0; i < badExplanations.size(); i++) {
                sb.append("BAD EXAMPLE ").append(i + 1).append(": \"")
                  .append(badExplanations.get(i)).append("\"\n\n");
            }
            sb.append("Write a BETTER explanation that avoids the patterns shown above.\n");
            return sb.toString();

        } catch (Exception e) {
            log.debug("Could not load feedback for prompt enrichment: {}", e.getMessage());
            return EXPLANATION_SYSTEM_PROMPT_BASE;
        }
    }

    public String generateExplanation(Transaction txn, EvaluationResult eval) {
        List<RuleResult> triggered = eval.getRuleResults().stream()
                .filter(RuleResult::isTriggered)
                .collect(Collectors.toList());

        StringBuilder prompt = new StringBuilder();
        prompt.append("Transaction: ").append(txn.getTxnType())
              .append(" of ₹").append(String.format("%,.2f", txn.getAmount()))
              .append(" by client ").append(txn.getClientId())
              .append(" (ID: ").append(txn.getTxnId()).append(")\n");
        prompt.append("Risk Score: ").append(String.format("%.1f", eval.getCompositeScore()))
              .append("/100 | Risk Level: ").append(eval.getRiskLevel())
              .append(" | Action: ").append(eval.getAction()).append("\n\n");
        prompt.append("Triggered Rules:\n");

        for (RuleResult r : triggered) {
            prompt.append("- ").append(r.getRuleName())
                  .append(" (score: ").append(String.format("%.1f", r.getPartialScore()))
                  .append(", deviation: ").append(String.format("%.1f%%", r.getDeviationPct()))
                  .append("): ").append(r.getReason()).append("\n");
        }

        if (triggered.isEmpty()) {
            prompt.append("- No rules triggered (transaction passed all checks)\n");
        }

        String systemPrompt = buildExplanationSystemPrompt();

        try {
            Map<String, Object> body = Map.of(
                    "model", ollamaConfig.getModel(),
                    "prompt", prompt.toString(),
                    "system", systemPrompt,
                    "stream", false,
                    "options", Map.of(
                            "temperature", 0.3,
                            "num_predict", 300
                    )
            );
            String bodyJson = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaConfig.getHost() + "/api/generate"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(ollamaConfig.getTimeoutSeconds()))
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("Ollama returned HTTP {} for explanation generation", response.statusCode());
                return null;
            }

            JsonNode root = objectMapper.readTree(response.body());
            String explanation = root.path("response").asText("").trim();
            return explanation.isEmpty() ? null : explanation;

        } catch (Exception e) {
            log.warn("Failed to generate AI explanation: {}", e.getMessage());
            return null;
        }
    }

    private static final String CLIENT_NARRATIVE_SYSTEM_PROMPT = """
            You are a banking fraud analyst AI. Given a client's behavioral profile and recent transaction history,
            write a clear risk narrative for a bank operations reviewer.

            Your narrative should:
            - Summarize the client's normal transaction behavior (typical amounts, frequency, channels)
            - Highlight any concerning patterns or anomalies
            - Note the ratio of flagged vs. clean transactions
            - Mention beneficiary concentration if relevant
            - Assess overall risk posture in 1 sentence
            - Be 4-7 sentences total, professional tone
            - Use actual numbers from the data
            - Do NOT use technical jargon like "EWMA" or "z-score" — translate to plain language (e.g., "typical amount", "usual rate")

            Respond with ONLY the narrative text, no JSON, no markdown, no bullet points.
            """;

    public String generateClientNarrative(ClientProfile profile, List<EvaluationResult> recentEvals) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Client: ").append(profile.getClientId()).append("\n");
        prompt.append("Total Transactions: ").append(profile.getTotalTxnCount()).append("\n");
        prompt.append("Typical Amount: ₹").append(String.format("%,.2f", profile.getEwmaAmount())).append("\n");
        prompt.append("Amount Variability (Std Dev): ₹").append(String.format("%,.2f", profile.getAmountStdDev())).append("\n");
        prompt.append("Typical Rate: ").append(String.format("%.1f", profile.getEwmaHourlyTps())).append(" txns/hour\n");
        prompt.append("Distinct Beneficiaries: ").append(profile.getDistinctBeneficiaryCount()).append("\n");

        // Transaction type breakdown
        if (profile.getTxnTypeCounts() != null && !profile.getTxnTypeCounts().isEmpty()) {
            prompt.append("Channel Breakdown: ");
            profile.getTxnTypeCounts().forEach((type, count) ->
                    prompt.append(type).append("=").append(count).append(" "));
            prompt.append("\n");
        }

        // Recent evaluations summary
        if (recentEvals != null && !recentEvals.isEmpty()) {
            long alerts = recentEvals.stream().filter(e -> "ALERT".equals(e.getAction())).count();
            long blocks = recentEvals.stream().filter(e -> "BLOCK".equals(e.getAction())).count();
            long passes = recentEvals.stream().filter(e -> "PASS".equals(e.getAction())).count();
            double avgScore = recentEvals.stream().mapToDouble(EvaluationResult::getCompositeScore).average().orElse(0);
            double maxScore = recentEvals.stream().mapToDouble(EvaluationResult::getCompositeScore).max().orElse(0);

            prompt.append("\nRecent Transaction History (last ").append(recentEvals.size()).append(" evaluations):\n");
            prompt.append("- PASS: ").append(passes).append(", ALERT: ").append(alerts).append(", BLOCK: ").append(blocks).append("\n");
            prompt.append("- Average Risk Score: ").append(String.format("%.1f", avgScore)).append("/100\n");
            prompt.append("- Highest Risk Score: ").append(String.format("%.1f", maxScore)).append("/100\n");

            // Top triggered rules across recent evals
            Map<String, Long> ruleCounts = recentEvals.stream()
                    .filter(e -> e.getRuleResults() != null)
                    .flatMap(e -> e.getRuleResults().stream())
                    .filter(RuleResult::isTriggered)
                    .collect(Collectors.groupingBy(RuleResult::getRuleName, Collectors.counting()));

            if (!ruleCounts.isEmpty()) {
                prompt.append("- Most Frequently Triggered Rules: ");
                ruleCounts.entrySet().stream()
                        .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                        .limit(5)
                        .forEach(entry -> prompt.append(entry.getKey()).append(" (").append(entry.getValue()).append("x), "));
                prompt.setLength(prompt.length() - 2); // remove trailing ", "
                prompt.append("\n");
            }
        } else {
            prompt.append("\nNo recent transaction evaluations available.\n");
        }

        try {
            Map<String, Object> body = Map.of(
                    "model", ollamaConfig.getModel(),
                    "prompt", prompt.toString(),
                    "system", CLIENT_NARRATIVE_SYSTEM_PROMPT,
                    "stream", false,
                    "options", Map.of(
                            "temperature", 0.3,
                            "num_predict", 400
                    )
            );
            String bodyJson = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaConfig.getHost() + "/api/generate"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(ollamaConfig.getTimeoutSeconds()))
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("Ollama returned HTTP {} for client narrative generation", response.statusCode());
                return null;
            }

            JsonNode root = objectMapper.readTree(response.body());
            String narrative = root.path("response").asText("").trim();
            return narrative.isEmpty() ? null : narrative;

        } catch (Exception e) {
            log.warn("Failed to generate client narrative: {}", e.getMessage());
            return null;
        }
    }

    private static final String TRIAGE_SYSTEM_PROMPT = """
            You are a banking fraud analyst. Rank these alerts by urgency. Higher scores, BLOCK actions, \
            multiple rules, and patterns like dormancy reactivation or mule networks are more urgent.

            IMPORTANT: Use the EXACT txnId values from the input (e.g. DEMO-XCHAN-4). \
            For urgency, pick exactly ONE of: CRITICAL, HIGH, MEDIUM, LOW.

            Reply with ONLY a JSON array. Example:
            [{"txnId":"DEMO-ABC-1","rank":1,"urgency":"CRITICAL","reasoning":"high score with mule pattern"}]
            """;

    public String generateAlertTriage(List<ReviewQueueItem> pendingItems, Map<String, EvaluationResult> evalMap) {
        if (pendingItems.isEmpty()) return null;

        StringBuilder prompt = new StringBuilder();
        prompt.append("Rank these ").append(Math.min(pendingItems.size(), 10)).append(" alerts by urgency:\n\n");

        for (int i = 0; i < Math.min(pendingItems.size(), 10); i++) {
            ReviewQueueItem item = pendingItems.get(i);
            prompt.append("- ").append(item.getTxnId())
                  .append(" ").append(item.getAction())
                  .append(" score=").append(String.format("%.0f", item.getCompositeScore()))
                  .append(" rules=").append(String.join(",", item.getTriggeredRuleIds()));

            EvaluationResult eval = evalMap.get(item.getTxnId());
            if (eval != null && eval.getRuleResults() != null) {
                List<String> triggered = eval.getRuleResults().stream()
                        .filter(RuleResult::isTriggered)
                        .map(RuleResult::getRuleName)
                        .toList();
                if (!triggered.isEmpty()) {
                    prompt.append(" (").append(String.join(", ", triggered)).append(")");
                }
            }
            prompt.append("\n");
        }

        try {
            Map<String, Object> body = Map.of(
                    "model", ollamaConfig.getModel(),
                    "prompt", prompt.toString(),
                    "system", TRIAGE_SYSTEM_PROMPT,
                    "stream", false,
                    "options", Map.of(
                            "temperature", 0.2,
                            "num_predict", 500
                    )
            );
            String bodyJson = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaConfig.getHost() + "/api/generate"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(ollamaConfig.getTimeoutSeconds()))
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("Ollama returned HTTP {} for alert triage", response.statusCode());
                return null;
            }

            JsonNode root = objectMapper.readTree(response.body());
            String triage = root.path("response").asText("").trim();
            if (triage.isEmpty()) return null;

            // Clean up LLM output: strip markdown fences
            triage = triage.replaceAll("(?s)```json\\s*", "").replaceAll("(?s)```\\s*", "").trim();

            // Try to parse individual JSON objects from the response
            java.util.List<JsonNode> items = new java.util.ArrayList<>();
            // Find all complete JSON objects using brace matching
            int depth = 0;
            int objStart = -1;
            for (int ci = 0; ci < triage.length(); ci++) {
                char ch = triage.charAt(ci);
                if (ch == '{') {
                    if (depth == 0) objStart = ci;
                    depth++;
                } else if (ch == '}') {
                    depth--;
                    if (depth == 0 && objStart >= 0) {
                        String objStr = triage.substring(objStart, ci + 1);
                        try {
                            items.add(objectMapper.readTree(objStr));
                        } catch (Exception ignored) {}
                        objStart = -1;
                    }
                }
            }

            if (items.isEmpty()) return null;

            // Build a clean JSON array
            com.fasterxml.jackson.databind.node.ArrayNode arr = objectMapper.createArrayNode();
            items.forEach(arr::add);
            return objectMapper.writeValueAsString(arr);

        } catch (Exception e) {
            log.warn("Failed to generate alert triage: {}", e.getMessage());
            return null;
        }
    }

    private static final String PATTERN_LABEL_SYSTEM_PROMPT = """
            You are a banking fraud analyst. Given the triggered anomaly rules for a transaction, \
            classify the attack pattern into exactly ONE of these categories:

            SMURFING, MULE_ACCOUNT, ACCOUNT_TAKEOVER, STRUCTURING, DORMANCY_EXPLOITATION, \
            VELOCITY_ABUSE, BENEFICIARY_FRAUD, MULTI_VECTOR_ATTACK, UNUSUAL_BEHAVIOR, CLEAN

            Rules:
            - SMURFING: many small transactions to stay under thresholds
            - MULE_ACCOUNT: money received and quickly sent to multiple beneficiaries
            - ACCOUNT_TAKEOVER: sudden change in behavior, new device/IP, unusual channel
            - STRUCTURING: amounts just below reporting thresholds
            - DORMANCY_EXPLOITATION: reactivated dormant account with suspicious activity
            - VELOCITY_ABUSE: abnormally high transaction frequency
            - BENEFICIARY_FRAUD: new or suspicious beneficiary patterns
            - MULTI_VECTOR_ATTACK: multiple distinct attack patterns combined
            - UNUSUAL_BEHAVIOR: anomalous but doesn't fit other categories
            - CLEAN: no significant risk detected

            Reply with ONLY a JSON object. Example:
            {"pattern":"MULE_ACCOUNT","confidence":"HIGH","summary":"rapid fund dispersal to new beneficiaries"}
            """;

    public String generatePatternLabel(EvaluationResult eval) {
        List<RuleResult> triggered = eval.getRuleResults().stream()
                .filter(RuleResult::isTriggered)
                .collect(Collectors.toList());

        if (triggered.isEmpty()) {
            return "{\"pattern\":\"CLEAN\",\"confidence\":\"HIGH\",\"summary\":\"no rules triggered\"}";
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("Transaction ").append(eval.getTxnId())
              .append(" | Action: ").append(eval.getAction())
              .append(" | Score: ").append(String.format("%.0f", eval.getCompositeScore()))
              .append("/100\n\nTriggered rules:\n");

        for (RuleResult r : triggered) {
            prompt.append("- ").append(r.getRuleName())
                  .append(" (score=").append(String.format("%.0f", r.getPartialScore()))
                  .append("): ").append(r.getReason()).append("\n");
        }

        try {
            Map<String, Object> body = Map.of(
                    "model", ollamaConfig.getModel(),
                    "prompt", prompt.toString(),
                    "system", PATTERN_LABEL_SYSTEM_PROMPT,
                    "stream", false,
                    "options", Map.of(
                            "temperature", 0.1,
                            "num_predict", 100
                    )
            );
            String bodyJson = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaConfig.getHost() + "/api/generate"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(ollamaConfig.getTimeoutSeconds()))
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("Ollama returned HTTP {} for pattern labeling", response.statusCode());
                return null;
            }

            JsonNode root = objectMapper.readTree(response.body());
            String rawOutput = root.path("response").asText("").trim();
            if (rawOutput.isEmpty()) return null;

            // Strip markdown fences
            rawOutput = rawOutput.replaceAll("(?s)```json\\s*", "").replaceAll("(?s)```\\s*", "").trim();

            // Extract first JSON object using brace matching
            int depth = 0;
            int objStart = -1;
            for (int ci = 0; ci < rawOutput.length(); ci++) {
                char ch = rawOutput.charAt(ci);
                if (ch == '{') {
                    if (depth == 0) objStart = ci;
                    depth++;
                } else if (ch == '}') {
                    depth--;
                    if (depth == 0 && objStart >= 0) {
                        String objStr = rawOutput.substring(objStart, ci + 1);
                        try {
                            JsonNode parsed = objectMapper.readTree(objStr);
                            // Validate pattern field exists
                            String pattern = parsed.path("pattern").asText("");
                            if (!pattern.isEmpty()) {
                                return objectMapper.writeValueAsString(parsed);
                            }
                        } catch (Exception ignored) {}
                        objStart = -1;
                    }
                }
            }
            return null;

        } catch (Exception e) {
            log.warn("Failed to generate pattern label: {}", e.getMessage());
            return null;
        }
    }

    public static class OllamaUnavailableException extends RuntimeException {
        public OllamaUnavailableException(String msg) { super(msg); }
    }

    public static class IntentParseException extends RuntimeException {
        public IntentParseException(String msg) { super(msg); }
    }
}
