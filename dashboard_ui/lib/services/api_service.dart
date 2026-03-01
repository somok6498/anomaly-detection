import 'dart:convert';
import 'package:http/http.dart' as http;
import '../models/models.dart';

class ApiService {
  // When served from the same origin (Spring Boot static), use relative paths.
  // For local Flutter dev, point to the Spring Boot server.
  static const String baseUrl = 'http://localhost:8080/api/v1';

  Future<ClientProfile> getProfile(String clientId) async {
    final response = await http.get(Uri.parse('$baseUrl/profiles/$clientId'));
    if (response.statusCode == 404) {
      throw Exception('No profile found for client "$clientId"');
    }
    if (response.statusCode != 200) {
      throw Exception('Failed to load profile: ${response.statusCode}');
    }
    return ClientProfile.fromJson(jsonDecode(response.body));
  }

  Future<PagedResponse<Transaction>> getTransactionsByClient(String clientId,
      {int limit = 50, String? before}) async {
    final params = <String, String>{'limit': limit.toString()};
    if (before != null) params['before'] = before;
    final uri = Uri.parse('$baseUrl/transactions/client/$clientId')
        .replace(queryParameters: params);
    final response = await http.get(uri);
    if (response.statusCode != 200) {
      throw Exception('Failed to load transactions: ${response.statusCode}');
    }
    final json = jsonDecode(response.body) as Map<String, dynamic>;
    return PagedResponse<Transaction>(
      data: (json['data'] as List<dynamic>)
          .map((j) => Transaction.fromJson(j as Map<String, dynamic>))
          .toList(),
      hasMore: json['hasMore'] ?? false,
      nextCursor: json['nextCursor'],
    );
  }

  Future<PagedResponse<EvaluationResult>> getEvalsByClient(String clientId,
      {int limit = 200, String? before}) async {
    final params = <String, String>{'limit': limit.toString()};
    if (before != null) params['before'] = before;
    final uri = Uri.parse('$baseUrl/transactions/results/client/$clientId')
        .replace(queryParameters: params);
    final response = await http.get(uri);
    if (response.statusCode != 200) {
      throw Exception('Failed to load evaluations: ${response.statusCode}');
    }
    final json = jsonDecode(response.body) as Map<String, dynamic>;
    return PagedResponse<EvaluationResult>(
      data: (json['data'] as List<dynamic>)
          .map((j) => EvaluationResult.fromJson(j as Map<String, dynamic>))
          .toList(),
      hasMore: json['hasMore'] ?? false,
      nextCursor: json['nextCursor'],
    );
  }

  Future<Transaction> getTransaction(String txnId) async {
    final response =
        await http.get(Uri.parse('$baseUrl/transactions/$txnId'));
    if (response.statusCode == 404) {
      throw Exception('No transaction found with ID "$txnId"');
    }
    if (response.statusCode != 200) {
      throw Exception('Failed to load transaction: ${response.statusCode}');
    }
    return Transaction.fromJson(jsonDecode(response.body));
  }

  Future<EvaluationResult?> getEvalResult(String txnId) async {
    final response =
        await http.get(Uri.parse('$baseUrl/transactions/results/$txnId'));
    if (response.statusCode == 404) return null;
    if (response.statusCode != 200) {
      throw Exception('Failed to load eval result: ${response.statusCode}');
    }
    return EvaluationResult.fromJson(jsonDecode(response.body));
  }

  // ── Review Queue API ──

  Future<PagedResponse<ReviewQueueItem>> getReviewQueue({
    String? action,
    String? clientId,
    int? fromDate,
    int? toDate,
    String? ruleId,
    int limit = 100,
    String? before,
  }) async {
    final params = <String, String>{'limit': limit.toString()};
    if (action != null && action.isNotEmpty) params['action'] = action;
    if (clientId != null && clientId.isNotEmpty) params['clientId'] = clientId;
    if (fromDate != null) params['fromDate'] = fromDate.toString();
    if (toDate != null) params['toDate'] = toDate.toString();
    if (ruleId != null && ruleId.isNotEmpty) params['ruleId'] = ruleId;
    if (before != null) params['before'] = before;

    final uri = Uri.parse('$baseUrl/review/queue')
        .replace(queryParameters: params);
    final response = await http.get(uri);
    if (response.statusCode != 200) {
      throw Exception('Failed to load review queue: ${response.statusCode}');
    }
    final json = jsonDecode(response.body) as Map<String, dynamic>;
    return PagedResponse<ReviewQueueItem>(
      data: (json['data'] as List<dynamic>)
          .map((j) => ReviewQueueItem.fromJson(j as Map<String, dynamic>))
          .toList(),
      hasMore: json['hasMore'] ?? false,
      nextCursor: json['nextCursor'],
    );
  }

