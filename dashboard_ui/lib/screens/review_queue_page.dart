import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import '../theme/app_theme.dart';
import '../services/api_service.dart';
import '../models/models.dart';
import '../widgets/badge_widget.dart';
import '../widgets/section_card.dart';
import '../widgets/stat_card.dart';

class ReviewQueuePage extends StatefulWidget {
  final ValueChanged<int>? onPendingCountChanged;

  const ReviewQueuePage({super.key, this.onPendingCountChanged});

  @override
  State<ReviewQueuePage> createState() => _ReviewQueuePageState();
}

class _ReviewQueuePageState extends State<ReviewQueuePage> {
  final _api = ApiService();
  final _clientFilterController = TextEditingController();

  // State
  bool _loading = true;
  String? _error;
  List<ReviewQueueItem> _queueItems = [];
  ReviewStats? _stats;

  // Filters
  String _actionFilter = 'ALL';
  String _statusFilter = 'ALL';

  // Sorting: null = no sort, 'action' or 'score'
  String? _sortColumn;
  bool _sortAscending = true;

  // Score threshold filter
  String _scoreOp = 'none'; // 'none', '>', '<'
  final _scoreThresholdController = TextEditingController();

  // Selection
  final Set<String> _selectedTxnIds = {};
  bool _selectAll = false;

  // Detail panel
  String? _selectedTxnId;
  ReviewQueueDetail? _selectedDetail;
  bool _loadingDetail = false;

  // Weight history
  List<RuleWeightChange> _weightHistory = [];

  @override
  void initState() {
    super.initState();
    _loadQueue();
  }

  @override
  void dispose() {
    _clientFilterController.dispose();
    _scoreThresholdController.dispose();
    super.dispose();
  }

  Future<void> _loadQueue() async {
    setState(() {
      _loading = true;
      _error = null;
    });

    try {
      final results = await Future.wait([
        _api.getReviewQueue(
          action: _actionFilter == 'ALL' ? null : _actionFilter,
          clientId: _clientFilterController.text.trim().isEmpty
              ? null
              : _clientFilterController.text.trim(),
        ),
        _api.getReviewStats(),
        _api.getWeightHistory(limit: 20),
      ]);

      setState(() {
        _queueItems = results[0] as List<ReviewQueueItem>;
        _stats = results[1] as ReviewStats;
        _weightHistory = results[2] as List<RuleWeightChange>;
        _loading = false;
        _selectedTxnIds.clear();
        _selectAll = false;
      });

      widget.onPendingCountChanged?.call(_stats?.pending ?? 0);
    } catch (e) {
      setState(() {
        _error = e.toString().replaceFirst('Exception: ', '');
        _loading = false;
      });
    }
  }

  List<ReviewQueueItem> get _filteredItems {
    var items = _queueItems.toList();

    // Status filter
    if (_statusFilter != 'ALL') {
      items = items.where((i) => i.feedbackStatus == _statusFilter).toList();
    }

    // Score threshold filter
    if (_scoreOp != 'none') {
      final threshold = double.tryParse(_scoreThresholdController.text.trim());
      if (threshold != null) {
        if (_scoreOp == '>') {
          items = items.where((i) => i.compositeScore > threshold).toList();
        } else if (_scoreOp == '<') {
          items = items.where((i) => i.compositeScore < threshold).toList();
        }
      }
    }

    // Sorting
    if (_sortColumn == 'score') {
      items.sort((a, b) => _sortAscending
          ? a.compositeScore.compareTo(b.compositeScore)
          : b.compositeScore.compareTo(a.compositeScore));
    } else if (_sortColumn == 'action') {
      items.sort((a, b) => _sortAscending
          ? a.action.compareTo(b.action)
          : b.action.compareTo(a.action));
    }

    return items;
  }

  Future<void> _selectItem(String txnId) async {
    setState(() {
      _selectedTxnId = txnId;
      _loadingDetail = true;
    });

    try {
      final detail = await _api.getReviewDetail(txnId);
      setState(() {
        _selectedDetail = detail;
        _loadingDetail = false;
      });
    } catch (e) {
      setState(() {
        _loadingDetail = false;
        _selectedDetail = null;
      });
    }
  }

