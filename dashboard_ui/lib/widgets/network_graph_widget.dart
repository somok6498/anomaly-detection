import 'dart:math';
import 'package:flutter/material.dart';
import '../models/models.dart';
import '../theme/app_theme.dart';

/// Radial hierarchical network graph for beneficiary relationships.
/// Center client → inner ring (other clients) → outer ring (beneficiaries).
class NetworkGraphWidget extends StatefulWidget {
  final NetworkGraph graph;

  const NetworkGraphWidget({super.key, required this.graph});

  @override
  State<NetworkGraphWidget> createState() => _NetworkGraphWidgetState();
}

class _NetworkGraphWidgetState extends State<NetworkGraphWidget> {
  String? _selectedNodeId;
  String? _hoveredNodeId;

  // Adjacency for highlighting connections
  late Map<String, Set<String>> _adjacency;
  late NetworkNode? _centerNode;
  late List<NetworkNode> _clientNodes;
  late List<NetworkNode> _beneficiaryNodes;

  @override
  void initState() {
    super.initState();
    _buildAdjacency();
    _categorizeNodes();
  }

  @override
  void didUpdateWidget(NetworkGraphWidget oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.graph != widget.graph) {
      _buildAdjacency();
      _categorizeNodes();
      _selectedNodeId = null;
      _hoveredNodeId = null;
    }
  }

  void _buildAdjacency() {
    _adjacency = {};
    for (final edge in widget.graph.edges) {
      _adjacency.putIfAbsent(edge.from, () => {}).add(edge.to);
      _adjacency.putIfAbsent(edge.to, () => {}).add(edge.from);
    }
  }

  void _categorizeNodes() {
    _centerNode = widget.graph.nodes.where((n) => n.isCenter).firstOrNull;
    _clientNodes = widget.graph.nodes
        .where((n) => n.type == 'CLIENT' && !n.isCenter)
        .toList();
    _beneficiaryNodes = widget.graph.nodes
        .where((n) => n.type == 'BENEFICIARY')
        .toList();
  }

  bool _isConnected(String nodeId) {
    final active = _selectedNodeId ?? _hoveredNodeId;
    if (active == null) return true;
    if (nodeId == active) return true;
    return _adjacency[active]?.contains(nodeId) ?? false;
  }

  bool _isEdgeHighlighted(NetworkEdge edge) {
    final active = _selectedNodeId ?? _hoveredNodeId;
    if (active == null) return false;
    return edge.from == active || edge.to == active;
  }

  Map<String, Offset> _computeRadialLayout(double width, double height) {
    final positions = <String, Offset>{};
    final centerX = width / 2;
    final centerY = height / 2;

    // Place center node
    if (_centerNode != null) {
      positions[_centerNode!.id] = Offset(centerX, centerY);
    }

    // Inner ring: other clients — closer to center
    final innerRadius = min(width, height) * 0.2;
    if (_clientNodes.isNotEmpty) {
      final angleStep = 2 * pi / _clientNodes.length;
      // Offset starting angle so labels don't overlap vertically
      final startAngle = -pi / 2;
      for (int i = 0; i < _clientNodes.length; i++) {
        final angle = startAngle + angleStep * i;
        positions[_clientNodes[i].id] = Offset(
          centerX + innerRadius * cos(angle),
          centerY + innerRadius * sin(angle),
        );
      }
    }

    // Outer ring: beneficiaries — spread across a larger radius
    // Group beneficiaries by which client(s) they connect to for better layout
    final outerRadius = min(width, height) * 0.42;
    if (_beneficiaryNodes.isNotEmpty) {
      // Sort beneficiaries: shared ones (high fan-in) first for prominence
      final sorted = List<NetworkNode>.from(_beneficiaryNodes)
        ..sort((a, b) => b.fanIn.compareTo(a.fanIn));

      final angleStep = 2 * pi / sorted.length;
      final startAngle = -pi / 2 + (angleStep / 2); // offset from client ring
      for (int i = 0; i < sorted.length; i++) {
        final angle = startAngle + angleStep * i;
        // Vary radius slightly for visual interest — shared benes slightly closer
        final r = sorted[i].fanIn > 1
            ? outerRadius * 0.85
            : outerRadius;
        positions[sorted[i].id] = Offset(
          centerX + r * cos(angle),
          centerY + r * sin(angle),
        );
      }
    }

    return positions;
  }

  @override
  Widget build(BuildContext context) {
    if (widget.graph.nodes.isEmpty) {
      return const SizedBox(
        height: 200,
        child: Center(
          child: Text('No graph data available.',
              style: TextStyle(color: AppTheme.textSecondary)),
        ),
      );
    }

    final nodeMap = {for (final n in widget.graph.nodes) n.id: n};

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // Selected node detail strip
        if (_selectedNodeId != null && nodeMap.containsKey(_selectedNodeId))
          _buildDetailStrip(nodeMap[_selectedNodeId]!),

        // The graph itself
        LayoutBuilder(
          builder: (context, constraints) {
            final width = constraints.maxWidth;
            final height = max(600.0, width * 0.6);
            final positions = _computeRadialLayout(width, height);

            return GestureDetector(
              onTapUp: (details) {
                final tapped = _hitTest(positions, details.localPosition);
                setState(() {
                  _selectedNodeId = (tapped == _selectedNodeId) ? null : tapped;
                });
              },
              child: MouseRegion(
                onHover: (event) {
                  final hit = _hitTest(positions, event.localPosition);
                  if (hit != _hoveredNodeId) {
                    setState(() => _hoveredNodeId = hit);
                  }
                },
                onExit: (_) => setState(() => _hoveredNodeId = null),
                cursor: _hoveredNodeId != null
                    ? SystemMouseCursors.click
                    : SystemMouseCursors.basic,
                child: SizedBox(
                  width: width,
                  height: height,
                  child: CustomPaint(
                    painter: _RadialGraphPainter(
                      nodes: widget.graph.nodes,
                      edges: widget.graph.edges,
                      positions: positions,
                      selectedNodeId: _selectedNodeId,
                      hoveredNodeId: _hoveredNodeId,
                      isConnectedFn: _isConnected,
                      isEdgeHighlightedFn: _isEdgeHighlighted,
                    ),
                  ),
                ),
              ),
            );
          },
        ),
      ],
    );
  }

  String? _hitTest(Map<String, Offset> positions, Offset localPos) {
    String? nearest;
    double minDist = 20.0;
    for (final entry in positions.entries) {
      final dist = (entry.value - localPos).distance;
      if (dist < minDist) {
        minDist = dist;
        nearest = entry.key;
      }
    }
    return nearest;
  }

  Widget _buildDetailStrip(NetworkNode node) {
    final neighbors = _adjacency[node.id] ?? {};
    final nodeMap = {for (final n in widget.graph.nodes) n.id: n};

    return Container(
      width: double.infinity,
      margin: const EdgeInsets.only(bottom: 12),
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      decoration: BoxDecoration(
        color: AppTheme.surface,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(
          color: node.isCenter
              ? AppTheme.pass.withValues(alpha: 0.5)
              : node.type == 'CLIENT'
                  ? AppTheme.accent.withValues(alpha: 0.5)
                  : AppTheme.alert.withValues(alpha: 0.5),
        ),
      ),
      child: Row(
        children: [
          // Node type badge
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
            decoration: BoxDecoration(
              color: node.isCenter
                  ? AppTheme.pass.withValues(alpha: 0.15)
                  : node.type == 'CLIENT'
                      ? AppTheme.accent.withValues(alpha: 0.15)
                      : AppTheme.alert.withValues(alpha: 0.15),
              borderRadius: BorderRadius.circular(4),
            ),
            child: Text(
              node.isCenter ? 'CENTER' : node.type,
              style: TextStyle(
                fontSize: 10,
                fontWeight: FontWeight.w700,
                color: node.isCenter
                    ? AppTheme.pass
                    : node.type == 'CLIENT'
                        ? AppTheme.accent
                        : AppTheme.alert,
                letterSpacing: 0.5,
              ),
            ),
          ),
          const SizedBox(width: 12),
          // Node ID
          Text(
            node.id,
            style: const TextStyle(
              color: AppTheme.textPrimary,
              fontSize: 13,
              fontWeight: FontWeight.w600,
            ),
          ),
          if (node.type == 'BENEFICIARY') ...[
            const SizedBox(width: 16),
            Text(
              'Fan-in: ${node.fanIn}',
              style: TextStyle(
                color: node.fanIn > 2 ? AppTheme.critical : AppTheme.textSecondary,
                fontSize: 12,
                fontWeight: node.fanIn > 2 ? FontWeight.w700 : FontWeight.w400,
              ),
            ),
          ],
          const SizedBox(width: 16),
          Text(
            '${neighbors.length} connection${neighbors.length == 1 ? '' : 's'}',
            style: const TextStyle(color: AppTheme.textSecondary, fontSize: 12),
          ),
          const Spacer(),
          // Connected nodes chips
          Flexible(
            child: Wrap(
              spacing: 6,
              runSpacing: 4,
              alignment: WrapAlignment.end,
              children: neighbors.take(8).map((nId) {
                final n = nodeMap[nId];
                return Container(
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                  decoration: BoxDecoration(
                    color: AppTheme.cardBg,
                    borderRadius: BorderRadius.circular(4),
                    border: Border.all(color: AppTheme.cardBorder),
                  ),
                  child: Text(
                    n?.label ?? nId,
                    style: TextStyle(
                      fontSize: 10,
                      color: n?.type == 'CLIENT' ? AppTheme.accent : AppTheme.alert,
                    ),
                  ),
                );
              }).toList(),
            ),
          ),
          const SizedBox(width: 8),
          InkWell(
            onTap: () => setState(() => _selectedNodeId = null),
            child: const Icon(Icons.close, size: 16, color: AppTheme.textSecondary),
          ),
        ],
      ),
    );
  }
}

