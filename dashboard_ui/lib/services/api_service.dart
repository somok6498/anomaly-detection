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

  Future<List<Transaction>> getTransactionsByClient(String clientId,
      {int limit = 50}) async {
    final response = await http
        .get(Uri.parse('$baseUrl/transactions/client/$clientId?limit=$limit'));
    if (response.statusCode != 200) {
      throw Exception('Failed to load transactions: ${response.statusCode}');
    }
    final List<dynamic> data = jsonDecode(response.body);
    return data
        .map((j) => Transaction.fromJson(j as Map<String, dynamic>))
        .toList();
  }

  Future<List<EvaluationResult>> getEvalsByClient(String clientId,
      {int limit = 20}) async {
    final response = await http.get(
        Uri.parse('$baseUrl/transactions/results/client/$clientId?limit=$limit'));
    if (response.statusCode != 200) {
      throw Exception('Failed to load evaluations: ${response.statusCode}');
    }
    final List<dynamic> data = jsonDecode(response.body);
    return data
        .map((j) => EvaluationResult.fromJson(j as Map<String, dynamic>))
        .toList();
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
}
