import java.text.SimpleDateFormat
import java.util.Date
import javax.xml.parsers.DocumentBuilderFactory

plugins {
    java
    id("org.springframework.boot") version "3.2.5"
    id("io.spring.dependency-management") version "1.1.4"
}

group = "com.bank"
version = "1.0.0-SNAPSHOT"
description = "Real-time behavioral anomaly detection for banking transactions"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("com.aerospike:aerospike-client-jdk8:9.0.5")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.5.0")
    implementation("com.twilio.sdk:twilio:10.1.0")

    // Observability: OTEL traces + Micrometer metrics
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-aop")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    implementation("io.micrometer:micrometer-registry-prometheus")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.test {
    useJUnitPlatform()
}

// Allow test failures when generating the executive report (so the report always gets created)
gradle.taskGraph.whenReady {
    if (hasTask(":testReport")) {
        tasks.test.get().ignoreFailures = true
    }
}

tasks.register("testReport") {
    description = "Runs tests and generates an executive-summary HTML report"
    group = "verification"
    dependsOn("test")

    doLast {
        val testResultsDir = file("build/test-results/test")
        val xmlFiles = testResultsDir.listFiles { f: java.io.File ->
            f.name.startsWith("TEST-") && f.name.endsWith(".xml")
        } ?: error("No test result XMLs found in ${testResultsDir.absolutePath}. Did tests run?")

        val dbFactory = DocumentBuilderFactory.newInstance()
        val dBuilder = dbFactory.newDocumentBuilder()

        // Parse each XML into a list of maps (avoiding data class in Gradle DSL)
        val results = xmlFiles.map { xmlFile ->
            val doc = dBuilder.parse(xmlFile)
            val suite = doc.documentElement
            val fqcn = suite.getAttribute("name")
            val pkg = fqcn.split(".").getOrElse(4) { "other" }
            val layer = when (pkg) {
                "controller" -> "Controller"
                "service" -> "Service"
                "contract" -> "Contract"
                else -> "Other"
            }
            mapOf(
                "className" to fqcn.substringAfterLast("."),
                "tests" to suite.getAttribute("tests"),
                "failures" to suite.getAttribute("failures"),
                "errors" to suite.getAttribute("errors"),
                "skipped" to suite.getAttribute("skipped"),
                "time" to suite.getAttribute("time"),
                "layer" to layer
            )
        }.sortedWith(compareBy({ it["layer"] }, { it["className"] }))

        fun Map<String, String>.i(key: String) = getValue(key).toInt()
        fun Map<String, String>.d(key: String) = getValue(key).toDouble()

        val totalTests = results.sumOf { it.i("tests") }
        val totalFailed = results.sumOf { it.i("failures") + it.i("errors") }
        val totalSkipped = results.sumOf { it.i("skipped") }
        val totalPassed = totalTests - totalFailed - totalSkipped
        val totalTime = results.sumOf { it.d("time") }
        val passRate = if (totalTests > 0) (totalPassed * 100.0 / totalTests) else 0.0
        val allGreen = totalFailed == 0

        val byLayer = results.groupBy { it.getValue("layer") }
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())
        val javaVersion = System.getProperty("java.version")
        val projName = project.name
        val projVersion = project.version

        val html = buildString {
            append("""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Test Report - $projName</title>
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #f1f5f9; color: #1e293b; line-height: 1.6; }
  .container { max-width: 900px; margin: 0 auto; padding: 24px; }
  .header { background: linear-gradient(135deg, #1a2332 0%, #2d3748 100%); color: white; padding: 36px 32px; border-radius: 12px 12px 0 0; }
  .header h1 { font-size: 24px; font-weight: 700; margin-bottom: 4px; }
  .header p { font-size: 14px; color: #94a3b8; }
  .status-banner { padding: 16px 32px; font-size: 16px; font-weight: 700; letter-spacing: 0.5px; }
  .status-pass { background: #16a34a; color: white; }
  .status-fail { background: #dc2626; color: white; }
  .summary-section { background: white; padding: 28px 32px; border-bottom: 1px solid #e2e8f0; }
  .summary-section h2 { font-size: 15px; text-transform: uppercase; letter-spacing: 1px; color: #64748b; margin-bottom: 16px; }
  .stats-grid { display: flex; gap: 16px; flex-wrap: wrap; }
  .stat-card { flex: 1; min-width: 130px; background: #f8fafc; border-radius: 10px; padding: 20px 16px; text-align: center; border: 1px solid #e2e8f0; }
  .stat-value { font-size: 32px; font-weight: 800; line-height: 1.1; }
  .stat-label { font-size: 12px; color: #64748b; text-transform: uppercase; letter-spacing: 0.5px; margin-top: 4px; }
  .green { color: #16a34a; }
  .red { color: #dc2626; }
  .amber { color: #d97706; }
  .layers-section { background: white; padding: 28px 32px; border-bottom: 1px solid #e2e8f0; }
  .layers-section h2 { font-size: 15px; text-transform: uppercase; letter-spacing: 1px; color: #64748b; margin-bottom: 16px; }
  .layer-card { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 10px; padding: 18px 20px; margin-bottom: 12px; }
  .layer-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
  .layer-name { font-size: 16px; font-weight: 700; }
  .layer-meta { font-size: 13px; color: #64748b; }
  .progress-bar { height: 8px; background: #e2e8f0; border-radius: 4px; overflow: hidden; }
  .progress-fill { height: 100%; border-radius: 4px; transition: width 0.3s; }
  .detail-section { background: white; padding: 28px 32px; border-radius: 0 0 12px 12px; }
  .detail-section h2 { font-size: 15px; text-transform: uppercase; letter-spacing: 1px; color: #64748b; margin-bottom: 16px; }
  table { width: 100%; border-collapse: collapse; font-size: 14px; }
  th { text-align: left; padding: 10px 12px; background: #f8fafc; border-bottom: 2px solid #e2e8f0; font-weight: 600; font-size: 12px; text-transform: uppercase; letter-spacing: 0.5px; color: #64748b; }
  td { padding: 10px 12px; border-bottom: 1px solid #f1f5f9; }
  tr:hover td { background: #f8fafc; }
  .badge { display: inline-block; padding: 3px 12px; border-radius: 12px; font-size: 12px; font-weight: 600; }
  .badge-pass { background: #dcfce7; color: #166534; }
  .badge-fail { background: #fee2e2; color: #991b1b; }
  .badge-skip { background: #fef3c7; color: #92400e; }
  .layer-badge { display: inline-block; padding: 2px 8px; border-radius: 4px; font-size: 11px; font-weight: 600; }
  .layer-controller { background: #dbeafe; color: #1e40af; }
  .layer-service { background: #ede9fe; color: #5b21b6; }
  .layer-contract { background: #fce7f3; color: #9d174d; }
  .layer-other { background: #e2e8f0; color: #475569; }
  .footer { text-align: center; padding: 24px; color: #94a3b8; font-size: 12px; }
  @media print { body { background: white; } .container { max-width: 100%; padding: 0; } .header { border-radius: 0; } .detail-section { border-radius: 0; } }
  @media (max-width: 600px) { .stats-grid { flex-direction: column; } .stat-card { min-width: auto; } }
</style>
</head>
<body>
<div class="container">
""")

            // Header
            append("""  <div class="header">
    <h1>Anomaly Detection &mdash; Test Report</h1>
    <p>$projName v$projVersion &nbsp;|&nbsp; Java $javaVersion &nbsp;|&nbsp; $timestamp</p>
  </div>
""")

            // Status banner
            if (allGreen) {
                append("""  <div class="status-banner status-pass">&#10004; ALL $totalTests TESTS PASSING</div>
""")
            } else {
                append("""  <div class="status-banner status-fail">&#10008; $totalFailed FAILURE(S) DETECTED &mdash; $totalPassed / $totalTests PASSED</div>
""")
            }

            // Executive summary stats
            val rateClass = if (allGreen) "green" else if (passRate >= 80) "amber" else "red"
            append("""  <div class="summary-section">
    <h2>Executive Summary</h2>
    <div class="stats-grid">
      <div class="stat-card">
        <div class="stat-value $rateClass">${String.format("%.0f", passRate)}%</div>
        <div class="stat-label">Pass Rate</div>
      </div>
      <div class="stat-card">
        <div class="stat-value">$totalTests</div>
        <div class="stat-label">Total Tests</div>
      </div>
      <div class="stat-card">
        <div class="stat-value green">$totalPassed</div>
        <div class="stat-label">Passed</div>
      </div>
      <div class="stat-card">
        <div class="stat-value ${if (totalFailed > 0) "red" else ""}">$totalFailed</div>
        <div class="stat-label">Failed</div>
      </div>
      <div class="stat-card">
        <div class="stat-value">${String.format("%.1f", totalTime)}s</div>
        <div class="stat-label">Duration</div>
      </div>
    </div>
  </div>
""")

            // Layer breakdown
            append("""  <div class="layers-section">
    <h2>Coverage by Layer</h2>
""")

            val layerOrder = listOf("Controller", "Service", "Contract", "Other")
            for (layer in layerOrder) {
                val suites = byLayer[layer] ?: continue
                val lTests = suites.sumOf { it.i("tests") }
                val lFailed = suites.sumOf { it.i("failures") + it.i("errors") }
                val lSkipped = suites.sumOf { it.i("skipped") }
                val lPassed = lTests - lFailed - lSkipped
                val lTime = suites.sumOf { it.d("time") }
                val lPct = if (lTests > 0) (lPassed * 100.0 / lTests) else 0.0
                val barColor = if (lFailed == 0) "#16a34a" else "#dc2626"
                append("""    <div class="layer-card">
      <div class="layer-header">
        <span class="layer-name">$layer Tests</span>
        <span class="layer-meta">${suites.size} classes &nbsp;|&nbsp; $lPassed / $lTests passed &nbsp;|&nbsp; ${String.format("%.2f", lTime)}s</span>
      </div>
      <div class="progress-bar"><div class="progress-fill" style="width:${String.format("%.1f", lPct)}%;background:$barColor"></div></div>
    </div>
""")
            }
            append("""  </div>
""")

            // Detailed results table
            append("""  <div class="detail-section">
    <h2>Detailed Results</h2>
    <table>
      <thead><tr><th>Layer</th><th>Test Class</th><th>Tests</th><th>Passed</th><th>Failed</th><th>Skipped</th><th>Time</th><th>Status</th></tr></thead>
      <tbody>
""")

            for (r in results) {
                val tests = r.i("tests")
                val failures = r.i("failures")
                val errors = r.i("errors")
                val skipped = r.i("skipped")
                val passed = tests - failures - errors - skipped
                val failed = failures + errors
                val time = r.d("time")
                val className = r.getValue("className")
                val layer = r.getValue("layer")
                val statusBadge = if (failed > 0) """<span class="badge badge-fail">&#10008; FAIL</span>"""
                    else if (skipped > 0) """<span class="badge badge-skip">&#9888; SKIP</span>"""
                    else """<span class="badge badge-pass">&#10004; PASS</span>"""
                val layerCss = "layer-${layer.lowercase()}"
                append("""        <tr>
          <td><span class="layer-badge $layerCss">$layer</span></td>
          <td>$className</td>
          <td>$tests</td>
          <td>$passed</td>
          <td>${if (failed > 0) """<strong class="red">$failed</strong>""" else "0"}</td>
          <td>$skipped</td>
          <td>${String.format("%.3f", time)}s</td>
          <td>$statusBadge</td>
        </tr>
""")
            }

            append("""      </tbody>
    </table>
  </div>

  <div class="footer">
    Generated by <strong>./gradlew testReport</strong> &nbsp;|&nbsp; $projName v$projVersion &nbsp;|&nbsp; $timestamp
  </div>
</div>
</body>
</html>""")
        }

        val outputFile = file("build/reports/tests/executive-summary.html")
        outputFile.parentFile.mkdirs()
        outputFile.writeText(html)

        println("\n==========================================")
        println("  Executive Summary Report generated:")
        println("  ${outputFile.absolutePath}")
        if (!allGreen) {
            println("  WARNING: $totalFailed test(s) failed!")
        }
        println("==========================================\n")
    }
}
