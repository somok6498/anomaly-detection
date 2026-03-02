import 'dart:convert';
import 'dart:typed_data';
// ignore: avoid_web_libraries_in_flutter
import 'dart:html' as html;
import 'package:csv/csv.dart';
import 'package:pdf/pdf.dart';
import 'package:pdf/widgets.dart' as pw;

class ExportService {
  static String _timestampedName(String filename) {
    final now = DateTime.now();
    final stamp = '${now.year}'
        '${now.month.toString().padLeft(2, '0')}'
        '${now.day.toString().padLeft(2, '0')}'
        '_${now.hour.toString().padLeft(2, '0')}'
        '${now.minute.toString().padLeft(2, '0')}'
        '${now.second.toString().padLeft(2, '0')}';
    final dot = filename.lastIndexOf('.');
    if (dot == -1) return '${filename}_$stamp';
    return '${filename.substring(0, dot)}_$stamp${filename.substring(dot)}';
  }

  /// Generate a CSV string and trigger browser download.
  static void downloadCsv(String filename, List<List<String>> rows) {
    final csvData = const ListToCsvConverter().convert(rows);
    final bytes = utf8.encode(csvData);
    final blob = html.Blob([bytes], 'text/csv');
    final url = html.Url.createObjectUrlFromBlob(blob);
    html.AnchorElement(href: url)
      ..setAttribute('download', _timestampedName(filename))
      ..click();
    html.Url.revokeObjectUrl(url);
  }

  /// Generate a PDF with a title and table, then trigger browser download.
  static Future<void> downloadPdf(
    String title,
    List<String> headers,
    List<List<String>> rows,
    String filename,
  ) async {
    final pdf = pw.Document();

    // Split rows into pages of 30 rows each
    const rowsPerPage = 30;
    final totalPages = (rows.length / rowsPerPage).ceil().clamp(1, 100);

    for (int page = 0; page < totalPages; page++) {
      final start = page * rowsPerPage;
      final end = (start + rowsPerPage).clamp(0, rows.length);
      final pageRows = rows.sublist(start, end);

      pdf.addPage(
        pw.Page(
          pageFormat: PdfPageFormat.a4.landscape,
          margin: const pw.EdgeInsets.all(24),
          build: (context) {
            return pw.Column(
              crossAxisAlignment: pw.CrossAxisAlignment.start,
              children: [
                if (page == 0) ...[
                  pw.Text(title, style: pw.TextStyle(fontSize: 18, fontWeight: pw.FontWeight.bold)),
                  pw.SizedBox(height: 4),
                  pw.Text('Generated: ${DateTime.now().toIso8601String()}',
                      style: const pw.TextStyle(fontSize: 10, color: PdfColors.grey600)),
                  pw.SizedBox(height: 16),
                ],
                pw.TableHelper.fromTextArray(
                  headerStyle: pw.TextStyle(fontSize: 8, fontWeight: pw.FontWeight.bold),
                  cellStyle: const pw.TextStyle(fontSize: 7),
                  headerDecoration: const pw.BoxDecoration(color: PdfColors.grey200),
                  cellHeight: 20,
                  cellAlignments: {for (int i = 0; i < headers.length; i++) i: pw.Alignment.centerLeft},
                  headers: headers,
                  data: pageRows,
                ),
              ],
            );
          },
        ),
      );
    }

    final Uint8List bytes = await pdf.save();
    final blob = html.Blob([bytes.buffer], 'application/pdf');
    final url = html.Url.createObjectUrlFromBlob(blob);
    html.AnchorElement(href: url)
      ..setAttribute('download', _timestampedName(filename))
      ..click();
    html.Url.revokeObjectUrl(url);
  }
}
