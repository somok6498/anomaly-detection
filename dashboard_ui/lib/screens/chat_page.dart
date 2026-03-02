import 'package:flutter/material.dart';
import '../theme/app_theme.dart';
import '../services/api_service.dart';
import '../services/export_service.dart';
import '../models/models.dart';

class ChatPage extends StatefulWidget {
  const ChatPage({super.key});

  @override
  State<ChatPage> createState() => _ChatPageState();
}

class _ChatPageState extends State<ChatPage> {
  final _controller = TextEditingController();
  final _scrollController = ScrollController();
  final _api = ApiService();
  final List<ChatMessage> _messages = [];
  bool _loading = false;

  static const _suggestions = [
    'How many clients did UPI in last 15 mins?',
    'List all anomaly rules',
    'Show review queue stats',
    'How many clients are not transacting in last 1 hr?',
    'List transactions blocked in last 30 mins',
    'How many rules are there?',
  ];

  @override
  void dispose() {
    _controller.dispose();
    _scrollController.dispose();
    super.dispose();
  }

  Future<void> _sendMessage([String? overrideText]) async {
    final text = (overrideText ?? _controller.text).trim();
    if (text.isEmpty || _loading) return;

    _controller.clear();
    setState(() {
      _messages.add(ChatMessage(
        role: 'user',
        text: text,
        timestamp: DateTime.now(),
      ));
      _loading = true;
    });
    _scrollToBottom();

    try {
      final result = await _api.sendChatMessage(text);
      setState(() {
        _messages.add(ChatMessage(
          role: 'assistant',
          text: result.summary,
          timestamp: DateTime.now(),
          result: result,
        ));
        _loading = false;
      });
    } catch (e) {
      setState(() {
        _messages.add(ChatMessage(
          role: 'assistant',
          text: 'Failed to get a response. Please check that the server is running.',
          timestamp: DateTime.now(),
        ));
        _loading = false;
      });
    }
    _scrollToBottom();
  }

  void _scrollToBottom() {
    Future.delayed(const Duration(milliseconds: 100), () {
      if (_scrollController.hasClients) {
        _scrollController.animateTo(
          _scrollController.position.maxScrollExtent,
          duration: const Duration(milliseconds: 300),
          curve: Curves.easeOut,
        );
      }
    });
  }

