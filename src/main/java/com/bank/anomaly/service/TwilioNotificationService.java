package com.bank.anomaly.service;

import com.bank.anomaly.config.MetricsConfig;
import com.bank.anomaly.config.TwilioNotificationConfig;
import com.bank.anomaly.model.EvaluationResult;
import com.bank.anomaly.model.RuleResult;
import com.bank.anomaly.model.Transaction;
import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;
import io.micrometer.observation.annotation.Observed;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Comparator;

@Service
public class TwilioNotificationService {

    private static final Logger log = LoggerFactory.getLogger(TwilioNotificationService.class);

    private final TwilioNotificationConfig config;
    private final MetricsConfig metricsConfig;

    public TwilioNotificationService(TwilioNotificationConfig config, MetricsConfig metricsConfig) {
        this.config = config;
        this.metricsConfig = metricsConfig;
    }

    @PostConstruct
    public void init() {
        if (config.isEnabled()) {
            Twilio.init(config.getAccountSid(), config.getAuthToken());
            log.info("Twilio notification service initialized. Channel: {}", config.getChannel());
        } else {
            log.info("Twilio notification service is DISABLED.");
        }
    }

    @Async
    @Observed(name = "notification.send", contextualName = "send-notification")
    public void notifyIfBlocked(Transaction txn, EvaluationResult result) {
        if (!config.isEnabled() || !"BLOCK".equals(result.getAction())) {
            return;
        }

        try {
            String body = buildMessageBody(txn, result);
            String from = resolveNumber(config.getFromNumber());
            String to = resolveNumber(config.getToNumber());

            Message message = Message.creator(
                    new PhoneNumber(to),
                    new PhoneNumber(from),
                    body
            ).create();

            metricsConfig.recordNotification(config.getChannel(), "success");
            log.info("Twilio notification sent for txn={}, sid={}", txn.getTxnId(), message.getSid());
        } catch (Exception e) {
            metricsConfig.recordNotification(config.getChannel(), "error");
            log.error("Failed to send Twilio notification for txn={}: {}", txn.getTxnId(), e.getMessage(), e);
        }
    }

    private String buildMessageBody(Transaction txn, EvaluationResult result) {
        String topRule = result.getRuleResults().stream()
                .filter(RuleResult::isTriggered)
                .max(Comparator.comparingDouble(RuleResult::getPartialScore))
                .map(RuleResult::getRuleName)
                .orElse("N/A");

        return String.format(
                "[ANOMALY ALERT] Transaction BLOCKED\n" +
                "Client: %s\n" +
                "Txn ID: %s\n" +
                "Amount: %.2f\n" +
                "Risk Score: %.2f\n" +
                "Top Rule: %s",
                txn.getClientId(),
                txn.getTxnId(),
                txn.getAmount(),
                result.getCompositeScore(),
                topRule
        );
    }

    @Async
    public void notifySilentClient(String clientId, double silenceMinutes,
                                    double expectedGapMinutes, double ewmaHourlyTps) {
        if (!config.isEnabled()) {
            return;
        }

        try {
            String body = String.format(
                    "[SILENCE ALERT] Transaction flow stopped\n" +
                    "Client: %s\n" +
                    "Silent for: %.1f minutes\n" +
                    "Expected gap: %.1f minutes (%.1f txns/hr)\n" +
                    "Action: Investigate possible network/system issue",
                    clientId, silenceMinutes, expectedGapMinutes, ewmaHourlyTps);

            String from = resolveNumber(config.getFromNumber());
            String to = resolveNumber(config.getToNumber());

            Message message = Message.creator(
                    new PhoneNumber(to),
                    new PhoneNumber(from),
                    body
            ).create();

            metricsConfig.recordNotification(config.getChannel(), "success");
            log.info("Silence alert sent for client={}, sid={}", clientId, message.getSid());
        } catch (Exception e) {
            metricsConfig.recordNotification(config.getChannel(), "error");
            log.error("Failed to send silence alert for client={}: {}", clientId, e.getMessage(), e);
        }
    }

    private String resolveNumber(String number) {
        if ("whatsapp".equalsIgnoreCase(config.getChannel())) {
            return "whatsapp:" + number;
        }
        return number;
    }
}
