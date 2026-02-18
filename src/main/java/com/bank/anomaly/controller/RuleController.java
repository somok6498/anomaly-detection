package com.bank.anomaly.controller;

import com.bank.anomaly.model.AnomalyRule;
import com.bank.anomaly.service.RuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/rules")
@Tag(name = "Rules", description = "Manage anomaly detection rules (CRUD + enable/disable)")
public class RuleController {

    private final RuleService ruleService;

    public RuleController(RuleService ruleService) {
        this.ruleService = ruleService;
    }

    @Operation(summary = "List all anomaly rules",
            description = "Returns all configured anomaly detection rules including their thresholds, weights, and status.")
    @GetMapping
    public ResponseEntity<List<AnomalyRule>> listRules() {
        return ResponseEntity.ok(ruleService.getAllRules());
    }

    @Operation(summary = "Get a specific rule by ID")
    @GetMapping("/{ruleId}")
    public ResponseEntity<AnomalyRule> getRule(
            @Parameter(description = "Rule ID", example = "RULE-IF")
            @PathVariable String ruleId) {
        AnomalyRule rule = ruleService.getRule(ruleId);
        if (rule == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(rule);
    }

    @Operation(summary = "Create a new anomaly rule",
            description = "Creates a new rule. The rule will be auto-discovered by the engine if a matching evaluator exists.")
    @PostMapping
    public ResponseEntity<AnomalyRule> createRule(@RequestBody AnomalyRule rule) {
        if (rule.getName() == null || rule.getRuleType() == null) {
            return ResponseEntity.badRequest().build();
        }
        AnomalyRule created = ruleService.createRule(rule);
        return ResponseEntity.ok(created);
    }

    @Operation(summary = "Update an existing rule",
            description = "Modify variance thresholds, risk weights, enable/disable rules, or change parameters.")
    @PutMapping("/{ruleId}")
    public ResponseEntity<AnomalyRule> updateRule(
            @Parameter(description = "Rule ID", example = "RULE-IF")
            @PathVariable String ruleId,
            @RequestBody AnomalyRule updated) {
        AnomalyRule result = ruleService.updateRule(ruleId, updated);
        if (result == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "Delete a rule")
    @DeleteMapping("/{ruleId}")
    public ResponseEntity<Void> deleteRule(
            @Parameter(description = "Rule ID", example = "RULE-TXN-TYPE")
            @PathVariable String ruleId) {
        boolean deleted = ruleService.deleteRule(ruleId);
        if (!deleted) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.noContent().build();
    }
}
