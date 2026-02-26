import 'dart:math';
import 'package:flutter/material.dart';
import '../models/models.dart';
import '../theme/app_theme.dart';

/// Force-directed network graph visualization for beneficiary relationships.
class NetworkGraphWidget extends StatefulWidget {
  final NetworkGraph graph;

  const NetworkGraphWidget({super.key, required this.graph});

  @override
  State<NetworkGraphWidget> createState() => _NetworkGraphWidgetState();
}

class _NetworkGraphWidgetState extends State<NetworkGraphWidget> {
  // Node positions after layout
  Map<String, Offset> _positions = {};
  String? _hoveredNodeId;

  @override
  void initState() {
    super.initState();
    _computeLayout();
  }

  @override
  void didUpdateWidget(NetworkGraphWidget oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.graph != widget.graph) {
      _computeLayout();
    }
  }

  void _computeLayout() {
    final nodes = widget.graph.nodes;
    final edges = widget.graph.edges;
    if (nodes.isEmpty) return;

    final rng = Random(42);
    final positions = <String, Offset>{};

    // Initialize: center node at origin, others randomly
    for (final node in nodes) {
      if (node.isCenter) {
        positions[node.id] = Offset.zero;
      } else {
        positions[node.id] = Offset(
          (rng.nextDouble() - 0.5) * 400,
          (rng.nextDouble() - 0.5) * 400,
        );
      }
    }

    // Build adjacency for attraction
    final adj = <String, Set<String>>{};
    for (final edge in edges) {
      adj.putIfAbsent(edge.from, () => {}).add(edge.to);
      adj.putIfAbsent(edge.to, () => {}).add(edge.from);
    }

    // Run force-directed iterations
    const iterations = 80;
    const repulsion = 8000.0;
    const attraction = 0.01;
    const damping = 0.9;

    final velocities = <String, Offset>{};
    for (final node in nodes) {
      velocities[node.id] = Offset.zero;
    }

    for (int iter = 0; iter < iterations; iter++) {
      final forces = <String, Offset>{};
      for (final node in nodes) {
        forces[node.id] = Offset.zero;
      }

      // Repulsion between all pairs
      for (int i = 0; i < nodes.length; i++) {
        for (int j = i + 1; j < nodes.length; j++) {
          final a = nodes[i].id;
          final b = nodes[j].id;
          final delta = positions[a]! - positions[b]!;
          final dist = max(delta.distance, 1.0);
          final force = repulsion / (dist * dist);
          final normalized = delta / dist;
          forces[a] = forces[a]! + normalized * force;
          forces[b] = forces[b]! - normalized * force;
        }
      }

      // Attraction along edges
      for (final edge in edges) {
        final pa = positions[edge.from];
        final pb = positions[edge.to];
        if (pa == null || pb == null) continue;
        final delta = pb - pa;
        final dist = max(delta.distance, 1.0);
        final force = dist * attraction;
        final normalized = delta / dist;
        forces[edge.from] = forces[edge.from]! + normalized * force;
        forces[edge.to] = forces[edge.to]! - normalized * force;
      }

      // Apply forces with damping
      for (final node in nodes) {
        if (node.isCenter) continue; // Pin center node
        final vel = (velocities[node.id]! + forces[node.id]!) * damping;
        velocities[node.id] = vel;
        positions[node.id] = positions[node.id]! + vel;
      }
    }

    // Normalize to fit in a reasonable area
    double minX = double.infinity, maxX = double.negativeInfinity;
    double minY = double.infinity, maxY = double.negativeInfinity;
    for (final pos in positions.values) {
      minX = min(minX, pos.dx);
      maxX = max(maxX, pos.dx);
      minY = min(minY, pos.dy);
      maxY = max(maxY, pos.dy);
    }

    final rangeX = max(maxX - minX, 1.0);
    final rangeY = max(maxY - minY, 1.0);
    final scale = 500.0;

    final normalized = <String, Offset>{};
    for (final entry in positions.entries) {
      normalized[entry.key] = Offset(
        ((entry.value.dx - minX) / rangeX - 0.5) * scale,
        ((entry.value.dy - minY) / rangeY - 0.5) * scale,
      );
    }

    setState(() => _positions = normalized);
  }

  @override
  Widget build(BuildContext context) {
    if (widget.graph.nodes.isEmpty) {
      return const Center(
        child: Text('No graph data available.', style: TextStyle(color: AppTheme.textSecondary)),
      );
    }

    return InteractiveViewer(
      boundaryMargin: const EdgeInsets.all(200),
      minScale: 0.3,
      maxScale: 3.0,
      child: MouseRegion(
        onHover: (event) {
          // Find nearest node within 20px
          final localPos = event.localPosition - const Offset(300, 250);
          String? nearest;
          double minDist = 25.0;
          for (final entry in _positions.entries) {
            final dist = (entry.value - localPos).distance;
            if (dist < minDist) {
              minDist = dist;
              nearest = entry.key;
            }
          }
          if (nearest != _hoveredNodeId) {
            setState(() => _hoveredNodeId = nearest);
          }
        },
        onExit: (_) => setState(() => _hoveredNodeId = null),
        child: SizedBox(
          width: 600,
          height: 500,
          child: CustomPaint(
            painter: _GraphPainter(
              nodes: widget.graph.nodes,
              edges: widget.graph.edges,
              positions: _positions,
              hoveredNodeId: _hoveredNodeId,
              center: const Offset(300, 250),
            ),
          ),
        ),
      ),
    );
  }
}

