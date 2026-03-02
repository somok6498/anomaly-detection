package com.bank.anomaly.controller;

import com.bank.anomaly.model.ChatIntent;
import com.bank.anomaly.model.ChatRequest;
import com.bank.anomaly.model.ChatResponse;
import com.bank.anomaly.service.ChatQueryService;
import com.bank.anomaly.service.KeywordIntentParser;
import com.bank.anomaly.service.OllamaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;

@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final KeywordIntentParser keywordParser;
    private final OllamaService ollamaService;
    private final ChatQueryService chatQueryService;

    public ChatController(KeywordIntentParser keywordParser,
                          OllamaService ollamaService,
                          ChatQueryService chatQueryService) {
        this.keywordParser = keywordParser;
        this.ollamaService = ollamaService;
        this.chatQueryService = chatQueryService;
    }

    @PostMapping
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request) {
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            return ResponseEntity.ok(errorResponse("Please enter a question."));
        }

        log.info("Chat query: {}", request.getMessage());

        try {
            // Try fast keyword-based parsing first
            ChatIntent intent = keywordParser.parse(request.getMessage());

            if (intent != null) {
                log.info("Keyword parser matched: queryType={}", intent.getQueryType());
            } else {
                // Fall back to Ollama LLM for complex queries
                log.info("Keyword parser could not match, trying Ollama...");
                try {
                    intent = ollamaService.parseIntent(request.getMessage());
                    log.info("Ollama parsed intent: queryType={}", intent.getQueryType());
                } catch (OllamaService.OllamaUnavailableException e) {
                    log.warn("Ollama unavailable: {}", e.getMessage());
                    return ResponseEntity.ok(errorResponse(
                            "I couldn't understand that question. Try something like:\n" +
                            "• How many clients did UPI in last 15 mins?\n" +
                            "• List all rules\n" +
                            "• Show review queue stats\n" +
                            "• Transactions blocked in last 30 mins"));
                } catch (OllamaService.IntentParseException e) {
                    return ResponseEntity.ok(errorResponse(e.getMessage()));
                }
            }

            ChatResponse response = chatQueryService.execute(intent);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Unexpected error in chat: {}", e.getMessage(), e);
            return ResponseEntity.ok(errorResponse(
                    "Something went wrong processing your question. Please try again."));
        }
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
