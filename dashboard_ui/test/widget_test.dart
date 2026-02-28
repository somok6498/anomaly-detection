import 'package:flutter_test/flutter_test.dart';
import 'package:anomaly_dashboard/main.dart';

void main() {
  testWidgets('App smoke test', (WidgetTester tester) async {
    await tester.pumpWidget(const AnomalyDashboardApp());
    expect(find.text('Anomaly Detection Dashboard'), findsOneWidget);
  });
}
