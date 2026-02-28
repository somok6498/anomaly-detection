import 'package:flutter/material.dart';
import 'theme/app_theme.dart';
import 'theme/theme_notifier.dart';
import 'services/api_service.dart';
import 'services/export_service.dart';
import 'models/models.dart';
import 'widgets/skeleton_loader.dart';
import 'screens/client_view.dart';
import 'screens/transaction_view.dart';
import 'screens/review_queue_page.dart';
import 'screens/analytics_page.dart';

final themeNotifier = ThemeNotifier();

void main() {
  runApp(const AnomalyDashboardApp());
}

class AnomalyDashboardApp extends StatelessWidget {
  const AnomalyDashboardApp({super.key});

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: themeNotifier,
      builder: (context, _) {
        AppTheme.isDark = themeNotifier.isDark;
        return MaterialApp(
          title: 'Anomaly Detection Dashboard',
          theme: AppTheme.lightTheme,
          darkTheme: AppTheme.darkTheme,
          themeMode: themeNotifier.mode,
          debugShowCheckedModeBanner: false,
          home: const DashboardPage(),
        );
      },
    );
  }
}

enum SearchType { client, txn }

class DashboardPage extends StatefulWidget {
  const DashboardPage({super.key});

  @override
  State<DashboardPage> createState() => _DashboardPageState();
}

class _DashboardPageState extends State<DashboardPage> {
  final _searchController = TextEditingController();
  final _api = ApiService();
  SearchType _searchType = SearchType.client;

  bool _loading = false;
  String? _error;

  // Client search results
  ClientProfile? _profile;
  List<Transaction> _transactions = [];
  List<EvaluationResult> _evaluations = [];
  bool _txnHasMore = false;
  String? _txnNextCursor;
  bool _loadingMore = false;

  // Transaction search results
  Transaction? _txnDetail;
  EvaluationResult? _evalDetail;

  bool _hasResults = false;

  // Tab navigation: 0 = Investigation, 1 = Review Queue, 2 = Analytics
  int _activeTab = 0;
  int _pendingCount = 0;

  // Analytics page key for accessing state
  final _analyticsKey = GlobalKey<AnalyticsPageState>();

  @override
  void initState() {
    super.initState();
    _loadPendingCount();
  }

  Future<void> _loadPendingCount() async {
    try {
      final stats = await _api.getReviewStats();
      if (mounted) {
        setState(() => _pendingCount = stats.pending);
      }
    } catch (_) {}
  }

  void _doSearch([String? overrideQuery, SearchType? overrideType]) {
    final query = (overrideQuery ?? _searchController.text.trim()).toUpperCase();
    final type = overrideType ?? _searchType;

    if (query.isEmpty) return;

    if (overrideQuery != null) {
      _searchController.text = query;
    }
    if (overrideType != null) {
      _searchType = type;
    }

    setState(() {
      _loading = true;
      _error = null;
      _hasResults = false;
    });

    if (type == SearchType.client) {
      _searchClient(query);
    } else {
      _searchTransaction(query);
    }
  }

  Future<void> _searchClient(String clientId) async {
    try {
      final results = await Future.wait([
        _api.getProfile(clientId),
        _api.getTransactionsByClient(clientId),
        _api.getEvalsByClient(clientId),
      ]);

      final txnPage = results[1] as PagedResponse<Transaction>;
      final evalPage = results[2] as PagedResponse<EvaluationResult>;

      setState(() {
        _profile = results[0] as ClientProfile;
        _transactions = txnPage.data;
        _evaluations = evalPage.data;
        _txnHasMore = txnPage.hasMore;
        _txnNextCursor = txnPage.nextCursor;
        _txnDetail = null;
        _evalDetail = null;
        _hasResults = true;
        _loading = false;
      });
    } catch (e) {
      setState(() {
        _error = e.toString().replaceFirst('Exception: ', '');
        _loading = false;
      });
    }
  }

  Future<void> _loadMoreTransactions() async {
    if (_loadingMore || !_txnHasMore || _profile == null) return;
    setState(() => _loadingMore = true);
    try {
      final page = await _api.getTransactionsByClient(
        _profile!.clientId,
        before: _txnNextCursor,
      );
      setState(() {
        _transactions = [..._transactions, ...page.data];
        _txnHasMore = page.hasMore;
        _txnNextCursor = page.nextCursor;
        _loadingMore = false;
      });
    } catch (e) {
      setState(() => _loadingMore = false);
    }
  }

  Future<void> _searchTransaction(String txnId) async {
    try {
      final txn = await _api.getTransaction(txnId);
      final eval = await _api.getEvalResult(txnId);

      setState(() {
        _txnDetail = txn;
        _evalDetail = eval;
        _profile = null;
        _transactions = [];
        _evaluations = [];
        _hasResults = true;
        _loading = false;
      });
    } catch (e) {
      setState(() {
        _error = e.toString().replaceFirst('Exception: ', '');
        _loading = false;
      });
    }
  }

