package com.example.amrkpi.report;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

@Component
public class XlsxReportWriter {

    public byte[] write(ReportData data) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle boldStyle = boldStyle(workbook);

            writeSummarySheet(workbook, boldStyle, data);
            writeStatsSheet(workbook, boldStyle, "Uptime & Breaker", data);
            // Excel sheet names are capped at 31 characters — POI silently truncates (mid-word)
            // past that instead of throwing, so these are kept short enough to avoid it.
            writeStatsSheet(workbook, boldStyle, "Latency (Geo-Net-Throughput)", data);
            writeStatsSheet(workbook, boldStyle, "CacheAside, Consistency, Token", data);

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void writeSummarySheet(XSSFWorkbook wb, CellStyle bold, ReportData data) {
        Sheet sheet = wb.createSheet("Run Summary");
        int r = 0;
        r = kv(sheet, bold, r, "Run ID", data.runId());
        r = kv(sheet, bold, r, "Run Type", data.runType());
        r = kv(sheet, bold, r, "Run Status", data.runStatus());
        r = kv(sheet, bold, r, "Window From", String.valueOf(data.from()));
        r = kv(sheet, bold, r, "Window To", String.valueOf(data.to()));
        r++;
        Row header = sheet.createRow(r++);
        header(header, bold, "Region", "Breaker State");
        for (var e : data.breakerStates().entrySet()) {
            Row row = sheet.createRow(r++);
            row.createCell(0).setCellValue(e.getKey());
            row.createCell(1).setCellValue(e.getValue());
        }
        r++;
        r = kv(sheet, bold, r, "Effective Run Config (JSON)", data.runConfigJson());
        autosize(sheet, 2);
    }

    private void writeStatsSheet(XSSFWorkbook wb, CellStyle bold, String title, ReportData data) {
        Sheet sheet = wb.createSheet(title);
        int r = 0;

        if (title.startsWith("Uptime")) {
            r = statsHeaderRow(sheet, bold, r, "Uptime", "region", "uptimePercent", "probeCount", "outageCount");
            r = uptimeRow(sheet, r, data.uptimeLocal());
            r = uptimeRow(sheet, r, data.uptimeFailover());
            r++;
            r = statsHeaderRow(sheet, bold, r, "Breaker Time-in-State", "region", "currentState", "states...");
            r = breakerRow(sheet, r, data.breakerTimeInStateLocal());
            r = breakerRow(sheet, r, data.breakerTimeInStateFailover());
        } else if (title.startsWith("Latency")) {
            r = statsHeaderRow(sheet, bold, r, "Geo-Replication Lag (ms)", "direction", "count", "min", "p50", "p95", "p99", "max", "timeouts");
            r = geoRow(sheet, r, data.geoLocalToFailover());
            r = geoRow(sheet, r, data.geoFailoverToLocal());
            r++;
            r = statsHeaderRow(sheet, bold, r, "Network Time (us)", "region", "count", "achievedOpsPerSec", "min", "p50", "p95", "p99", "p999", "max", "errors");
            r = aggRow(sheet, r, "local", data.networkTimeLocal());
            r = aggRow(sheet, r, "failover", data.networkTimeFailover());
            if (data.throughput() != null) {
                r++;
                r = statsHeaderRow(sheet, bold, r, "Throughput (target=" + data.throughput().targetOpsPerSec()
                        + " achieved=" + String.format("%.1f", data.throughput().achievedOpsPerSec()) + ")",
                        "scope", "count", "achievedOpsPerSec", "min", "p50", "p95", "p99", "p999", "max", "errors");
                r = aggRow(sheet, r, "overall", data.throughput().overall());
                for (var e : data.throughput().byOperation().entrySet()) {
                    r = aggRow(sheet, r, "op:" + e.getKey(), e.getValue());
                }
                for (var e : data.throughput().byRegion().entrySet()) {
                    r = aggRow(sheet, r, "region:" + e.getKey(), e.getValue());
                }
            }
        } else {
            var ca = data.cacheAside();
            r = statsHeaderRow(sheet, bold, r, "Cache-Aside (missRatio=" + String.format("%.1f%%", ca.missRatio()) + ")",
                    "scope", "count", "achievedOpsPerSec", "min", "p50", "p95", "p99", "p999", "max", "errors");
            r = aggRow(sheet, r, "hit", ca.hitLatency());
            r = aggRow(sheet, r, "miss", ca.missLatency());
            r = aggRow(sheet, r, "degraded", ca.degradedLatency());
            r++;

            var c = data.consistency();
            r = statsHeaderRow(sheet, bold, r, "Consistency", "metric", "value");
            r = kv(sheet, null, r, "stalenessProbeCount", String.valueOf(c.stalenessProbeCount()));
            r = kv(sheet, null, r, "staleAtT0Count", String.valueOf(c.staleAtT0Count()));
            r = kv(sheet, null, r, "staleWindowMillis p50/p95/p99/max", c.staleWindowMillis().p50() + "/" + c.staleWindowMillis().p95() + "/" + c.staleWindowMillis().p99() + "/" + c.staleWindowMillis().max());
            r = kv(sheet, null, r, "conflictProbeCount", String.valueOf(c.conflictProbeCount()));
            r = kv(sheet, null, r, "convergedCount", String.valueOf(c.convergedCount()));
            r = kv(sheet, null, r, "divergedCount", String.valueOf(c.divergedCount()));
            r = kv(sheet, null, r, "convergenceTimeMillis p50/p95/p99/max", c.convergenceTimeMillis().p50() + "/" + c.convergenceTimeMillis().p95() + "/" + c.convergenceTimeMillis().p99() + "/" + c.convergenceTimeMillis().max());
            r++;

            var tl = data.tokenLifecycle();
            r = statsHeaderRow(sheet, bold, r, "Token Lifecycle", "renewedAt", "outcome", "user", "expiresAt", "ttlMillis");
            for (var renewal : tl.renewals()) {
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(String.valueOf(renewal.renewedAt()));
                row.createCell(1).setCellValue(renewal.outcome());
                row.createCell(2).setCellValue(String.valueOf(renewal.user()));
                row.createCell(3).setCellValue(String.valueOf(renewal.tokenExpiresAt()));
                row.createCell(4).setCellValue(String.valueOf(renewal.tokenTtlMillis()));
            }
        }
        autosize(sheet, 10);
    }