  void _exportResultCsv(ChatResult result, int index) {
    final rows = <List<String>>[
      result.columns,
      ...result.rows,
    ];
    ExportService.downloadCsv('chat_result_$index.csv', rows);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppTheme.bg,
      appBar: AppBar(
        backgroundColor: AppTheme.cardBg,
        leading: IconButton(
          icon: Icon(Icons.arrow_back, color: AppTheme.textPrimary),
          onPressed: () => Navigator.pop(context),
        ),
        title: Row(
          children: [
            Icon(Icons.smart_toy, color: AppTheme.accent, size: 22),
            const SizedBox(width: 10),
            Text('AI Assistant',
                style: TextStyle(color: AppTheme.textPrimary, fontSize: 18, fontWeight: FontWeight.w600)),
          ],
        ),
        elevation: 0,
        bottom: PreferredSize(
          preferredSize: const Size.fromHeight(1),
          child: Container(height: 1, color: AppTheme.cardBorder),
        ),
      ),
      body: Column(
        children: [
          Expanded(
            child: _messages.isEmpty ? _buildEmptyState() : _buildMessageList(),
          ),
          _buildInputArea(),
        ],
      ),
    );
  }

  Widget _buildEmptyState() {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.smart_toy, size: 64, color: AppTheme.accent.withValues(alpha: 0.3)),
            const SizedBox(height: 16),
            Text('Ask me anything about your data',
                style: TextStyle(color: AppTheme.textPrimary, fontSize: 18, fontWeight: FontWeight.w500)),
            const SizedBox(height: 8),
            Text('I can count clients, list transactions, show rules, review stats, and more.',
                textAlign: TextAlign.center,
                style: TextStyle(color: AppTheme.textSecondary, fontSize: 14)),
            const SizedBox(height: 28),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              alignment: WrapAlignment.center,
              children: _suggestions
                  .map((s) => _SuggestionChip(text: s, onTap: () => _sendMessage(s)))
                  .toList(),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildMessageList() {
    return ListView.builder(
      controller: _scrollController,
      padding: const EdgeInsets.all(16),
      itemCount: _messages.length + (_loading ? 1 : 0),
      itemBuilder: (context, index) {
        if (index == _messages.length) {
          return _buildTypingIndicator();
        }
        return _buildMessageBubble(_messages[index], index);
      },
    );
  }

  Widget _buildTypingIndicator() {
    return Align(
      alignment: Alignment.centerLeft,
      child: Container(
        margin: const EdgeInsets.symmetric(vertical: 6),
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
        decoration: BoxDecoration(
          color: AppTheme.cardBg,
          border: Border.all(color: AppTheme.cardBorder),
          borderRadius: BorderRadius.circular(12),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            SizedBox(width: 40, child: _ThinkingDots()),
            const SizedBox(width: 8),
            Text('Thinking...', style: TextStyle(color: AppTheme.textSecondary, fontSize: 13)),
          ],
        ),
      ),
    );
  }

  Widget _buildMessageBubble(ChatMessage msg, int index) {
    final isUser = msg.role == 'user';

    return Align(
      alignment: isUser ? Alignment.centerRight : Alignment.centerLeft,
      child: Container(
        margin: const EdgeInsets.symmetric(vertical: 6),
        constraints: BoxConstraints(
          maxWidth: MediaQuery.of(context).size.width * 0.85,
        ),
        child: Column(
          crossAxisAlignment: isUser ? CrossAxisAlignment.end : CrossAxisAlignment.start,
          children: [
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
              decoration: BoxDecoration(
                color: isUser
                    ? AppTheme.accent.withValues(alpha: 0.15)
                    : AppTheme.cardBg,
                border: Border.all(
                  color: isUser
                      ? AppTheme.accent.withValues(alpha: 0.4)
                      : AppTheme.cardBorder,
                ),
                borderRadius: BorderRadius.circular(12),
              ),
              child: Text(
                msg.text,
                style: TextStyle(
                  color: msg.result?.errorMessage != null
                      ? AppTheme.critical
                      : AppTheme.textPrimary,
                  fontSize: 14,
                ),
              ),
            ),
            if (!isUser && msg.result != null && msg.result!.isTabular && msg.result!.rows.isNotEmpty) ...[
              const SizedBox(height: 8),
              _buildResultTable(msg.result!),
              const SizedBox(height: 6),
              Align(
                alignment: Alignment.centerRight,
                child: TextButton.icon(
                  onPressed: () => _exportResultCsv(msg.result!, index),
                  icon: Icon(Icons.download, size: 15, color: AppTheme.accent),
                  label: Text('Export CSV', style: TextStyle(color: AppTheme.accent, fontSize: 12)),
                  style: TextButton.styleFrom(
                    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                  ),
                ),
              ),
            ],
            const SizedBox(height: 2),
            Text(
              _formatTime(msg.timestamp),
              style: TextStyle(color: AppTheme.textSecondary, fontSize: 11),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildResultTable(ChatResult result) {
    return Container(
      decoration: BoxDecoration(
        border: Border.all(color: AppTheme.cardBorder),
        borderRadius: BorderRadius.circular(8),
      ),
      child: SingleChildScrollView(
        scrollDirection: Axis.horizontal,
        child: DataTable(
          headingRowColor: WidgetStateProperty.all(AppTheme.surface),
          dataRowMinHeight: 36,
          dataRowMaxHeight: 48,
          headingRowHeight: 40,
          columnSpacing: 20,
          columns: result.columns
              .map((c) => DataColumn(
                    label: Text(c,
                        style: TextStyle(
                            color: AppTheme.textSecondary,
                            fontSize: 11,
                            fontWeight: FontWeight.w600,
                            letterSpacing: 0.5)),
                  ))
              .toList(),
          rows: result.rows
              .map((row) => DataRow(
                    cells: row
                        .map((cell) => DataCell(Text(cell,
                            style: TextStyle(color: AppTheme.textPrimary, fontSize: 13))))
                        .toList(),
                  ))
              .toList(),
        ),
      ),
    );
  }

  Widget _buildInputArea() {
    return Container(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 20),
      decoration: BoxDecoration(
        color: AppTheme.cardBg,
        border: Border(top: BorderSide(color: AppTheme.cardBorder)),
      ),
      child: Row(
        children: [
          Expanded(
            child: TextField(
              controller: _controller,
              style: TextStyle(color: AppTheme.textPrimary, fontSize: 14),
              decoration: InputDecoration(
                hintText: 'Ask a question about your data...',
                hintStyle: TextStyle(color: AppTheme.textSecondary, fontSize: 14),
                contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(24),
                  borderSide: BorderSide(color: AppTheme.cardBorder),
                ),
                enabledBorder: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(24),
                  borderSide: BorderSide(color: AppTheme.cardBorder),
                ),
                focusedBorder: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(24),
                  borderSide: BorderSide(color: AppTheme.accent),
                ),
                filled: true,
                fillColor: AppTheme.surface,
              ),
              onSubmitted: _loading ? null : (_) => _sendMessage(),
              textInputAction: TextInputAction.send,
              maxLines: 3,
              minLines: 1,
            ),
          ),
          const SizedBox(width: 10),
          AnimatedContainer(
            duration: const Duration(milliseconds: 200),
            decoration: BoxDecoration(
              color: _loading ? AppTheme.surface : AppTheme.accent,
              shape: BoxShape.circle,
            ),
            child: IconButton(
              onPressed: _loading ? null : () => _sendMessage(),
              icon: _loading
                  ? SizedBox(
                      width: 20,
                      height: 20,
                      child: CircularProgressIndicator(strokeWidth: 2, color: AppTheme.accent),
                    )
                  : const Icon(Icons.send_rounded, color: Colors.white),
              tooltip: 'Send',
            ),
          ),
        ],
      ),
    );
  }

  String _formatTime(DateTime dt) {
    return '${dt.hour.toString().padLeft(2, '0')}:'
        '${dt.minute.toString().padLeft(2, '0')}';
  }
}

