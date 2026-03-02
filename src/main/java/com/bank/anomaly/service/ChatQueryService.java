package com.bank.anomaly.service;

import com.bank.anomaly.model.*;
import com.bank.anomaly.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ChatQueryService {

    private static final Logger log = LoggerFactory.getLogger(ChatQueryService.class);
    private static final int MAX_SCAN_RESULTS = 500;
    private static final int MAX_TABLE_ROWS = 200;

    private final TransactionRepository transactionRepository;
    private final RiskResultRepository riskResultRepository;
    private final ClientProfileRepository clientProfileRepository;
    private final RuleRepository ruleRepository;
    private final ReviewQueueRepository reviewQueueRepository;

    public ChatQueryService(
            TransactionRepository transactionRepository,
            RiskResultRepository riskResultRepository,
            ClientProfileRepository clientProfileRepository,
            RuleRepository ruleRepository,
            ReviewQueueRepository reviewQueueRepository) {
        this.transactionRepository = transactionRepository;
        this.riskResultRepository = riskResultRepository;
        this.clientProfileRepository = clientProfileRepository;
        this.ruleRepository = ruleRepository;
        this.reviewQueueRepository = reviewQueueRepository;
    }

    public ChatResponse execute(ChatIntent intent) {
        if (intent.getQueryType() == null) {
            return errorResponse("Could not determine query type from your question.");
        }

        try {
            return switch (intent.getQueryType()) {
                case "COUNT_CLIENTS"      -> handleCountClients(intent);
                case "COUNT_TRANSACTIONS" -> handleCountTransactions(intent);
                case "LIST_TRANSACTIONS"  -> handleListTransactions(intent);
                case "LIST_CLIENTS"       -> handleListClients(intent);
                case "COUNT_RULES"        -> handleCountRules(intent);
                case "LIST_RULES"         -> handleListRules(intent);
                case "REVIEW_STATS"       -> handleReviewStats(intent);
                case "SILENT_CLIENTS"     -> handleSilentClients(intent);
                default -> errorResponse("Unknown query type: " + intent.getQueryType());
            };
        } catch (Exception e) {
            log.error("Error executing chat query: {}", e.getMessage(), e);
            return errorResponse("An error occurred while executing your query.");
        }
    }

    private ChatResponse handleCountClients(ChatIntent intent) {
        ChatIntent.ChatFilters f = filters(intent);
        long count;
        String descriptor = buildDescriptor(f);

        if (hasTimeFilter(f) || f.getTxnType() != null) {
            long[] range = timeRange(f);
            count = transactionRepository.countDistinctClientsByTimeRange(
                    range[0], range[1], f.getTxnType());
        } else if (f.getRiskLevel() != null || f.getAction() != null) {
            count = riskResultRepository.countDistinctClientsByTimeRange(
                    0L, Long.MAX_VALUE, f.getRiskLevel(), f.getAction());
        } else {
            count = clientProfileRepository.scanAllProfiles().size();
        }

        return ChatResponse.builder()
                .summary(String.format("Found %d client%s%s",
                        count, count == 1 ? "" : "s", descriptor))
                .isTabular(false)
                .queryType(intent.getQueryType())
                .columns(Collections.emptyList())
                .rows(Collections.emptyList())
                .build();
    }

    private ChatResponse handleCountTransactions(ChatIntent intent) {
        ChatIntent.ChatFilters f = filters(intent);
        long[] range = timeRange(f);
        String descriptor = buildDescriptor(f);

        List<Transaction> txns = transactionRepository.findByTimeRange(
                range[0], range[1], f.getTxnType(), MAX_SCAN_RESULTS);

        String summary = String.format("Found %d transaction%s%s",
                txns.size(), txns.size() == 1 ? "" : "s", descriptor);
        if (txns.size() >= MAX_SCAN_RESULTS) {
            summary += " (capped at " + MAX_SCAN_RESULTS + ")";
        }

        return ChatResponse.builder()
                .summary(summary)
                .isTabular(false)
                .queryType(intent.getQueryType())
                .columns(Collections.emptyList())
                .rows(Collections.emptyList())
                .build();
    }

    private ChatResponse handleListTransactions(ChatIntent intent) {
        ChatIntent.ChatFilters f = filters(intent);
        long[] range = timeRange(f);

        List<Transaction> txns = transactionRepository.findByTimeRange(
                range[0], range[1], f.getTxnType(), MAX_SCAN_RESULTS);

        // If action filter, cross-reference with evaluations
        if (f.getAction() != null) {
            List<EvaluationResult> evals = riskResultRepository.findByTimeRange(
                    range[0], range[1], f.getRiskLevel(), f.getAction(), MAX_SCAN_RESULTS);
            Set<String> matchingTxnIds = evals.stream()
                    .map(EvaluationResult::getTxnId)
                    .collect(Collectors.toSet());
            txns = txns.stream()
                    .filter(t -> matchingTxnIds.contains(t.getTxnId()))
                    .collect(Collectors.toList());
        }

        txns.sort(Comparator.comparingLong(Transaction::getTimestamp).reversed());

        int limit = Math.min(intent.getLimit() != null ? intent.getLimit() : 100, MAX_TABLE_ROWS);
        List<Transaction> page = txns.size() > limit ? txns.subList(0, limit) : txns;

        List<List<String>> rows = page.stream()
                .map(t -> List.of(
                        t.getTxnId(),
                        t.getClientId(),
                        t.getTxnType(),
                        String.format("%.2f", t.getAmount()),
                        Instant.ofEpochMilli(t.getTimestamp()).toString()
                ))
                .collect(Collectors.toList());

        String descriptor = buildDescriptor(f);
        return ChatResponse.builder()
                .summary(String.format("Showing %d of %d transaction%s%s",
                        page.size(), txns.size(), txns.size() == 1 ? "" : "s", descriptor))
                .isTabular(true)
                .queryType(intent.getQueryType())
                .columns(List.of("TXN ID", "CLIENT ID", "TYPE", "AMOUNT (INR)", "TIMESTAMP"))
                .rows(rows)
                .build();
    }

    private ChatResponse handleListClients(ChatIntent intent) {
        ChatIntent.ChatFilters f = filters(intent);
        int limit = Math.min(intent.getLimit() != null ? intent.getLimit() : 100, MAX_TABLE_ROWS);
        String descriptor = buildDescriptor(f);

        if (hasTimeFilter(f) || f.getTxnType() != null) {
            long[] range = timeRange(f);
            List<Transaction> txns = transactionRepository.findByTimeRange(
                    range[0], range[1], f.getTxnType(), MAX_SCAN_RESULTS);

            Map<String, long[]> clientStats = new LinkedHashMap<>();
            for (Transaction t : txns) {
                clientStats.computeIfAbsent(t.getClientId(), k -> new long[2]);
                clientStats.get(t.getClientId())[0]++;
                clientStats.get(t.getClientId())[1] += (long) t.getAmount();
            }

            List<List<String>> rows = clientStats.entrySet().stream()
                    .sorted(Map.Entry.<String, long[]>comparingByValue(
                            (a, b) -> Long.compare(b[0], a[0])))
                    .limit(limit)
                    .map(e -> List.of(
                            e.getKey(),
                            String.valueOf(e.getValue()[0]),
                            String.format("%.2f", (double) e.getValue()[1])
                    ))
                    .collect(Collectors.toList());

            return ChatResponse.builder()
                    .summary(String.format("Found %d client%s%s", clientStats.size(),
                            clientStats.size() == 1 ? "" : "s", descriptor))
                    .isTabular(true)
                    .queryType(intent.getQueryType())
                    .columns(List.of("CLIENT ID", "TXN COUNT", "TOTAL AMOUNT (INR)"))
                    .rows(rows)
                    .build();
        }

        List<ClientProfile> profiles = clientProfileRepository.scanAllProfiles();
        List<List<String>> rows = profiles.stream()
                .limit(limit)
                .map(p -> List.of(
                        p.getClientId(),
                        String.valueOf(p.getTotalTxnCount()),
                        String.format("%.2f", p.getEwmaAmount())
                ))
                .collect(Collectors.toList());

        return ChatResponse.builder()
                .summary(String.format("Found %d client profile%s%s", profiles.size(),
                        profiles.size() == 1 ? "" : "s", descriptor))
                .isTabular(true)
                .queryType(intent.getQueryType())
                .columns(List.of("CLIENT ID", "TOTAL TXN COUNT", "AVG AMOUNT (EWMA)"))
                .rows(rows)
                .build();
    }

    private ChatResponse handleCountRules(ChatIntent intent) {
        int count = ruleRepository.findAll().size();
        return ChatResponse.builder()
                .summary(String.format("There are %d anomaly detection rule%s configured",
                        count, count == 1 ? "" : "s"))
                .isTabular(false)
                .queryType(intent.getQueryType())
                .columns(Collections.emptyList())
                .rows(Collections.emptyList())
                .build();
    }

    private ChatResponse handleListRules(ChatIntent intent) {
        List<AnomalyRule> rules = ruleRepository.findAll();
        List<List<String>> rows = rules.stream()
                .map(r -> List.of(
                        r.getRuleId(),
                        r.getName(),
                        r.getRuleType().name(),
                        String.format("%.1f", r.getRiskWeight()),
                        r.isEnabled() ? "Yes" : "No"
                ))
                .collect(Collectors.toList());

        return ChatResponse.builder()
                .summary(String.format("There are %d anomaly detection rule%s",
                        rules.size(), rules.size() == 1 ? "" : "s"))
                .isTabular(true)
                .queryType(intent.getQueryType())
                .columns(List.of("RULE ID", "NAME", "TYPE", "WEIGHT", "ENABLED"))
                .rows(rows)
                .build();
    }

    private ChatResponse handleReviewStats(ChatIntent intent) {
        int[] counts = reviewQueueRepository.countByStatus();
        List<List<String>> rows = List.of(
                List.of("Pending Review", String.valueOf(counts[0])),
                List.of("True Positive (Confirmed Fraud)", String.valueOf(counts[1])),
                List.of("False Positive (Cleared)", String.valueOf(counts[2])),
                List.of("Auto-Accepted", String.valueOf(counts[3]))
        );
        int total = counts[0] + counts[1] + counts[2] + counts[3];

        return ChatResponse.builder()
                .summary(String.format("Review queue has %d items total; %d pending review", total, counts[0]))
                .isTabular(true)
                .queryType(intent.getQueryType())
                .columns(List.of("STATUS", "COUNT"))
                .rows(rows)
                .build();
    }

    private ChatResponse handleSilentClients(ChatIntent intent) {
        ChatIntent.ChatFilters f = filters(intent);
        long[] range = timeRange(f);

        List<Transaction> activeTxns = transactionRepository.findByTimeRange(
                range[0], range[1], null, MAX_SCAN_RESULTS);
        Set<String> activeClients = activeTxns.stream()
                .map(Transaction::getClientId)
                .collect(Collectors.toSet());

        List<ClientProfile> allProfiles = clientProfileRepository.scanAllProfiles();
        List<ClientProfile> silentProfiles = allProfiles.stream()
                .filter(p -> !activeClients.contains(p.getClientId()))
                .collect(Collectors.toList());

        int limit = Math.min(intent.getLimit() != null ? intent.getLimit() : 100, MAX_TABLE_ROWS);
        List<List<String>> rows = silentProfiles.stream()
                .limit(limit)
                .map(p -> List.of(
                        p.getClientId(),
                        String.valueOf(p.getTotalTxnCount()),
                        Instant.ofEpochMilli(p.getLastUpdated()).toString()
                ))
                .collect(Collectors.toList());

        String timeDesc = f.getTimeRangeMinutes() != null
                ? "in the last " + f.getTimeRangeMinutes() + " minutes"
                : "recently";

        return ChatResponse.builder()
                .summary(String.format("Found %d client%s with no transactions %s (out of %d total)",
                        silentProfiles.size(), silentProfiles.size() == 1 ? "" : "s",
                        timeDesc, allProfiles.size()))
                .isTabular(true)
                .queryType(intent.getQueryType())
                .columns(List.of("CLIENT ID", "TOTAL TXN COUNT", "LAST ACTIVE"))
                .rows(rows)
                .build();
    }

    // ── Helpers ──

    private ChatIntent.ChatFilters filters(ChatIntent intent) {
        return intent.getFilters() != null ? intent.getFilters() : new ChatIntent.ChatFilters();
    }

    private boolean hasTimeFilter(ChatIntent.ChatFilters f) {
        return f.getTimeRangeMinutes() != null && f.getTimeRangeMinutes() > 0;
    }

    private long[] timeRange(ChatIntent.ChatFilters f) {
        long now = System.currentTimeMillis();
        if (f.getTimeRangeMinutes() != null && f.getTimeRangeMinutes() > 0) {
            return new long[]{ now - (long) f.getTimeRangeMinutes() * 60_000L, now };
        }
        return new long[]{ now - 24L * 60 * 60_000L, now };
    }

    private String buildDescriptor(ChatIntent.ChatFilters f) {
        StringBuilder sb = new StringBuilder();
        if (f.getTxnType() != null) sb.append(" for ").append(f.getTxnType());
        if (f.getTimeRangeMinutes() != null) {
            sb.append(" in the last ");
            int mins = f.getTimeRangeMinutes();
            if (mins >= 60 && mins % 60 == 0) sb.append(mins / 60).append(" hour(s)");
            else sb.append(mins).append(" minute(s)");
        }
        if (f.getRiskLevel() != null) sb.append(" with risk level ").append(f.getRiskLevel());
        if (f.getAction() != null) sb.append(" with action ").append(f.getAction());
        return sb.toString();
    }

    private ChatResponse errorResponse(String message) {
        return ChatResponse.builder()
                .summary(message)
                .errorMessage(message)
                .isTabular(false)
                .queryType(null)
                .columns(Collections.emptyList())
                .rows(Collections.emptyList())
                .build();
    }
}
