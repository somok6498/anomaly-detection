package com.bank.anomaly.service;

import com.bank.anomaly.model.ChatIntent;
import com.bank.anomaly.model.EvaluationResult;
import com.bank.anomaly.model.RuleResult;
import com.bank.anomaly.model.Transaction;
import com.bank.anomaly.repository.AiFeedbackRepository;
import com.bank.anomaly.repository.RiskResultRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
    private static final String MODEL = "llama3.2:1b";
    private static final int TIMEOUT_SECONDS = 300;

    private static final Pattern JSON_BLOCK = Pattern.compile(
            "\\{[^{}]*\"queryType\"[^{}]*\\}", Pattern.DOTALL);

    private static final int MAX_NEGATIVE_EXAMPLES = 3;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String ollamaHost;
    private final AiFeedbackRepository aiFeedbackRepository;
    private final RiskResultRepository riskResultRepository;

    public OllamaService(
            @Value("${ollama.host:http://localhost:11434}") String ollamaHost,
            AiFeedbackRepository aiFeedbackRepository,
            RiskResultRepository riskResultRepository) {
        this.ollamaHost = ollamaHost;
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
                    "model", MODEL,
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
                    .uri(URI.create(ollamaHost + "/api/generate"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
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
                    "model", MODEL,
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
                    .uri(URI.create(ollamaHost + "/api/generate"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
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

    public static class OllamaUnavailableException extends RuntimeException {
        public OllamaUnavailableException(String msg) { super(msg); }
    }

    public static class IntentParseException extends RuntimeException {
        public IntentParseException(String msg) { super(msg); }
    }
}
