package com.example.amrkpi.report;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * GET /reports/export?format=pdf|xlsx|csv&from=&to=&runId= — one shared query layer
 * (ReportQueryService) feeds all three formats. The {@code kpis} filter param from the spec is
 * accepted for forward-compatibility but not yet applied — this version always exports the full
 * report; scoping it down to a KPI subset is a natural follow-up if needed.
 */
@RestController
public class ReportExportController {

    private final ReportQueryService queryService;
    private final CsvReportWriter csvWriter;
    private final XlsxReportWriter xlsxWriter;
    private final PdfReportWriter pdfWriter;

    public ReportExportController(ReportQueryService queryService, CsvReportWriter csvWriter,
                                   XlsxReportWriter xlsxWriter, PdfReportWriter pdfWriter) {
        this.queryService = queryService;
        this.csvWriter = csvWriter;
        this.xlsxWriter = xlsxWriter;
        this.pdfWriter = pdfWriter;
    }

    /**
     * Exports a point-in-time report — run configuration block, summary stats per KPI, and (for
     * CSV/XLSX) full tabular detail — as a downloadable file.
     *
     * @param format  {@code csv} (default), {@code xlsx}, or {@code pdf}
     * @param runId   optional run ID; when present, includes run-scoped KPI 3 throughput data and
     *                the run's persisted effective configuration
     * @param kpis    accepted but not yet applied — see the class-level note above
     * @param from    window start; defaults to one hour before {@code to}
     * @param to      window end; defaults to now
     * @return the rendered report as an attachment with the appropriate content type
     */
    @GetMapping("/reports/export")
    public ResponseEntity<byte[]> export(
            @RequestParam(defaultValue = "csv") String format,
            @RequestParam(required = false) String runId,
            @RequestParam(required = false) String kpis,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {

        Instant effectiveTo = to != null ? to : Instant.now();
        Instant effectiveFrom = from != null ? from : effectiveTo.minus(1, ChronoUnit.HOURS);
        ReportData data = queryService.build(runId, effectiveFrom, effectiveTo);

        byte[] body;
        MediaType contentType;
        String filename;
        switch (format.toLowerCase()) {
            case "xlsx" -> {
                body = xlsxWriter.write(data);
                contentType = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
                filename = "amr-kpi-report.xlsx";
            }
            case "pdf" -> {
                body = pdfWriter.write(data);
                contentType = MediaType.APPLICATION_PDF;
                filename = "amr-kpi-report.pdf";
            }
            case "csv" -> {
                body = csvWriter.write(data);
                contentType = MediaType.parseMediaType("text/csv");
                filename = "amr-kpi-report.csv";
            }
            default -> throw new IllegalArgumentException("Unsupported format: " + format + " (use csv, xlsx, or pdf)");
        }

        return ResponseEntity.ok()
                .contentType(contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .body(body);
    }
}
