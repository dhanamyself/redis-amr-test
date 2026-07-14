package com.example.amrkpi.report;

import com.example.amrkpi.kpi.cachemiss.CacheAsideReport;
import com.example.amrkpi.kpi.circuitbreaker.BreakerTimeInStateReport;
import com.example.amrkpi.kpi.consistency.ConsistencyReport;
import com.example.amrkpi.kpi.geolatency.GeoReplicationReport;
import com.example.amrkpi.kpi.throughput.ThroughputReport;
import com.example.amrkpi.kpi.tokenlifecycle.TokenLifecycleReport;
import com.example.amrkpi.kpi.tokenlifecycle.TokenRenewalCorrelation;
import com.example.amrkpi.kpi.uptime.UptimeReport;
import com.example.amrkpi.loadgen.LoadGenConfig;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke tests for the three export formats against a fully populated, realistic ReportData (and
 * against the null-throughput case every export explicitly guards for, since KPI 3 has no data
 * when no run was ever started). These aren't asserting on visual layout — they exist to catch
 * the class of bug where a writer throws (NPE on a null nested field, an unhandled cell type)
 * instead of producing a document, which nothing else in the suite would surface.
 */
class ReportWritersTest {

    private final CsvReportWriter csvWriter = new CsvReportWriter();
    private final XlsxReportWriter xlsxWriter = new XlsxReportWriter();
    private final PdfReportWriter pdfWriter = new PdfReportWriter();

    private ReportData buildSampleReportData(boolean includeThroughput) {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-01-01T01:00:00Z");

        AggregateStats sampleStats = new AggregateStats(1000, from, to, 100.0,
                100, 500, 900, 1200, 2000, 5000, Map.of("SOCKET_READ_TIMEOUT", 3L), 3);

        UptimeReport uptime = new UptimeReport("canada-central", from, to, 99.98, 720,
                List.of(new UptimeReport.Outage(from.plusSeconds(10), from.plusSeconds(15), 5000, "CONNECT_TIMEOUT")));

        BreakerTimeInStateReport breaker = new BreakerTimeInStateReport("canada-central", from, to,
                "CLOSED", Map.of("CLOSED", 3595000L, "OPEN", 5000L), 2);

        GeoReplicationReport geo = new GeoReplicationReport("canada-central->canada-east", from, to,
                new PercentileUtil.Stats(50, 10, 40, 90, 150, 300), 1);

        ThroughputReport throughput = includeThroughput ? new ThroughputReport(
                "run-123",
                new LoadGenConfig(900, 128, 5000, 0.8, 100_000, 2048, 20_480, 0.2, 0.8, 1800, true, 30),
                true,
                sampleStats,
                Map.of("loadgen.get", sampleStats, "loadgen.set", sampleStats),
                Map.of("canada-central", sampleStats),
                5000.0,
                4980.0
        ) : null;

        CacheAsideReport cacheAside = new CacheAsideReport(from, to, 800, 150, 50, 20.0,
                sampleStats, sampleStats, sampleStats);

        ConsistencyReport consistency = new ConsistencyReport(from, to, 20, 15,
                new PercentileUtil.Stats(20, 5, 50, 200, 400, 900),
                10, 9, 1,
                new PercentileUtil.Stats(10, 10, 60, 300, 500, 1200));

        TokenLifecycleReport tokenLifecycle = new TokenLifecycleReport(from, to, 2, 0, List.of(
                new TokenRenewalCorrelation(from.plusSeconds(1800), "SUCCESS", "amr-kpi-identity",
                        from.plusSeconds(5400), 3600_000L, sampleStats)
        ));

        return new ReportData(
                includeThroughput ? "run-123" : null,
                includeThroughput ? "loadgen" : null,
                includeThroughput ? "COMPLETED" : null,
                includeThroughput ? "{\"concurrency\":128}" : null,
                from, to,
                Map.of("canada-central", "CLOSED", "canada-east", "CLOSED"),
                uptime, uptime,
                breaker, breaker,
                geo, geo,
                sampleStats, sampleStats,
                throughput,
                cacheAside,
                consistency,
                tokenLifecycle
        );
    }

    @Test
    void csvWriterProducesNonEmptyOutputWithExpectedSections() {
        byte[] bytes = csvWriter.write(buildSampleReportData(true));
        String csv = new String(bytes, StandardCharsets.UTF_8);

        assertThat(bytes).isNotEmpty();
        assertThat(csv).contains("AMR KPI Test Harness Report")
                .contains("Uptime")
                .contains("Geo-Replication Lag")
                .contains("Cache-Aside")
                .contains("Consistency")
                .contains("Token Lifecycle")
                .contains("run-123");
    }

    @Test
    void csvWriterHandlesNullThroughputWithoutThrowing() {
        byte[] bytes = csvWriter.write(buildSampleReportData(false));

        assertThat(bytes).isNotEmpty();
        assertThat(new String(bytes, StandardCharsets.UTF_8)).doesNotContain("Throughput (KPI 3)");
    }

    @Test
    void xlsxWriterProducesAValidZipContainer() {
        byte[] bytes = xlsxWriter.write(buildSampleReportData(true));

        assertThat(bytes).isNotEmpty();
        // .xlsx is a zip container -> "PK" magic bytes at the start.
        assertThat(bytes[0]).isEqualTo((byte) 'P');
        assertThat(bytes[1]).isEqualTo((byte) 'K');
    }

    @Test
    void xlsxWriterHandlesNullThroughputWithoutThrowing() {
        byte[] bytes = xlsxWriter.write(buildSampleReportData(false));

        assertThat(bytes).isNotEmpty();
        assertThat(bytes[0]).isEqualTo((byte) 'P');
    }

    @Test
    void pdfWriterProducesAValidPdfDocument() {
        byte[] bytes = pdfWriter.write(buildSampleReportData(true));

        assertThat(bytes).isNotEmpty();
        assertThat(new String(bytes, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
    }

    @Test
    void pdfWriterHandlesNullThroughputWithoutThrowing() {
        byte[] bytes = pdfWriter.write(buildSampleReportData(false));

        assertThat(bytes).isNotEmpty();
        assertThat(new String(bytes, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
    }
}