  Future<ReviewQueueDetail> getReviewDetail(String txnId) async {
    final response =
        await http.get(Uri.parse('$baseUrl/review/queue/$txnId'));
    if (response.statusCode == 404) {
      throw Exception('Queue item not found for "$txnId"');
    }
    if (response.statusCode != 200) {
      throw Exception('Failed to load review detail: ${response.statusCode}');
    }
    return ReviewQueueDetail.fromJson(jsonDecode(response.body));
  }

  Future<ReviewQueueItem> submitFeedback(
      String txnId, String status, String feedbackBy) async {
    final response = await http.post(
      Uri.parse('$baseUrl/review/queue/$txnId/feedback'),
      headers: {'Content-Type': 'application/json'},
      body: jsonEncode({'status': status, 'feedbackBy': feedbackBy}),
    );
    if (response.statusCode != 200) {
      throw Exception('Failed to submit feedback: ${response.statusCode}');
    }
    return ReviewQueueItem.fromJson(jsonDecode(response.body));
  }

  Future<int> submitBulkFeedback(
      List<String> txnIds, String status, String feedbackBy) async {
    final response = await http.post(
      Uri.parse('$baseUrl/review/queue/bulk-feedback'),
      headers: {'Content-Type': 'application/json'},
      body: jsonEncode({
        'txnIds': txnIds,
        'status': status,
        'feedbackBy': feedbackBy,
      }),
    );
    if (response.statusCode != 200) {
      throw Exception('Failed to submit bulk feedback: ${response.statusCode}');
    }
    final data = jsonDecode(response.body) as Map<String, dynamic>;
    return (data['updatedCount'] ?? 0) as int;
  }

  Future<ReviewStats> getReviewStats() async {
    final response = await http.get(Uri.parse('$baseUrl/review/stats'));
    if (response.statusCode != 200) {
      throw Exception('Failed to load review stats: ${response.statusCode}');
    }
    return ReviewStats.fromJson(jsonDecode(response.body));
  }

  Future<PagedResponse<RuleWeightChange>> getWeightHistory(
      {String? ruleId, int limit = 50, String? before}) async {
    final params = <String, String>{'limit': limit.toString()};
    if (ruleId != null && ruleId.isNotEmpty) params['ruleId'] = ruleId;
    if (before != null) params['before'] = before;

    final uri = Uri.parse('$baseUrl/review/weight-history')
        .replace(queryParameters: params);
    final response = await http.get(uri);
    if (response.statusCode != 200) {
      throw Exception(
          'Failed to load weight history: ${response.statusCode}');
    }
    final json = jsonDecode(response.body) as Map<String, dynamic>;
    return PagedResponse<RuleWeightChange>(
      data: (json['data'] as List<dynamic>)
          .map((j) => RuleWeightChange.fromJson(j as Map<String, dynamic>))
          .toList(),
      hasMore: json['hasMore'] ?? false,
      nextCursor: json['nextCursor'],
    );
  }

  // ── Analytics API ──

  Future<List<RulePerformance>> getRulePerformance() async {
    final response =
        await http.get(Uri.parse('$baseUrl/analytics/rules/performance'));
    if (response.statusCode != 200) {
      throw Exception(
          'Failed to load rule performance: ${response.statusCode}');
    }
    final List<dynamic> data = jsonDecode(response.body);
    return data
        .map((j) => RulePerformance.fromJson(j as Map<String, dynamic>))
        .toList();
  }

  Future<NetworkGraph> getClientNetwork(String clientId) async {
    final response = await http
        .get(Uri.parse('$baseUrl/analytics/graph/client/$clientId/network'));
    if (response.statusCode != 200) {
      throw Exception(
          'Failed to load client network: ${response.statusCode}');
    }
    return NetworkGraph.fromJson(jsonDecode(response.body));
  }