class _SuggestionChip extends StatelessWidget {
  final String text;
  final VoidCallback onTap;

  const _SuggestionChip({required this.text, required this.onTap});

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(20),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
        decoration: BoxDecoration(
          border: Border.all(color: AppTheme.cardBorder),
          borderRadius: BorderRadius.circular(20),
          color: AppTheme.surface,
        ),
        child: Text(text, style: TextStyle(color: AppTheme.textSecondary, fontSize: 12)),
      ),
    );
  }
}

class _ThinkingDots extends StatefulWidget {
  @override
  State<_ThinkingDots> createState() => _ThinkingDotsState();
}

class _ThinkingDotsState extends State<_ThinkingDots> with SingleTickerProviderStateMixin {
  late AnimationController _ctrl;
  int _dotCount = 1;

  @override
  void initState() {
    super.initState();
    _ctrl = AnimationController(vsync: this, duration: const Duration(milliseconds: 600))
      ..addListener(() {
        final newCount = _ctrl.value > 0.66 ? 3 : (_ctrl.value > 0.33 ? 2 : 1);
        if (_dotCount != newCount) setState(() => _dotCount = newCount);
      })
      ..repeat();
  }

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Text('.' * _dotCount,
        style: TextStyle(color: AppTheme.accent, fontSize: 20, fontWeight: FontWeight.bold));
  }
}