    private int kv(Sheet sheet, CellStyle bold, int r, String key, String value) {
        Row row = sheet.createRow(r);
        Cell k = row.createCell(0);
        k.setCellValue(key);
        if (bold != null) {
            k.setCellStyle(bold);
        }
        row.createCell(1).setCellValue(value == null ? "" : value);
        return r + 1;
    }

    private int statsHeaderRow(Sheet sheet, CellStyle bold, int r, String title, String... columns) {
        Row titleRow = sheet.createRow(r++);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue(title);
        titleCell.setCellStyle(bold);
        Row header = sheet.createRow(r++);
        header(header, bold, columns);
        return r;
    }

    private void header(Row row, CellStyle bold, String... columns) {
        for (int i = 0; i < columns.length; i++) {
            Cell c = row.createCell(i);
            c.setCellValue(columns[i]);
            c.setCellStyle(bold);
        }
    }

    private int uptimeRow(Sheet sheet, int r, com.example.amrkpi.kpi.uptime.UptimeReport report) {
        Row row = sheet.createRow(r);
        row.createCell(0).setCellValue(report.region());
        row.createCell(1).setCellValue(report.uptimePercent());
        row.createCell(2).setCellValue(report.probeCount());
        row.createCell(3).setCellValue(report.outages().size());
        return r + 1;
    }

    private int breakerRow(Sheet sheet, int r, com.example.amrkpi.kpi.circuitbreaker.BreakerTimeInStateReport report) {
        Row row = sheet.createRow(r);
        row.createCell(0).setCellValue(report.region());
        row.createCell(1).setCellValue(report.currentState());
        StringBuilder sb = new StringBuilder();
        report.timeInStateMillis().forEach((state, ms) -> sb.append(state).append("=").append(ms).append("ms "));
        row.createCell(2).setCellValue(sb.toString());
        return r + 1;
    }

    private int geoRow(Sheet sheet, int r, com.example.amrkpi.kpi.geolatency.GeoReplicationReport report) {
        Row row = sheet.createRow(r);
        var s = report.latencyMillis();
        row.createCell(0).setCellValue(report.direction());
        row.createCell(1).setCellValue(s.count());
        row.createCell(2).setCellValue(s.min());
        row.createCell(3).setCellValue(s.p50());
        row.createCell(4).setCellValue(s.p95());
        row.createCell(5).setCellValue(s.p99());
        row.createCell(6).setCellValue(s.max());
        row.createCell(7).setCellValue(report.timeoutCount());
        return r + 1;
    }

    private int aggRow(Sheet sheet, int r, String label, AggregateStats s) {
        Row row = sheet.createRow(r);
        row.createCell(0).setCellValue(label);
        if (s != null) {
            row.createCell(1).setCellValue(s.count());
            row.createCell(2).setCellValue(s.achievedOpsPerSec());
            row.createCell(3).setCellValue(s.minMicros());
            row.createCell(4).setCellValue(s.p50Micros());
            row.createCell(5).setCellValue(s.p95Micros());
            row.createCell(6).setCellValue(s.p99Micros());
            row.createCell(7).setCellValue(s.p999Micros());
            row.createCell(8).setCellValue(s.maxMicros());
            row.createCell(9).setCellValue(s.errorTotal());
        }
        return r + 1;
    }

    private CellStyle boldStyle(Workbook wb) {
        Font font = wb.createFont();
        font.setBold(true);
        CellStyle style = wb.createCellStyle();
        style.setFont(font);
        return style;
    }

    private void autosize(Sheet sheet, int columns) {
        for (int i = 0; i < columns; i++) {
            try {
                sheet.autoSizeColumn(i);
            } catch (Exception ignored) {
                // headless font metrics can be unavailable in some container environments
            }
        }
    }
}
