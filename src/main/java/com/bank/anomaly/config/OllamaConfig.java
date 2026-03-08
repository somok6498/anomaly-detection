package com.bank.anomaly.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "ollama")
public class OllamaConfig {
    private String host = "http://localhost:11434";
    private String model = "llama3.2:1b";
    private int timeoutSeconds = 300;
}