  // ── Export helpers ──

  void _exportClientCsv() {
    if (_profile == null) return;
    final rows = <List<String>>[
      ['TXN ID', 'TYPE', 'AMOUNT', 'TIMESTAMP'],
      ...(_transactions.map((t) => [
        t.txnId, t.txnType, t.amount.toStringAsFixed(2),
        DateTime.fromMillisecondsSinceEpoch(t.timestamp).toIso8601String(),
      ])),
    ];
    ExportService.downloadCsv('${_profile!.clientId}_transactions.csv', rows);
  }

  void _exportClientPdf() {
    if (_profile == null) return;
    final rows = _transactions.map((t) => [
      t.txnId, t.txnType, t.amount.toStringAsFixed(2),
      DateTime.fromMillisecondsSinceEpoch(t.timestamp).toIso8601String(),
    ]).toList();
    ExportService.downloadPdf(
      'Client Report: ${_profile!.clientId}',
      ['TXN ID', 'TYPE', 'AMOUNT', 'TIMESTAMP'],
      rows,
      '${_profile!.clientId}_report.pdf',
    );
  }

  void _exportRulesCsv() {
    final stats = _analyticsKey.currentState?.ruleStats ?? [];
    if (stats.isEmpty) return;
    final rows = <List<String>>[
      ['RULE', 'TYPE', 'WEIGHT', 'TRIGGERS', 'TP', 'FP', 'PRECISION'],
      ...(stats.map((r) => [
        r.ruleName, r.ruleType, r.currentWeight.toStringAsFixed(1),
        r.triggerCount.toString(), r.tpCount.toString(), r.fpCount.toString(),
        r.triggerCount > 0 ? '${(r.precision * 100).toStringAsFixed(1)}%' : '-',
      ])),
    ];
    ExportService.downloadCsv('rule_performance.csv', rows);
  }