  Future<GraphStatus> getGraphStatus() async {
    final response = await http.get(Uri.parse('$baseUrl/graph/status'));
    if (response.statusCode != 200) {
      throw Exception('Failed to load graph status: ${response.statusCode}');
    }
    return GraphStatus.fromJson(jsonDecode(response.body));
  }

  // ── Config API ──

  Future<List<AnomalyRuleModel>> getRules() async {
    final response = await http.get(Uri.parse('$baseUrl/rules'));
    if (response.statusCode != 200) throw Exception('Failed to load rules');
    final list = jsonDecode(response.body) as List<dynamic>;
    return list.map((j) => AnomalyRuleModel.fromJson(j)).toList();
  }

  Future<AnomalyRuleModel> updateRule(String ruleId, AnomalyRuleModel rule) async {
    final response = await http.put(
      Uri.parse('$baseUrl/rules/$ruleId'),
      headers: {'Content-Type': 'application/json'},
      body: jsonEncode(rule.toJson()),
    );
    if (response.statusCode != 200) throw Exception('Failed to update rule: ${response.body}');
    return AnomalyRuleModel.fromJson(jsonDecode(response.body));
  }

  Future<ThresholdConfig> getThresholds() async {
    final response = await http.get(Uri.parse('$baseUrl/config/thresholds'));
    if (response.statusCode != 200) throw Exception('Failed to load thresholds');
    return ThresholdConfig.fromJson(jsonDecode(response.body));
  }

  Future<ThresholdConfig> updateThresholds(ThresholdConfig config) async {
    final response = await http.put(
      Uri.parse('$baseUrl/config/thresholds'),
      headers: {'Content-Type': 'application/json'},
      body: jsonEncode(config.toJson()),
    );
    if (response.statusCode != 200) {
      final err = jsonDecode(response.body);
      throw Exception(err['error'] ?? 'Failed to update thresholds');
    }
    return ThresholdConfig.fromJson(jsonDecode(response.body));
  }

  Future<FeedbackConfigModel> getFeedbackConfig() async {
    final response = await http.get(Uri.parse('$baseUrl/config/feedback'));
    if (response.statusCode != 200) throw Exception('Failed to load feedback config');
    return FeedbackConfigModel.fromJson(jsonDecode(response.body));
  }

  Future<FeedbackConfigModel> updateFeedbackConfig(FeedbackConfigModel config) async {
    final response = await http.put(
      Uri.parse('$baseUrl/config/feedback'),
      headers: {'Content-Type': 'application/json'},
      body: jsonEncode(config.toJson()),
    );
    if (response.statusCode != 200) {
      final err = jsonDecode(response.body);
      throw Exception(err['error'] ?? 'Failed to update feedback config');
    }
    return FeedbackConfigModel.fromJson(jsonDecode(response.body));
  }

  Future<List<String>> getTransactionTypes() async {
    final response = await http.get(Uri.parse('$baseUrl/config/transaction-types'));
    if (response.statusCode != 200) throw Exception('Failed to load transaction types');
    final json = jsonDecode(response.body) as Map<String, dynamic>;
    return (json['transactionTypes'] as List<dynamic>).cast<String>();
  }

  Future<List<String>> updateTransactionTypes(List<String> types) async {
    final response = await http.put(
      Uri.parse('$baseUrl/config/transaction-types'),
      headers: {'Content-Type': 'application/json'},
      body: jsonEncode({'transactionTypes': types}),
    );
    if (response.statusCode != 200) {
      final err = jsonDecode(response.body);
      throw Exception(err['error'] ?? 'Failed to update transaction types');
    }
    final json = jsonDecode(response.body) as Map<String, dynamic>;
    return (json['transactionTypes'] as List<dynamic>).cast<String>();
  }

  Future<AerospikeInfo> getAerospikeInfo() async {
    final response = await http.get(Uri.parse('$baseUrl/config/aerospike'));
    if (response.statusCode != 200) throw Exception('Failed to load Aerospike info');
    return AerospikeInfo.fromJson(jsonDecode(response.body));
  }
}
