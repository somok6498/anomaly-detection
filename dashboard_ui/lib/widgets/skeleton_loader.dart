import 'package:flutter/material.dart';

class ShimmerContainer extends StatefulWidget {
  final Widget child;

  const ShimmerContainer({super.key, required this.child});

  @override
  State<ShimmerContainer> createState() => _ShimmerContainerState();
}

class _ShimmerContainerState extends State<ShimmerContainer>
    with SingleTickerProviderStateMixin {
  late AnimationController _controller;

  @override
  void initState() {
    super.initState();
    _controller = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 1500),
    )..repeat();
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: _controller,
      builder: (context, child) {
        return ShaderMask(
          shaderCallback: (bounds) {
            return LinearGradient(
              begin: Alignment.centerLeft,
              end: Alignment.centerRight,
              colors: const [
                Color(0xFF2A2D35),
                Color(0xFF3A3D45),
                Color(0xFF2A2D35),
              ],
              stops: [
                (_controller.value - 0.3).clamp(0.0, 1.0),
                _controller.value,
                (_controller.value + 0.3).clamp(0.0, 1.0),
              ],
            ).createShader(bounds);
          },
          blendMode: BlendMode.srcATop,
          child: widget.child,
        );
      },
      child: widget.child,
    );
  }
}

class SkeletonLine extends StatelessWidget {
  final double width;
  final double height;

  const SkeletonLine({super.key, this.width = double.infinity, this.height = 14});

  @override
  Widget build(BuildContext context) {
    return Container(
      width: width,
      height: height,
      decoration: BoxDecoration(
        color: const Color(0xFF2A2D35),
        borderRadius: BorderRadius.circular(4),
      ),
    );
  }
}

class SkeletonCard extends StatelessWidget {
  const SkeletonCard({super.key});

  @override
  Widget build(BuildContext context) {
    return ShimmerContainer(
      child: Container(
        padding: const EdgeInsets.all(20),
        decoration: BoxDecoration(
          color: const Color(0xFF1E2028),
          borderRadius: BorderRadius.circular(10),
          border: Border.all(color: const Color(0xFF2A2D35)),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const SkeletonLine(width: 180, height: 16),
            const SizedBox(height: 16),
            Wrap(
              spacing: 14,
              runSpacing: 14,
              children: List.generate(6, (_) => const SizedBox(
                width: 180,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    SkeletonLine(width: 80, height: 10),
                    SizedBox(height: 6),
                    SkeletonLine(width: 120, height: 18),
                  ],
                ),
              )),
            ),
          ],
        ),
      ),
    );
  }
}

class SkeletonTable extends StatelessWidget {
  final int rows;
  final int columns;

  const SkeletonTable({super.key, this.rows = 8, this.columns = 4});

  @override
  Widget build(BuildContext context) {
    return ShimmerContainer(
      child: Container(
        padding: const EdgeInsets.all(20),
        decoration: BoxDecoration(
          color: const Color(0xFF1E2028),
          borderRadius: BorderRadius.circular(10),
          border: Border.all(color: const Color(0xFF2A2D35)),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const SkeletonLine(width: 200, height: 16),
            const SizedBox(height: 16),
            // Header row
            Row(
              children: List.generate(columns, (i) => Expanded(
                child: Padding(
                  padding: const EdgeInsets.only(right: 12),
                  child: SkeletonLine(height: 10, width: 60 + (i * 10).toDouble()),
                ),
              )),
            ),
            const SizedBox(height: 12),
            const Divider(color: Color(0xFF2A2D35), height: 1),
            // Data rows
            ...List.generate(rows, (rowIdx) => Padding(
              padding: const EdgeInsets.symmetric(vertical: 10),
              child: Row(
                children: List.generate(columns, (colIdx) => Expanded(
                  child: Padding(
                    padding: const EdgeInsets.only(right: 12),
                    child: SkeletonLine(height: 14),
                  ),
                )),
              ),
            )),
          ],
        ),
      ),
    );
  }
}

class InvestigationSkeleton extends StatelessWidget {
  const InvestigationSkeleton({super.key});

  @override
  Widget build(BuildContext context) {
    return const Padding(
      padding: EdgeInsets.only(top: 20),
      child: Column(
        children: [
          SkeletonCard(),
          SizedBox(height: 20),
          SkeletonTable(rows: 5, columns: 4),
          SizedBox(height: 20),
          SkeletonTable(rows: 5, columns: 6),
        ],
      ),
    );
  }
}

class ReviewQueueSkeleton extends StatelessWidget {
  const ReviewQueueSkeleton({super.key});

  @override
  Widget build(BuildContext context) {
    return const Center(
      child: Padding(
        padding: EdgeInsets.all(20),
        child: SkeletonTable(rows: 10, columns: 5),
      ),
    );
  }
}