  void _exportRulesPdf() {
    final stats = _analyticsKey.currentState?.ruleStats ?? [];
    if (stats.isEmpty) return;
    final rows = stats.map((r) => [
      r.ruleName, r.ruleType, r.currentWeight.toStringAsFixed(1),
      r.triggerCount.toString(), r.tpCount.toString(), r.fpCount.toString(),
      r.triggerCount > 0 ? '${(r.precision * 100).toStringAsFixed(1)}%' : '-',
    ]).toList();
    ExportService.downloadPdf(
      'Rule Performance Report',
      ['RULE', 'TYPE', 'WEIGHT', 'TRIGGERS', 'TP', 'FP', 'PRECISION'],
      rows,
      'rule_performance.pdf',
    );
  }

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Column(
        children: [
          _buildHeader(),
          if (_activeTab == 0) _buildSearchBar(),
          Expanded(
            child: _activeTab == 0
                ? SingleChildScrollView(
                    padding: const EdgeInsets.all(24),
                    child: Center(
                      child: ConstrainedBox(
                        constraints: const BoxConstraints(maxWidth: 1400),
                        child: _buildContent(),
                      ),
                    ),
                  )
                : _activeTab == 1
                    ? ReviewQueuePage(
                        onPendingCountChanged: (count) {
                          setState(() => _pendingCount = count);
                        },
                      )
                    : AnalyticsPage(
                        key: _analyticsKey,
                        onExportRulesCsv: _exportRulesCsv,
                        onExportRulesPdf: _exportRulesPdf,
                      ),
          ),
        ],
      ),
    );
  }

  Widget _buildHeader() {
    return Container(
      color: AppTheme.cardBg,
      padding: const EdgeInsets.symmetric(horizontal: 32, vertical: 20),
      child: Row(
        children: [
          Icon(Icons.security, color: AppTheme.accent, size: 28),
          const SizedBox(width: 12),
          Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('Anomaly Detection Dashboard',
                  style: TextStyle(fontSize: 22, fontWeight: FontWeight.w600, color: AppTheme.textPrimary)),
              Text('Real-time behavioral anomaly detection for banking transactions',
                  style: TextStyle(fontSize: 13, color: AppTheme.textSecondary)),
            ],
          ),
          const Spacer(),
          // Tab buttons
          _buildTabButton(0, 'Investigation', Icons.search),
          const SizedBox(width: 8),
          _buildTabButton(1, 'Review Queue', Icons.rate_review, badge: _pendingCount),
          const SizedBox(width: 8),
          _buildTabButton(2, 'Analytics', Icons.analytics),
          const SizedBox(width: 16),
          IconButton(
            onPressed: () => setState(() => themeNotifier.toggle()),
            icon: Icon(
              themeNotifier.isDark ? Icons.light_mode : Icons.dark_mode,
              color: AppTheme.textSecondary,
              size: 20,
            ),
            tooltip: themeNotifier.isDark ? 'Switch to light mode' : 'Switch to dark mode',
          ),
        ],
      ),
    );
  }

  Widget _buildTabButton(int index, String label, IconData icon, {int badge = 0}) {
    final isActive = _activeTab == index;
    return InkWell(
      onTap: () {
        setState(() => _activeTab = index);
        if (index == 1) _loadPendingCount();
      },
      borderRadius: BorderRadius.circular(8),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
        decoration: BoxDecoration(
          color: isActive ? AppTheme.accent.withValues(alpha: 0.15) : Colors.transparent,
          borderRadius: BorderRadius.circular(8),
          border: Border.all(
            color: isActive ? AppTheme.accent : AppTheme.cardBorder,
          ),
        ),
        child: Row(
          children: [
            Icon(icon, size: 16, color: isActive ? AppTheme.accent : AppTheme.textSecondary),
            const SizedBox(width: 8),
            Text(
              label,
              style: TextStyle(
                color: isActive ? AppTheme.accent : AppTheme.textSecondary,
                fontWeight: isActive ? FontWeight.w600 : FontWeight.w400,
                fontSize: 13,
              ),
            ),
            if (badge > 0) ...[
              const SizedBox(width: 8),
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 2),
                decoration: BoxDecoration(
                  color: AppTheme.alert,
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Text(
                  badge.toString(),
                  style: const TextStyle(color: Colors.white, fontSize: 11, fontWeight: FontWeight.w700),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildSearchBar() {
    return Container(
      color: AppTheme.cardBg,
      padding: const EdgeInsets.fromLTRB(32, 0, 32, 20),
      child: Row(
        children: [
          // Search type dropdown
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 12),
            decoration: BoxDecoration(
              color: AppTheme.surface,
              borderRadius: BorderRadius.circular(8),
              border: Border.all(color: AppTheme.cardBorder),
            ),
            child: DropdownButtonHideUnderline(
              child: DropdownButton<SearchType>(
                value: _searchType,
                dropdownColor: AppTheme.surface,
                style: TextStyle(color: AppTheme.textPrimary, fontSize: 14),
                items: const [
                  DropdownMenuItem(value: SearchType.client, child: Text('Client ID')),
                  DropdownMenuItem(value: SearchType.txn, child: Text('Transaction ID')),
                ],
                onChanged: (v) {
                  setState(() {
                    _searchType = v!;
                    _searchController.clear();
                  });
                },
              ),
            ),
          ),
          const SizedBox(width: 12),

          // Search input
          Expanded(
            child: TextField(
              controller: _searchController,
              style: TextStyle(color: AppTheme.textPrimary, fontSize: 14),
              decoration: InputDecoration(
                hintText: _searchType == SearchType.client
                    ? 'Enter Client ID (e.g. CLIENT-001)...'
                    : 'Enter Transaction ID (e.g. CLIENT-001-TXN-000001)...',
                contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
              ),
              onSubmitted: (_) => _doSearch(),
            ),
          ),
          const SizedBox(width: 12),

          // Search button
          ElevatedButton(
            onPressed: _loading ? null : () => _doSearch(),
            style: ElevatedButton.styleFrom(
              backgroundColor: AppTheme.accent,
              foregroundColor: Colors.white,
              padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 14),
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
            ),
            child: const Text('Search', style: TextStyle(fontWeight: FontWeight.w600)),
          ),
        ],
      ),
    );
  }

  Widget _buildContent() {
    if (_loading) {
      return const InvestigationSkeleton();
    }

    if (_error != null) {
      return Container(
        margin: const EdgeInsets.only(top: 20),
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          color: const Color(0xFF3A1A1A),
          border: Border.all(color: const Color(0xFF6A2A2A)),
          borderRadius: BorderRadius.circular(8),
        ),
        child: Text(_error!, style: const TextStyle(color: AppTheme.critical)),
      );
    }

    if (!_hasResults) {
      return Padding(
        padding: const EdgeInsets.only(top: 80),
        child: Center(
          child: Text('Search for a client or transaction to get started.',
              style: TextStyle(color: AppTheme.textSecondary, fontSize: 15)),
        ),
      );
    }

    // Client view
    if (_profile != null) {
      return ClientView(
        profile: _profile!,
        transactions: _transactions,
        evaluations: _evaluations,
        onTxnTap: (txnId) => _doSearch(txnId, SearchType.txn),
        onExportCsv: _exportClientCsv,
        onExportPdf: _exportClientPdf,
        hasMoreTransactions: _txnHasMore,
        loadingMore: _loadingMore,
        onLoadMore: _loadMoreTransactions,
      );
    }

    // Transaction view
    if (_txnDetail != null) {
      return TransactionView(
        transaction: _txnDetail!,
        evaluation: _evalDetail,
        onClientTap: (clientId) => _doSearch(clientId, SearchType.client),
      );
    }

    return const SizedBox();
  }
}