class _GraphPainter extends CustomPainter {
  final List<NetworkNode> nodes;
  final List<NetworkEdge> edges;
  final Map<String, Offset> positions;
  final String? hoveredNodeId;
  final Offset center;

  _GraphPainter({
    required this.nodes,
    required this.edges,
    required this.positions,
    this.hoveredNodeId,
    required this.center,
  });

  @override
  void paint(Canvas canvas, Size size) {
    final edgePaint = Paint()
      ..color = AppTheme.cardBorder.withValues(alpha: 0.5)
      ..strokeWidth = 1.0;

    // Draw edges
    for (final edge in edges) {
      final from = positions[edge.from];
      final to = positions[edge.to];
      if (from == null || to == null) continue;
      canvas.drawLine(from + center, to + center, edgePaint);
    }

    // Build node map for quick lookup
    final nodeMap = {for (final n in nodes) n.id: n};

    // Draw nodes
    for (final node in nodes) {
      final pos = positions[node.id];
      if (pos == null) continue;
      final screenPos = pos + center;

      Color nodeColor;
      double radius;
      if (node.isCenter) {
        nodeColor = AppTheme.pass; // Green for center
        radius = 14;
      } else if (node.type == 'CLIENT') {
        nodeColor = AppTheme.accent; // Blue for other clients
        radius = 10;
      } else {
        nodeColor = AppTheme.alert; // Orange for beneficiaries
        radius = node.fanIn > 2 ? 10 : 7;
      }

      // Highlight on hover
      if (node.id == hoveredNodeId) {
        canvas.drawCircle(screenPos, radius + 4,
            Paint()..color = nodeColor.withValues(alpha: 0.3));
      }

      canvas.drawCircle(screenPos, radius, Paint()..color = nodeColor);

      // Label
      final textPainter = TextPainter(
        text: TextSpan(
          text: node.label,
          style: TextStyle(
            color: node.id == hoveredNodeId ? Colors.white : AppTheme.textSecondary,
            fontSize: node.type == 'CLIENT' ? 10 : 8,
            fontWeight: node.isCenter ? FontWeight.w700 : FontWeight.w400,
          ),
        ),
        textDirection: TextDirection.ltr,
      )..layout(maxWidth: 120);
      textPainter.paint(canvas, screenPos + Offset(-textPainter.width / 2, radius + 3));
    }

    // Tooltip for hovered node
    if (hoveredNodeId != null && nodeMap.containsKey(hoveredNodeId)) {
      final node = nodeMap[hoveredNodeId]!;
      final pos = positions[hoveredNodeId];
      if (pos != null) {
        final screenPos = pos + center;
        String tooltip = node.type == 'CLIENT'
            ? node.label
            : '${node.id}\nFan-in: ${node.fanIn}';

        final tp = TextPainter(
          text: TextSpan(
            text: tooltip,
            style: const TextStyle(color: Colors.white, fontSize: 11),
          ),
          textDirection: TextDirection.ltr,
        )..layout(maxWidth: 200);

        final bg = Rect.fromLTWH(
          screenPos.dx - tp.width / 2 - 8,
          screenPos.dy - tp.height - 24,
          tp.width + 16,
          tp.height + 8,
        );
        canvas.drawRRect(
          RRect.fromRectAndRadius(bg, const Radius.circular(4)),
          Paint()..color = AppTheme.surface,
        );
        tp.paint(canvas, Offset(bg.left + 8, bg.top + 4));
      }
    }
  }

  @override
  bool shouldRepaint(_GraphPainter oldDelegate) =>
      hoveredNodeId != oldDelegate.hoveredNodeId ||
      nodes != oldDelegate.nodes;
}
