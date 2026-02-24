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

class ReviewQueueItem {
  final String txnId;
  final String clientId;
  final String action;
  final double compositeScore;
  final String riskLevel;
  final List<String> triggeredRuleIds;
  final int enqueuedAt;
  final String feedbackStatus;
  final int feedbackAt;
  final String? feedbackBy;
  final int autoAcceptDeadline;

  ReviewQueueItem({
    required this.txnId,
    required this.clientId,
    required this.action,
    required this.compositeScore,
    required this.riskLevel,
    required this.triggeredRuleIds,
    required this.enqueuedAt,
    required this.feedbackStatus,
    required this.feedbackAt,
    this.feedbackBy,
    required this.autoAcceptDeadline,
  });

  factory ReviewQueueItem.fromJson(Map<String, dynamic> json) {
    return ReviewQueueItem(
      txnId: json['txnId'] ?? '',
      clientId: json['clientId'] ?? '',
      action: json['action'] ?? '',
      compositeScore: (json['compositeScore'] ?? 0).toDouble(),
      riskLevel: json['riskLevel'] ?? 'LOW',
      triggeredRuleIds: (json['triggeredRuleIds'] as List<dynamic>? ?? [])
          .map((e) => e.toString())
          .toList(),
      enqueuedAt: (json['enqueuedAt'] ?? 0).toInt(),
      feedbackStatus: json['feedbackStatus'] ?? 'PENDING',
      feedbackAt: (json['feedbackAt'] ?? 0).toInt(),
      feedbackBy: json['feedbackBy'],
      autoAcceptDeadline: (json['autoAcceptDeadline'] ?? 0).toInt(),
    );
  }
}

class ReviewQueueDetail {
  final ReviewQueueItem queueItem;
  final EvaluationResult? evaluation;
  final Transaction? transaction;
  final ClientProfile? clientProfile;

  ReviewQueueDetail({
    required this.queueItem,
    this.evaluation,
    this.transaction,
    this.clientProfile,
  });

  factory ReviewQueueDetail.fromJson(Map<String, dynamic> json) {
    return ReviewQueueDetail(
      queueItem: ReviewQueueItem.fromJson(json['queueItem'] as Map<String, dynamic>),
      evaluation: json['evaluation'] != null
          ? EvaluationResult.fromJson(json['evaluation'] as Map<String, dynamic>)
          : null,
      transaction: json['transaction'] != null
          ? Transaction.fromJson(json['transaction'] as Map<String, dynamic>)
          : null,
      clientProfile: json['clientProfile'] != null
          ? ClientProfile.fromJson(json['clientProfile'] as Map<String, dynamic>)
          : null,
    );
  }
}

class ReviewStats {
  final int pending;
  final int truePositive;
  final int falsePositive;
  final int autoAccepted;

  ReviewStats({
    required this.pending,
    required this.truePositive,
    required this.falsePositive,
    required this.autoAccepted,
  });

  factory ReviewStats.fromJson(Map<String, dynamic> json) {
    return ReviewStats(
      pending: (json['pending'] ?? 0).toInt(),
      truePositive: (json['truePositive'] ?? 0).toInt(),
      falsePositive: (json['falsePositive'] ?? 0).toInt(),
      autoAccepted: (json['autoAccepted'] ?? 0).toInt(),
    );
  }
}

class RuleWeightChange {
  final String ruleId;
  final double oldWeight;
  final double newWeight;
  final int tpCount;
  final int fpCount;
  final double tpFpRatio;
  final int adjustedAt;

  RuleWeightChange({
    required this.ruleId,
    required this.oldWeight,
    required this.newWeight,
    required this.tpCount,
    required this.fpCount,
    required this.tpFpRatio,
    required this.adjustedAt,
  });

  factory RuleWeightChange.fromJson(Map<String, dynamic> json) {
    return RuleWeightChange(
      ruleId: json['ruleId'] ?? '',
      oldWeight: (json['oldWeight'] ?? 0).toDouble(),
      newWeight: (json['newWeight'] ?? 0).toDouble(),
      tpCount: (json['tpCount'] ?? 0).toInt(),
      fpCount: (json['fpCount'] ?? 0).toInt(),
      tpFpRatio: (json['tpFpRatio'] ?? 0).toDouble(),
      adjustedAt: (json['adjustedAt'] ?? 0).toInt(),
    );
  }
}
