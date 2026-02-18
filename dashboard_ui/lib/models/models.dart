class Transaction {
  final String txnId;
  final String clientId;
  final String txnType;
  final double amount;
  final int timestamp;

  Transaction({
    required this.txnId,
    required this.clientId,
    required this.txnType,
    required this.amount,
    required this.timestamp,
  });

  factory Transaction.fromJson(Map<String, dynamic> json) {
    return Transaction(
      txnId: json['txnId'] ?? '',
      clientId: json['clientId'] ?? '',
      txnType: json['txnType'] ?? '',
      amount: (json['amount'] ?? 0).toDouble(),
      timestamp: (json['timestamp'] ?? 0).toInt(),
    );
  }
}

class ClientProfile {
  final String clientId;
  final int totalTxnCount;
  final double ewmaAmount;
  final double amountStdDev;
  final double ewmaHourlyTps;
  final double tpsStdDev;
  final int lastUpdated;
  final Map<String, int> txnTypeCounts;
  final Map<String, double> avgAmountByType;

  ClientProfile({
    required this.clientId,
    required this.totalTxnCount,
    required this.ewmaAmount,
    required this.amountStdDev,
    required this.ewmaHourlyTps,
    required this.tpsStdDev,
    required this.lastUpdated,
    required this.txnTypeCounts,
    required this.avgAmountByType,
  });

  factory ClientProfile.fromJson(Map<String, dynamic> json) {
    return ClientProfile(
      clientId: json['clientId'] ?? '',
      totalTxnCount: (json['totalTxnCount'] ?? 0).toInt(),
      ewmaAmount: (json['ewmaAmount'] ?? 0).toDouble(),
      amountStdDev: (json['amountStdDev'] ?? 0).toDouble(),
      ewmaHourlyTps: (json['ewmaHourlyTps'] ?? 0).toDouble(),
      tpsStdDev: (json['tpsStdDev'] ?? 0).toDouble(),
      lastUpdated: (json['lastUpdated'] ?? 0).toInt(),
      txnTypeCounts: (json['txnTypeCounts'] as Map<String, dynamic>? ?? {})
          .map((k, v) => MapEntry(k, (v as num).toInt())),
      avgAmountByType:
          (json['avgAmountByType'] as Map<String, dynamic>? ?? {})
              .map((k, v) => MapEntry(k, (v as num).toDouble())),
    );
  }
}

class RuleResult {
  final String ruleId;
  final String ruleName;
  final String ruleType;
  final bool triggered;
  final double deviationPct;
  final double partialScore;
  final double riskWeight;
  final String reason;

  RuleResult({
    required this.ruleId,
    required this.ruleName,
    required this.ruleType,
    required this.triggered,
    required this.deviationPct,
    required this.partialScore,
    required this.riskWeight,
    required this.reason,
  });

  factory RuleResult.fromJson(Map<String, dynamic> json) {
    return RuleResult(
      ruleId: json['ruleId'] ?? '',
      ruleName: json['ruleName'] ?? '',
      ruleType: json['ruleType'] ?? '',
      triggered: json['triggered'] ?? false,
      deviationPct: (json['deviationPct'] ?? 0).toDouble(),
      partialScore: (json['partialScore'] ?? 0).toDouble(),
      riskWeight: (json['riskWeight'] ?? 0).toDouble(),
      reason: json['reason'] ?? '',
    );
  }
}

class EvaluationResult {
  final String txnId;
  final String clientId;
  final double compositeScore;
  final String riskLevel;
  final String action;
  final int evaluatedAt;
  final List<RuleResult> ruleResults;

  EvaluationResult({
    required this.txnId,
    required this.clientId,
    required this.compositeScore,
    required this.riskLevel,
    required this.action,
    required this.evaluatedAt,
    required this.ruleResults,
  });

  factory EvaluationResult.fromJson(Map<String, dynamic> json) {
    return EvaluationResult(
      txnId: json['txnId'] ?? '',
      clientId: json['clientId'] ?? '',
      compositeScore: (json['compositeScore'] ?? 0).toDouble(),
      riskLevel: json['riskLevel'] ?? 'LOW',
      action: json['action'] ?? 'PASS',
      evaluatedAt: (json['evaluatedAt'] ?? 0).toInt(),
      ruleResults: (json['ruleResults'] as List<dynamic>? ?? [])
          .map((r) => RuleResult.fromJson(r as Map<String, dynamic>))
          .toList(),
    );
  }
}
