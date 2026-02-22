package com.bank.anomaly.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "twilio")
public class TwilioNotificationConfig {

    private String accountSid;
    private String authToken;
    private String fromNumber;
    private String toNumber;
    private boolean enabled = false;
    private String channel = "sms";  // "sms" or "whatsapp"
}