class _RadialGraphPainter extends CustomPainter {
  final List<NetworkNode> nodes;
  final List<NetworkEdge> edges;
  final Map<String, Offset> positions;
  final String? selectedNodeId;
  final String? hoveredNodeId;
  final bool Function(String) isConnectedFn;
  final bool Function(NetworkEdge) isEdgeHighlightedFn;

  _RadialGraphPainter({
    required this.nodes,
    required this.edges,
    required this.positions,
    this.selectedNodeId,
    this.hoveredNodeId,
    required this.isConnectedFn,
    required this.isEdgeHighlightedFn,
  });

  @override
  void paint(Canvas canvas, Size size) {
    final hasActive = selectedNodeId != null || hoveredNodeId != null;

    // Draw edges
    for (final edge in edges) {
      final from = positions[edge.from];
      final to = positions[edge.to];
      if (from == null || to == null) continue;

      final highlighted = isEdgeHighlightedFn(edge);
      final edgePaint = Paint()
        ..color = highlighted
            ? AppTheme.accent.withValues(alpha: 0.6)
            : hasActive
                ? AppTheme.cardBorder.withValues(alpha: 0.15)
                : AppTheme.cardBorder.withValues(alpha: 0.35)
        ..strokeWidth = highlighted ? 2.0 : 0.8;

      canvas.drawLine(from, to, edgePaint);
    }

    // Build node map
    final nodeMap = {for (final n in nodes) n.id: n};

    // Draw nodes and labels
    for (final node in nodes) {
      final pos = positions[node.id];
      if (pos == null) continue;

      final connected = isConnectedFn(node.id);
      final isHovered = node.id == hoveredNodeId;
      final isSelected = node.id == selectedNodeId;
      final dimmed = hasActive && !connected;

      // Node color and size
      Color nodeColor;
      double radius;
      if (node.isCenter) {
        nodeColor = AppTheme.pass;
        radius = 16;
      } else if (node.type == 'CLIENT') {
        nodeColor = AppTheme.accent;
        radius = 12;
      } else {
        nodeColor = AppTheme.alert;
        radius = node.fanIn > 2 ? 10 : 7;
      }

      if (dimmed) {
        nodeColor = nodeColor.withValues(alpha: 0.2);
      }

      // Selection / hover ring
      if (isSelected) {
        canvas.drawCircle(
          pos,
          radius + 5,
          Paint()
            ..color = nodeColor.withValues(alpha: 0.3)
            ..style = PaintingStyle.fill,
        );
        canvas.drawCircle(
          pos,
          radius + 5,
          Paint()
            ..color = nodeColor
            ..style = PaintingStyle.stroke
            ..strokeWidth = 1.5,
        );
      } else if (isHovered) {
        canvas.drawCircle(
          pos,
          radius + 4,
          Paint()..color = nodeColor.withValues(alpha: 0.25),
        );
      }

      // Node circle
      canvas.drawCircle(pos, radius, Paint()..color = nodeColor);

      // White border for clients
      if (node.type == 'CLIENT' || node.isCenter) {
        canvas.drawCircle(
          pos,
          radius,
          Paint()
            ..color = Colors.white.withValues(alpha: dimmed ? 0.05 : 0.2)
            ..style = PaintingStyle.stroke
            ..strokeWidth = 1.5,
        );
      }

      // Label
      if (!dimmed || isSelected || isHovered) {
        final label = node.type == 'CLIENT' || node.isCenter
            ? node.label
            : _shortenBeneLabel(node.label);

        final textPainter = TextPainter(
          text: TextSpan(
            text: label,
            style: TextStyle(
              color: dimmed
                  ? AppTheme.textSecondary.withValues(alpha: 0.3)
                  : node.type == 'CLIENT' || node.isCenter
                      ? Colors.white
                      : AppTheme.textSecondary,
              fontSize: node.type == 'CLIENT' || node.isCenter ? 11 : 9,
              fontWeight: node.isCenter
                  ? FontWeight.w700
                  : node.type == 'CLIENT'
                      ? FontWeight.w600
                      : FontWeight.w400,
            ),
          ),
          textDirection: TextDirection.ltr,
        )..layout(maxWidth: 140);

        // Place label below node
        textPainter.paint(
          canvas,
          Offset(pos.dx - textPainter.width / 2, pos.dy + radius + 4),
        );

        // Fan-in badge for high-fanin beneficiaries
        if (node.type == 'BENEFICIARY' && node.fanIn > 2 && !dimmed) {
          final badgeText = TextPainter(
            text: TextSpan(
              text: '${node.fanIn}',
              style: const TextStyle(
                color: Colors.white,
                fontSize: 8,
                fontWeight: FontWeight.w700,
              ),
            ),
            textDirection: TextDirection.ltr,
          )..layout();

          final badgePos = Offset(pos.dx + radius - 2, pos.dy - radius - 2);
          canvas.drawCircle(
            badgePos,
            7,
            Paint()..color = AppTheme.critical,
          );
          badgeText.paint(
            canvas,
            Offset(badgePos.dx - badgeText.width / 2,
                badgePos.dy - badgeText.height / 2),
          );
        }
      }
    }
  }

  String _shortenBeneLabel(String label) {
    // Show last 10 chars of long beneficiary IDs for readability
    if (label.length > 14) {
      return '...${label.substring(label.length - 10)}';
    }
    return label;
  }

  @override
  bool shouldRepaint(_RadialGraphPainter oldDelegate) =>
      hoveredNodeId != oldDelegate.hoveredNodeId ||
      selectedNodeId != oldDelegate.selectedNodeId ||
      nodes != oldDelegate.nodes;
}
