package com.bank.anomaly.service;

import com.bank.anomaly.model.ChatIntent;
import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fast keyword-based intent parser that handles common queries without needing an LLM.
 * Used as the primary parser; Ollama is optional for complex/ambiguous queries.
 */
@Service
public class KeywordIntentParser {

    private static final Pattern TIME_PATTERN = Pattern.compile(
            "(\\d+)\\s*(min(?:ute)?s?|hr|hrs|hour|hours)", Pattern.CASE_INSENSITIVE);

    public ChatIntent parse(String message) {
        String lower = message.toLowerCase().trim();

        // Detect time range
        Integer timeMinutes = extractTimeMinutes(lower);

        // Detect transaction type
        String txnType = extractTxnType(lower);

        // Detect action filter
        String action = extractAction(lower);

        // --- Query type detection ---

        // Rules
        if (matches(lower, "rule")) {
            if (matches(lower, "how many", "count")) {
                return intent("COUNT_RULES", txnType, timeMinutes, null, action);
            }
            return intent("LIST_RULES", txnType, timeMinutes, null, action);
        }

        // Review stats
        if (matches(lower, "review") && matches(lower, "stat", "queue stat", "stats")) {
            return intent("REVIEW_STATS", txnType, timeMinutes, null, action);
        }

        // Shared beneficiaries / mule detection
        if (matches(lower, "shared beneficiar", "common beneficiar", "same beneficiar",
                "shared bene", "common bene", "same bene", "same account",
                "mule network", "mule detect", "mule activity")) {
            return intent("SHARED_BENEFICIARIES", txnType, timeMinutes, null, action);
        }

        // Silent clients
        if (matches(lower, "not transact", "not doing txn", "not doing transaction",
                "silent", "inactive", "not done", "haven't transact", "no txn", "no transaction",
                "silenced")) {
            return intent("SILENT_CLIENTS", txnType, timeMinutes, null, action);
        }

        // Count clients
        if (matches(lower, "how many client", "count client", "number of client")) {
            return intent("COUNT_CLIENTS", txnType, timeMinutes, null, action);
        }

        // List clients
        if (matches(lower, "list client", "show client", "which client")) {
            return intent("LIST_CLIENTS", txnType, timeMinutes, null, action);
        }

        // Count transactions
        if (matches(lower, "how many transaction", "how many txn", "count transaction", "count txn")) {
            return intent("COUNT_TRANSACTIONS", txnType, timeMinutes, null, action);
        }

        // List transactions
        if (matches(lower, "list transaction", "show transaction", "list txn", "show txn",
                "transaction blocked", "transactions blocked", "txn blocked")) {
            return intent("LIST_TRANSACTIONS", txnType, timeMinutes, null, action);
        }

        // Fallback: if action is present, likely asking about transactions
        if (action != null) {
            if (matches(lower, "how many")) {
                return intent("COUNT_TRANSACTIONS", txnType, timeMinutes, null, action);
            }
            return intent("LIST_TRANSACTIONS", txnType, timeMinutes, null, action);
        }

        // Generic "how many" with txn type → count clients
        if (matches(lower, "how many") && txnType != null) {
            return intent("COUNT_CLIENTS", txnType, timeMinutes, null, action);
        }

        // Generic "list" / "show" → list transactions
        if (matches(lower, "list", "show")) {
            return intent("LIST_TRANSACTIONS", txnType, timeMinutes, null, action);
        }

        return null; // Could not parse
    }

    private boolean matches(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }

    private Integer extractTimeMinutes(String lower) {
        Matcher m = TIME_PATTERN.matcher(lower);
        if (m.find()) {
            int value = Integer.parseInt(m.group(1));
            String unit = m.group(2).toLowerCase();
            if (unit.startsWith("h")) {
                return value * 60;
            }
            return value;
        }
        return null;
    }

    private String extractTxnType(String lower) {
        if (lower.contains("upi")) return "UPI";
        if (lower.contains("neft")) return "NEFT";
        if (lower.contains("rtgs")) return "RTGS";
        if (lower.contains("imps")) return "IMPS";
        if (lower.contains("ift")) return "IFT";
        return null;
    }

    private String extractAction(String lower) {
        if (matches(lower, "block")) return "BLOCK";
        if (matches(lower, "alert")) return "ALERT";
        if (matches(lower, "pass")) return "PASS";
        return null;
    }

    private ChatIntent intent(String queryType, String txnType, Integer timeMinutes,
                               String riskLevel, String action) {
        ChatIntent intent = new ChatIntent();
        intent.setQueryType(queryType);
        intent.setLimit(100);

        ChatIntent.ChatFilters filters = new ChatIntent.ChatFilters();
        filters.setTxnType(txnType);
        filters.setTimeRangeMinutes(timeMinutes);
        filters.setRiskLevel(riskLevel);
        filters.setAction(action);
        intent.setFilters(filters);

        return intent;
    }
}