  Future<void> _submitFeedback(String txnId, String status) async {
    try {
      await _api.submitFeedback(txnId, status, 'ops');
      _loadQueue();
      if (_selectedTxnId == txnId) {
        _selectItem(txnId);
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Failed to submit feedback: $e')),
        );
      }
    }
  }

  Future<void> _submitBulkFeedback(String status) async {
    if (_selectedTxnIds.isEmpty) return;

    try {
      final count = await _api.submitBulkFeedback(
          _selectedTxnIds.toList(), status, 'ops');
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Updated $count items as ${_statusLabel(status)}')),
        );
      }
      _loadQueue();
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Bulk update failed: $e')),
        );
      }
    }
  }

  String _statusLabel(String status) {
    switch (status) {
      case 'TRUE_POSITIVE': return 'True Positive';
      case 'FALSE_POSITIVE': return 'False Positive';
      default: return status;
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) {
      return const Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            CircularProgressIndicator(color: AppTheme.accent),
            SizedBox(height: 16),
            Text('Loading review queue...', style: TextStyle(color: AppTheme.textSecondary)),
          ],
        ),
      );
    }

    if (_error != null) {
      return Center(
        child: Container(
          padding: const EdgeInsets.all(16),
          decoration: BoxDecoration(
            color: const Color(0xFF3A1A1A),
            border: Border.all(color: const Color(0xFF6A2A2A)),
            borderRadius: BorderRadius.circular(8),
          ),
          child: Text(_error!, style: const TextStyle(color: AppTheme.critical)),
        ),
      );
    }

    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // Left panel — queue list (~40%)
        SizedBox(
          width: MediaQuery.of(context).size.width * 0.42,
          child: _buildLeftPanel(),
        ),
        // Divider
        Container(width: 1, color: AppTheme.cardBorder),
        // Right panel — detail (~60%)
        Expanded(child: _buildRightPanel()),
      ],
    );
  }

  Widget _buildLeftPanel() {
    final items = _filteredItems;

    return Column(
      children: [
        // Filter bar
        _buildFilterBar(),
        // Score threshold filter
        _buildScoreFilterBar(),
        // Stats row
        if (_stats != null) _buildStatsRow(),
        // Bulk action bar
        if (_selectedTxnIds.isNotEmpty) _buildBulkActionBar(),
        // Queue list
        Expanded(
          child: items.isEmpty
              ? const Center(
                  child: Text('No items in queue matching filters.',
                      style: TextStyle(color: AppTheme.textSecondary)),
                )
              : ListView.builder(
                  padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                  itemCount: items.length + 1, // +1 for header
                  itemBuilder: (context, index) {
                    if (index == 0) return _buildListHeader(items);
                    return _buildQueueRow(items[index - 1]);
                  },
                ),
        ),
      ],
    );
  }

  Widget _buildFilterBar() {
    return Container(
      color: AppTheme.cardBg,
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 12),
      child: Row(
        children: [
          // Action filter
          _buildDropdown(
            value: _actionFilter,
            items: ['ALL', 'ALERT', 'BLOCK'],
            onChanged: (v) {
              setState(() => _actionFilter = v!);
              _loadQueue();
            },
            width: 100,
          ),
          const SizedBox(width: 8),
          // Status filter
          _buildDropdown(
            value: _statusFilter,
            items: ['ALL', 'PENDING', 'TRUE_POSITIVE', 'FALSE_POSITIVE', 'AUTO_ACCEPTED'],
            labels: {'ALL': 'All Status', 'TRUE_POSITIVE': 'True +ve', 'FALSE_POSITIVE': 'False +ve', 'AUTO_ACCEPTED': 'Auto'},
            onChanged: (v) => setState(() => _statusFilter = v!),
            width: 110,
          ),
          const SizedBox(width: 8),
          // Client ID filter
          Expanded(
            child: SizedBox(
              height: 36,
              child: TextField(
                controller: _clientFilterController,
                style: const TextStyle(color: AppTheme.textPrimary, fontSize: 12),
                decoration: InputDecoration(
                  hintText: 'Client ID...',
                  hintStyle: const TextStyle(fontSize: 12),
                  contentPadding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
                  border: OutlineInputBorder(borderRadius: BorderRadius.circular(6)),
                ),
                onSubmitted: (_) => _loadQueue(),
              ),
            ),
          ),
          const SizedBox(width: 8),
          SizedBox(
            height: 36,
            child: ElevatedButton(
              onPressed: _loadQueue,
              style: ElevatedButton.styleFrom(
                backgroundColor: AppTheme.accent,
                foregroundColor: Colors.white,
                padding: const EdgeInsets.symmetric(horizontal: 12),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(6)),
              ),
              child: const Text('Apply', style: TextStyle(fontSize: 12)),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildDropdown({
    required String value,
    required List<String> items,
    Map<String, String>? labels,
    required ValueChanged<String?> onChanged,
    double width = 100,
  }) {
    return Container(
      height: 36,
      width: width,
      padding: const EdgeInsets.symmetric(horizontal: 8),
      decoration: BoxDecoration(
        color: AppTheme.surface,
        borderRadius: BorderRadius.circular(6),
        border: Border.all(color: AppTheme.cardBorder),
      ),
      child: DropdownButtonHideUnderline(
        child: DropdownButton<String>(
          value: value,
          isExpanded: true,
          dropdownColor: AppTheme.surface,
          style: const TextStyle(color: AppTheme.textPrimary, fontSize: 12),
          items: items
              .map((v) => DropdownMenuItem(
                    value: v,
                    child: Text(labels?[v] ?? v, style: const TextStyle(fontSize: 12)),
                  ))
              .toList(),
          onChanged: onChanged,
        ),
      ),
    );
  }

  Widget _buildScoreFilterBar() {
    return Container(
      color: AppTheme.cardBg,
      padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
      child: Row(
        children: [
          const Text('Score', style: TextStyle(color: AppTheme.textSecondary, fontSize: 11)),
          const SizedBox(width: 8),
          _buildDropdown(
            value: _scoreOp,
            items: ['none', '>', '<'],
            labels: {'none': 'Any', '>': '>', '<': '<'},
            onChanged: (v) => setState(() {
              _scoreOp = v!;
            }),
            width: 65,
          ),
          const SizedBox(width: 8),
          SizedBox(
            width: 80,
            height: 36,
            child: TextField(
              controller: _scoreThresholdController,
              enabled: _scoreOp != 'none',
              keyboardType: const TextInputType.numberWithOptions(decimal: true),
              style: const TextStyle(color: AppTheme.textPrimary, fontSize: 12),
              decoration: InputDecoration(
                hintText: 'e.g. 50',
                hintStyle: TextStyle(
                  fontSize: 11,
                  color: _scoreOp == 'none' ? AppTheme.textSecondary.withValues(alpha: 0.3) : AppTheme.textSecondary,
                ),
                contentPadding: const EdgeInsets.symmetric(horizontal: 8, vertical: 8),
                border: OutlineInputBorder(borderRadius: BorderRadius.circular(6)),
                disabledBorder: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(6),
                  borderSide: BorderSide(color: AppTheme.cardBorder.withValues(alpha: 0.3)),
                ),
              ),
              onSubmitted: (_) => setState(() {}),
            ),
          ),
          const SizedBox(width: 8),
          if (_scoreOp != 'none')
            GestureDetector(
              onTap: () => setState(() {
                _scoreOp = 'none';
                _scoreThresholdController.clear();
              }),
              child: const Icon(Icons.close, color: AppTheme.textSecondary, size: 16),
            ),
        ],
      ),
    );
  }

  Widget _buildStatsRow() {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 4),
      child: Row(
        children: [
          _buildMiniStat('Pending', _stats!.pending, AppTheme.textSecondary, 'PENDING'),
          _buildMiniStat('True +ve', _stats!.truePositive, AppTheme.critical, 'TRUE_POSITIVE'),
          _buildMiniStat('False +ve', _stats!.falsePositive, AppTheme.low, 'FALSE_POSITIVE'),
          _buildMiniStat('Auto', _stats!.autoAccepted, AppTheme.medium, 'AUTO_ACCEPTED'),
        ],
      ),
    );
  }

  Widget _buildMiniStat(String label, int value, Color color, String statusKey) {
    final isActive = _statusFilter == statusKey;
    return Expanded(
      child: GestureDetector(
        onTap: () {
          setState(() {
            // Toggle: if already active, clear filter; otherwise set it
            if (isActive) {
              _statusFilter = 'ALL';
            } else {
              _statusFilter = statusKey;
            }
            _actionFilter = 'ALL';
            _clientFilterController.clear();
          });
          _loadQueue();
        },
        child: Container(
          margin: const EdgeInsets.symmetric(horizontal: 3),
          padding: const EdgeInsets.symmetric(vertical: 8),
          decoration: BoxDecoration(
            color: isActive ? color.withValues(alpha: 0.15) : AppTheme.surface,
            borderRadius: BorderRadius.circular(6),
            border: Border.all(
              color: isActive ? color : Colors.transparent,
              width: 1.5,
            ),
          ),
          child: Column(
            children: [
              Text(
                value.toString(),
                style: TextStyle(color: color, fontSize: 18, fontWeight: FontWeight.w700),
              ),
              Text(label, style: const TextStyle(color: AppTheme.textSecondary, fontSize: 10)),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildBulkActionBar() {
    return Container(
      color: AppTheme.accent.withValues(alpha: 0.1),
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      child: Row(
        children: [
          Text(
            '${_selectedTxnIds.length} selected',
            style: const TextStyle(color: AppTheme.accent, fontSize: 13, fontWeight: FontWeight.w600),
          ),
          const Spacer(),
          SizedBox(
            height: 30,
            child: ElevatedButton(
              onPressed: () => _submitBulkFeedback('TRUE_POSITIVE'),
              style: ElevatedButton.styleFrom(
                backgroundColor: AppTheme.critical.withValues(alpha: 0.8),
                foregroundColor: Colors.white,
                padding: const EdgeInsets.symmetric(horizontal: 10),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(6)),
              ),
              child: const Text('True Positive', style: TextStyle(fontSize: 11)),
            ),
          ),
          const SizedBox(width: 8),
          SizedBox(
            height: 30,
            child: ElevatedButton(
              onPressed: () => _submitBulkFeedback('FALSE_POSITIVE'),
              style: ElevatedButton.styleFrom(
                backgroundColor: AppTheme.low.withValues(alpha: 0.8),
                foregroundColor: Colors.white,
                padding: const EdgeInsets.symmetric(horizontal: 10),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(6)),
              ),
              child: const Text('False Positive', style: TextStyle(fontSize: 11)),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildListHeader(List<ReviewQueueItem> items) {
    final pendingItems = items.where((i) => i.feedbackStatus == 'PENDING').toList();
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
      decoration: BoxDecoration(
        color: AppTheme.surface,
        borderRadius: BorderRadius.circular(6),
      ),
      child: Row(
        children: [
          SizedBox(
            width: 24,
            child: Checkbox(
              value: _selectAll,
              onChanged: (v) {
                setState(() {
                  _selectAll = v ?? false;
                  if (_selectAll) {
                    _selectedTxnIds.addAll(pendingItems.map((i) => i.txnId));
                  } else {
                    _selectedTxnIds.clear();
                  }
                });
              },
              visualDensity: VisualDensity.compact,
              activeColor: AppTheme.accent,
            ),
          ),
          const SizedBox(width: 4),
          const Expanded(
              flex: 3,
              child: Text('TXN ID', style: TextStyle(color: AppTheme.textSecondary, fontSize: 10, fontWeight: FontWeight.w700))),
          Expanded(
              flex: 1,
              child: _buildSortableHeader('ACTION', 'action')),
          Expanded(
              flex: 1,
              child: _buildSortableHeader('SCORE', 'score')),
          const Expanded(
              flex: 2,
              child: Text('STATUS', style: TextStyle(color: AppTheme.textSecondary, fontSize: 10, fontWeight: FontWeight.w700))),
        ],
      ),
    );
  }

  Widget _buildSortableHeader(String label, String column) {
    final isActive = _sortColumn == column;
    return GestureDetector(
      onTap: () {
        setState(() {
          if (_sortColumn == column) {
            if (_sortAscending) {
              _sortAscending = false;
            } else {
              // Third click: clear sort
              _sortColumn = null;
              _sortAscending = true;
            }
          } else {
            _sortColumn = column;
            _sortAscending = false; // default: high to low for score, Z-A for action
          }
        });
      },
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(label, style: TextStyle(
            color: isActive ? AppTheme.accent : AppTheme.textSecondary,
            fontSize: 10,
            fontWeight: FontWeight.w700,
          )),
          const SizedBox(width: 2),
          if (isActive)
            Icon(
              _sortAscending ? Icons.arrow_upward : Icons.arrow_downward,
              size: 10,
              color: AppTheme.accent,
            )
          else
            const Icon(Icons.unfold_more, size: 10, color: AppTheme.textSecondary),
        ],
      ),
    );
  }

  Widget _buildQueueRow(ReviewQueueItem item) {
    final isSelected = _selectedTxnId == item.txnId;
    final isPending = item.feedbackStatus == 'PENDING';
    final timeLeft = _timeRemaining(item);

    return InkWell(
      onTap: () => _selectItem(item.txnId),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 10),
        decoration: BoxDecoration(
          color: isSelected ? AppTheme.accent.withValues(alpha: 0.08) : Colors.transparent,
          border: Border(
            bottom: BorderSide(color: AppTheme.cardBorder.withValues(alpha: 0.3)),
            left: isSelected
                ? const BorderSide(color: AppTheme.accent, width: 3)
                : BorderSide.none,
          ),
        ),
        child: Row(
          children: [
            SizedBox(
              width: 24,
              child: isPending
                  ? Checkbox(
                      value: _selectedTxnIds.contains(item.txnId),
                      onChanged: (v) {
                        setState(() {
                          if (v == true) {
                            _selectedTxnIds.add(item.txnId);
                          } else {
                            _selectedTxnIds.remove(item.txnId);
                          }
                        });
                      },
                      visualDensity: VisualDensity.compact,
                      activeColor: AppTheme.accent,
                    )
                  : const SizedBox(),
            ),
            const SizedBox(width: 4),
            Expanded(
              flex: 3,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    item.txnId,
                    style: const TextStyle(color: AppTheme.textPrimary, fontSize: 11, fontWeight: FontWeight.w500),
                    overflow: TextOverflow.ellipsis,
                  ),
                  Text(
                    '${item.clientId}  ${_formatTime(item.enqueuedAt)}',
                    style: const TextStyle(color: AppTheme.textSecondary, fontSize: 9),
                  ),
                ],
              ),
            ),
            Expanded(flex: 1, child: ActionBadge(action: item.action)),
            Expanded(
              flex: 1,
              child: Text(
                item.compositeScore.toStringAsFixed(1),
                style: TextStyle(
                  color: AppTheme.riskColor(item.riskLevel),
                  fontSize: 12,
                  fontWeight: FontWeight.w700,
                ),
              ),
            ),
            Expanded(
              flex: 2,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  FeedbackBadge(status: item.feedbackStatus),
                  if (isPending && timeLeft != null)
                    Padding(
                      padding: const EdgeInsets.only(top: 2),
                      child: Text(timeLeft,
                          style: const TextStyle(color: AppTheme.textSecondary, fontSize: 9)),
                    ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  String? _timeRemaining(ReviewQueueItem item) {
    if (item.feedbackStatus != 'PENDING') return null;
    final remaining = item.autoAcceptDeadline - DateTime.now().millisecondsSinceEpoch;
    if (remaining <= 0) return 'Expiring...';
    final mins = (remaining / 60000).round();
    if (mins >= 60) return '${(mins / 60).floor()}h ${mins % 60}m left';
    return '${mins}m left';
  }

  Widget _buildRightPanel() {
    if (_selectedTxnId == null) {
      return const Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(Icons.touch_app, color: AppTheme.textSecondary, size: 48),
            SizedBox(height: 16),
            Text('Select a queue item to view details',
                style: TextStyle(color: AppTheme.textSecondary, fontSize: 15)),
          ],
        ),
      );
    }

    if (_loadingDetail) {
      return const Center(child: CircularProgressIndicator(color: AppTheme.accent));
    }

    if (_selectedDetail == null) {
      return const Center(
        child: Text('Failed to load details.', style: TextStyle(color: AppTheme.critical)),
      );
    }

    final detail = _selectedDetail!;
    final qi = detail.queueItem;
    final eval = detail.evaluation;
    final txn = detail.transaction;
    final profile = detail.clientProfile;

    return SingleChildScrollView(
      padding: const EdgeInsets.all(20),
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 900),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Feedback action buttons
            _buildFeedbackActions(qi),
            const SizedBox(height: 16),
            // Transaction info
            if (txn != null) _buildTxnCard(txn, qi),
            const SizedBox(height: 16),
            // Risk score + evaluation
            if (eval != null) _buildEvalCard(eval),
            const SizedBox(height: 16),
            // Client profile summary
            if (profile != null) _buildProfileSummary(profile),
            const SizedBox(height: 16),
            // Weight history
            if (_weightHistory.isNotEmpty) _buildWeightHistoryCard(),
          ],
        ),
      ),
    );
  }

  Widget _buildFeedbackActions(ReviewQueueItem qi) {
    final isPending = qi.feedbackStatus == 'PENDING';

    return SectionCard(
      title: 'Feedback',
      child: Row(
        children: [
          FeedbackBadge(status: qi.feedbackStatus),
          if (qi.feedbackBy != null) ...[
            const SizedBox(width: 12),
            Text('by ${qi.feedbackBy}',
                style: const TextStyle(color: AppTheme.textSecondary, fontSize: 12)),
          ],
          if (qi.feedbackAt > 0) ...[
            const SizedBox(width: 12),
            Text('at ${_formatTimeFull(qi.feedbackAt)}',
                style: const TextStyle(color: AppTheme.textSecondary, fontSize: 12)),
          ],
          const Spacer(),
          if (isPending) ...[
            ElevatedButton.icon(
              onPressed: () => _submitFeedback(qi.txnId, 'TRUE_POSITIVE'),
              icon: const Icon(Icons.warning_amber, size: 16),
              label: const Text('True Positive'),
              style: ElevatedButton.styleFrom(
                backgroundColor: AppTheme.critical,
                foregroundColor: Colors.white,
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
              ),
            ),
            const SizedBox(width: 8),
            ElevatedButton.icon(
              onPressed: () => _submitFeedback(qi.txnId, 'FALSE_POSITIVE'),
              icon: const Icon(Icons.check_circle_outline, size: 16),
              label: const Text('False Positive'),
              style: ElevatedButton.styleFrom(
                backgroundColor: AppTheme.low,
                foregroundColor: Colors.white,
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
              ),
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildTxnCard(Transaction txn, ReviewQueueItem qi) {
    return SectionCard(
      title: 'Transaction Details',
      child: Row(
        children: [
          Expanded(child: StatCard(label: 'Txn ID', value: txn.txnId)),
          Expanded(child: StatCard(label: 'Client', value: txn.clientId)),
          Expanded(child: StatCard(label: 'Type', value: txn.txnType)),
          Expanded(child: StatCard(label: 'Amount', value: _formatAmount(txn.amount))),
          Expanded(child: StatCard(label: 'Time', value: _formatTimeFull(txn.timestamp))),
        ],
      ),
    );
  }

  Widget _buildEvalCard(EvaluationResult eval) {
    return SectionCard(
      title: 'Risk Evaluation',
      child: Column(
        children: [
          // Score and badges
          Row(
            children: [
              // Score circle
              Container(
                width: 80,
                height: 80,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  border: Border.all(
                    color: AppTheme.riskColor(eval.riskLevel),
                    width: 4,
                  ),
                ),
                child: Center(
                  child: Text(
                    eval.compositeScore.toStringAsFixed(1),
                    style: TextStyle(
                      color: AppTheme.riskColor(eval.riskLevel),
                      fontSize: 22,
                      fontWeight: FontWeight.w800,
                    ),
                  ),
                ),
              ),
              const SizedBox(width: 16),
              Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  RiskBadge(level: eval.riskLevel),
                  const SizedBox(height: 4),
                  ActionBadge(action: eval.action),
                ],
              ),
              const Spacer(),
              Text(
                '${eval.ruleResults.where((r) => r.triggered).length} of ${eval.ruleResults.length} rules triggered',
                style: const TextStyle(color: AppTheme.textSecondary, fontSize: 13),
              ),
            ],
          ),
          const SizedBox(height: 16),
          // Rule results table
          SizedBox(
            width: double.infinity,
            child: DataTable(
              columnSpacing: 16,
              headingRowHeight: 36,
              dataRowMinHeight: 32,
              dataRowMaxHeight: 48,
              columns: const [
                DataColumn(label: Text('RULE', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w700))),
                DataColumn(label: Text('TRIGGERED', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w700))),
                DataColumn(label: Text('DEVIATION', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w700)), numeric: true),
                DataColumn(label: Text('SCORE', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w700)), numeric: true),
                DataColumn(label: Text('WEIGHT', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w700)), numeric: true),
              ],
              rows: eval.ruleResults.map((r) {
                return DataRow(cells: [
                  DataCell(Text(r.ruleName, style: const TextStyle(fontSize: 11))),
                  DataCell(Text(
                    r.triggered ? 'YES' : 'NO',
                    style: TextStyle(
                      color: r.triggered ? AppTheme.critical : AppTheme.low,
                      fontSize: 11,
                      fontWeight: FontWeight.w700,
                    ),
                  )),
                  DataCell(Text('${r.deviationPct.toStringAsFixed(1)}%',
                      style: const TextStyle(fontSize: 11))),
                  DataCell(Text(r.partialScore.toStringAsFixed(1),
                      style: const TextStyle(fontSize: 11))),
                  DataCell(Text(r.riskWeight.toStringAsFixed(1),
                      style: const TextStyle(fontSize: 11))),
                ]);
              }).toList(),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildProfileSummary(ClientProfile profile) {
    return SectionCard(
      title: 'Client Profile — ${profile.clientId}',
      child: Row(
        children: [
          Expanded(child: StatCard(label: 'Total Txns', value: _formatNum(profile.totalTxnCount))),
          Expanded(child: StatCard(label: 'EWMA Amount', value: _formatAmount(profile.ewmaAmount))),
          Expanded(child: StatCard(label: 'Std Dev', value: _formatAmount(profile.amountStdDev))),
          Expanded(child: StatCard(label: 'EWMA TPS/hr', value: profile.ewmaHourlyTps.toStringAsFixed(2))),
          Expanded(child: StatCard(label: 'Last Active', value: _formatTimeFull(profile.lastUpdated))),
        ],
      ),
    );
  }

  Widget _buildWeightHistoryCard() {
    return SectionCard(
      title: 'Recent Rule Weight Adjustments',
      child: SizedBox(
        width: double.infinity,
        child: DataTable(
          columnSpacing: 16,
          headingRowHeight: 36,
          dataRowMinHeight: 28,
          dataRowMaxHeight: 36,
          columns: const [
            DataColumn(label: Text('RULE', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w700))),
            DataColumn(label: Text('OLD', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w700)), numeric: true),
            DataColumn(label: Text('NEW', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w700)), numeric: true),
            DataColumn(label: Text('TP', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w700)), numeric: true),
            DataColumn(label: Text('FP', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w700)), numeric: true),
            DataColumn(label: Text('RATIO', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w700)), numeric: true),
            DataColumn(label: Text('DATE', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w700))),
          ],
          rows: _weightHistory.map((w) {
            final delta = w.newWeight - w.oldWeight;
            return DataRow(cells: [
              DataCell(Text(w.ruleId, style: const TextStyle(fontSize: 11))),
              DataCell(Text(w.oldWeight.toStringAsFixed(2), style: const TextStyle(fontSize: 11))),
              DataCell(Text(
                w.newWeight.toStringAsFixed(2),
                style: TextStyle(
                  fontSize: 11,
                  color: delta > 0 ? AppTheme.critical : AppTheme.low,
                  fontWeight: FontWeight.w700,
                ),
              )),
              DataCell(Text('${w.tpCount}', style: const TextStyle(fontSize: 11))),
              DataCell(Text('${w.fpCount}', style: const TextStyle(fontSize: 11))),
              DataCell(Text(w.tpFpRatio.toStringAsFixed(2), style: const TextStyle(fontSize: 11))),
              DataCell(Text(_formatTime(w.adjustedAt), style: const TextStyle(fontSize: 11))),
            ]);
          }).toList(),
        ),
      ),
    );
  }

  // ── Formatters ──

  String _formatAmount(double n) {
    return NumberFormat.currency(locale: 'en_IN', symbol: '\u20B9', decimalDigits: 2).format(n);
  }

  String _formatTime(int epoch) {
    if (epoch <= 0) return '-';
    return DateFormat('dd MMM, HH:mm').format(DateTime.fromMillisecondsSinceEpoch(epoch));
  }

  String _formatTimeFull(int epoch) {
    if (epoch <= 0) return '-';
    return DateFormat('dd MMM yyyy, HH:mm').format(DateTime.fromMillisecondsSinceEpoch(epoch));
  }

  String _formatNum(int n) {
    return NumberFormat('#,##,###', 'en_IN').format(n);
  }
}
