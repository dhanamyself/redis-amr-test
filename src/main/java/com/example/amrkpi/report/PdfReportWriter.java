package com.example.amrkpi.report;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Data-complete, table-based PDF report (run config, summary tables, error breakdowns, outcome
 * tables). Rendered chart images are out of scope for this pass — the live dashboard (Chart.js)
 * is where visual charts live; this export is for offline records and sharing, not visuals.
 */
@Component
public class PdfReportWriter {

    private static final float MARGIN = 50;
    private static final float LEADING = 14;
    private static final float FONT_SIZE = 10;
    private static final float TITLE_FONT_SIZE = 14;

    public byte[] write(ReportData data) {
        try (PDDocument document = new PDDocument()) {
            Cursor cursor = new Cursor(document);

            cursor.title("AMR KPI Test Harness Report");
            cursor.line("Generated At: " + java.time.Instant.now());
            cursor.line("Run ID: " + data.runId());
            cursor.line("Run Type: " + data.runType() + "   Status: " + data.runStatus());
            cursor.line("Window: " + data.from() + " to " + data.to());
            cursor.blank();

            cursor.title("Circuit Breaker States (live)");
            data.breakerStates().forEach((region, state) -> cursor.line(region + ": " + state));
            cursor.blank();

            cursor.title("Uptime");
            uptime(cursor, data.uptimeLocal());
            uptime(cursor, data.uptimeFailover());
            cursor.blank();

            cursor.title("Circuit Breaker Time-In-State");
            breaker(cursor, data.breakerTimeInStateLocal());
            breaker(cursor, data.breakerTimeInStateFailover());
            cursor.blank();

            cursor.title("Geo-Replication Lag (ms)");
            geo(cursor, data.geoLocalToFailover());
            geo(cursor, data.geoFailoverToLocal());
            cursor.blank();

            cursor.title("Network Time (us)");
            agg(cursor, "local", data.networkTimeLocal());
            agg(cursor, "failover", data.networkTimeFailover());
            cursor.blank();

            if (data.throughput() != null) {
                cursor.title("Throughput (KPI 3) - run " + data.runId());
                cursor.line("target ops/sec: " + data.throughput().targetOpsPerSec()
                        + "   achieved ops/sec: " + String.format("%.1f", data.throughput().achievedOpsPerSec()));
                agg(cursor, "overall", data.throughput().overall());
                data.throughput().byOperation().forEach((op, s) -> agg(cursor, "op:" + op, s));
                data.throughput().byRegion().forEach((region, s) -> agg(cursor, "region:" + region, s));
                cursor.blank();
            }

            cursor.title("Cache-Aside (KPI 6)");
            var ca = data.cacheAside();
            cursor.line("hit=" + ca.hitCount() + " miss=" + ca.missCount() + " degraded=" + ca.degradedCount()
                    + " missRatio=" + String.format("%.1f%%", ca.missRatio()));
            agg(cursor, "hit", ca.hitLatency());
            agg(cursor, "miss", ca.missLatency());
            agg(cursor, "degraded", ca.degradedLatency());
            cursor.blank();

            cursor.title("Consistency & Conflict (KPI 9)");
            var c = data.consistency();
            cursor.line("staleness probes: " + c.stalenessProbeCount() + "   stale at t0: " + c.staleAtT0Count());
            cursor.line("stale window ms p50/p95/p99/max: " + c.staleWindowMillis().p50() + "/" + c.staleWindowMillis().p95()
                    + "/" + c.staleWindowMillis().p99() + "/" + c.staleWindowMillis().max());
            cursor.line("conflict probes: " + c.conflictProbeCount() + "   converged: " + c.convergedCount()
                    + "   diverged: " + c.divergedCount());
            cursor.line("convergence ms p50/p95/p99/max: " + c.convergenceTimeMillis().p50() + "/" + c.convergenceTimeMillis().p95()
                    + "/" + c.convergenceTimeMillis().p99() + "/" + c.convergenceTimeMillis().max());
            cursor.blank();

            cursor.title("Token Lifecycle (KPI 8)");
            var tl = data.tokenLifecycle();
            cursor.line("success=" + tl.successCount() + " failure=" + tl.failureCount());
            for (var renewal : tl.renewals()) {
                cursor.line(renewal.renewedAt() + " " + renewal.outcome() + " ttlMillis=" + renewal.tokenTtlMillis());
            }

            cursor.close();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void uptime(Cursor cursor, com.example.amrkpi.kpi.uptime.UptimeReport report) {
        cursor.line(report.region() + ": uptime=" + String.format("%.3f%%", report.uptimePercent())
                + " probes=" + report.probeCount() + " outages=" + report.outages().size());
    }

    private void breaker(Cursor cursor, com.example.amrkpi.kpi.circuitbreaker.BreakerTimeInStateReport report) {
        StringBuilder sb = new StringBuilder(report.region() + ": current=" + report.currentState() + "  ");
        report.timeInStateMillis().forEach((state, ms) -> sb.append(state).append("=").append(ms).append("ms "));
        cursor.line(sb.toString());
    }

    private void geo(Cursor cursor, com.example.amrkpi.kpi.geolatency.GeoReplicationReport report) {
        var s = report.latencyMillis();
        cursor.line(report.direction() + ": count=" + s.count() + " min=" + s.min() + " p50=" + s.p50()
                + " p95=" + s.p95() + " p99=" + s.p99() + " max=" + s.max() + " timeouts=" + report.timeoutCount());
    }

    private void agg(Cursor cursor, String label, AggregateStats s) {
        if (s == null) {
            cursor.line(label + ": no data");
            return;
        }
        cursor.line(label + ": count=" + s.count() + " achievedOpsPerSec=" + String.format("%.1f", s.achievedOpsPerSec())
                + " min=" + s.minMicros() + "us p50=" + s.p50Micros() + "us p95=" + s.p95Micros() + "us p99=" + s.p99Micros()
                + "us p999=" + s.p999Micros() + "us max=" + s.maxMicros() + "us errors=" + s.errorTotal());
    }

    /** Handles PDF pagination for a simple top-down text report. */
    private static class Cursor {
        private final PDDocument document;
        private PDPage page;
        private PDPageContentStream stream;
        private float y;

        Cursor(PDDocument document) throws IOException {
            this.document = document;
            newPage();
        }

        private void newPage() throws IOException {
            if (stream != null) {
                stream.close();
            }
            page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            y = page.getMediaBox().getHeight() - MARGIN;
        }

        void title(String text) {
            try {
                ensureSpace();
                stream.beginText();
                stream.setFont(PDType1Font.HELVETICA_BOLD, TITLE_FONT_SIZE);
                stream.newLineAtOffset(MARGIN, y);
                stream.showText(sanitize(text));
                stream.endText();
                y -= LEADING + 4;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        void line(String text) {
            try {
                ensureSpace();
                stream.beginText();
                stream.setFont(PDType1Font.HELVETICA, FONT_SIZE);
                stream.newLineAtOffset(MARGIN, y);
                stream.showText(sanitize(text));
                stream.endText();
                y -= LEADING;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        void blank() {
            y -= LEADING / 2;
        }

        private void ensureSpace() throws IOException {
            if (y < MARGIN + LEADING) {
                newPage();
            }
        }

        private String sanitize(String text) {
            if (text == null) {
                return "";
            }
            // WinAnsiEncoding (Standard 14 fonts) can't render arbitrary unicode; strip anything outside it.
            StringBuilder sb = new StringBuilder(text.length());
            for (char c : text.toCharArray()) {
                sb.append(c < 256 ? c : '?');
            }
            return sb.toString();
        }

        void close() {
            try {
                stream.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
