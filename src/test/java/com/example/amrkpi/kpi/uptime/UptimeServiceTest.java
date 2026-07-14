package com.example.amrkpi.kpi.uptime;

import com.example.amrkpi.metrics.ErrorCategory;
import com.example.amrkpi.persistence.entity.EventCategory;
import com.example.amrkpi.persistence.entity.RawEvent;
import com.example.amrkpi.persistence.repository.RawEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * UptimeService's outage-timeline reconstruction is pure state-machine logic over a sorted event
 * list — exactly the kind of thing worth pinning down with explicit scenarios (unresolved
 * outage at the window edge, back-to-back separate outages) rather than only the one happy path
 * the integration test covers.
 */
@ExtendWith(MockitoExtension.class)
class UptimeServiceTest {

    @Mock
    private RawEventRepository repository;

    private UptimeService service;

    @BeforeEach
    void setUp() {
        service = new UptimeService(repository);
    }

    private RawEvent event(Instant timestamp, String outcome, ErrorCategory errorCategory) {
        RawEvent e = new RawEvent();
        e.setRunId("background");
        e.setCategory(EventCategory.UPTIME_PROBE);
        e.setRegion("canada-central");
        e.setTimestamp(timestamp);
        e.setOutcome(outcome);
        e.setErrorCategory(errorCategory);
        return e;
    }

    @Test
    void noEventsMeansFullUptimeAndNoOutages() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-01-01T01:00:00Z");
        when(repository.findByCategoryAndRegionAndTimestampBetweenOrderByTimestampAsc(
                EventCategory.UPTIME_PROBE, "canada-central", from, to)).thenReturn(List.of());

        UptimeReport report = service.report("canada-central", from, to);

        assertThat(report.uptimePercent()).isEqualTo(100.0);
        assertThat(report.outages()).isEmpty();
        assertThat(report.probeCount()).isZero();
    }

    @Test
    void allUpEventsMeansFullUptime() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-01-01T00:01:00Z");
        List<RawEvent> events = List.of(
                event(from.plusSeconds(5), "UP", null),
                event(from.plusSeconds(10), "UP", null),
                event(from.plusSeconds(15), "UP", null)
        );
        when(repository.findByCategoryAndRegionAndTimestampBetweenOrderByTimestampAsc(
                EventCategory.UPTIME_PROBE, "canada-central", from, to)).thenReturn(events);

        UptimeReport report = service.report("canada-central", from, to);

        assertThat(report.uptimePercent()).isEqualTo(100.0);
        assertThat(report.outages()).isEmpty();
    }

    @Test
    void outageThatResolvesBeforeWindowEndProducesOneClosedOutage() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-01-01T00:01:00Z");
        Instant downAt = from.plusSeconds(10);
        Instant upAt = from.plusSeconds(20);
        List<RawEvent> events = List.of(
                event(downAt, "DOWN", ErrorCategory.CONNECT_TIMEOUT),
                event(upAt, "UP", null)
        );
        when(repository.findByCategoryAndRegionAndTimestampBetweenOrderByTimestampAsc(
                EventCategory.UPTIME_PROBE, "canada-central", from, to)).thenReturn(events);

        UptimeReport report = service.report("canada-central", from, to);

        assertThat(report.outages()).hasSize(1);
        UptimeReport.Outage outage = report.outages().get(0);
        assertThat(outage.start()).isEqualTo(downAt);
        assertThat(outage.end()).isEqualTo(upAt);
        assertThat(outage.durationMillis()).isEqualTo(10_000);
        assertThat(outage.errorCategory()).isEqualTo("CONNECT_TIMEOUT");
        assertThat(report.uptimePercent()).isLessThan(100.0);
    }

    @Test
    void unresolvedOutageExtendsToWindowEnd() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-01-01T00:01:00Z");
        Instant downAt = from.plusSeconds(50);
        List<RawEvent> events = List.of(
                event(downAt, "DOWN", ErrorCategory.SOCKET_READ_TIMEOUT)
                // no closing UP event within the window
        );
        when(repository.findByCategoryAndRegionAndTimestampBetweenOrderByTimestampAsc(
                EventCategory.UPTIME_PROBE, "canada-central", from, to)).thenReturn(events);

        UptimeReport report = service.report("canada-central", from, to);

        assertThat(report.outages()).hasSize(1);
        UptimeReport.Outage outage = report.outages().get(0);
        assertThat(outage.start()).isEqualTo(downAt);
        assertThat(outage.end()).isEqualTo(to);
    }

    @Test
    void twoSeparateOutagesAreNotMergedIntoOne() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-01-01T00:02:00Z");
        List<RawEvent> events = List.of(
                event(from.plusSeconds(10), "DOWN", ErrorCategory.CONNECT_TIMEOUT),
                event(from.plusSeconds(15), "UP", null),
                event(from.plusSeconds(60), "DOWN", ErrorCategory.AUTH_FAILURE),
                event(from.plusSeconds(65), "UP", null)
        );
        when(repository.findByCategoryAndRegionAndTimestampBetweenOrderByTimestampAsc(
                EventCategory.UPTIME_PROBE, "canada-central", from, to)).thenReturn(events);

        UptimeReport report = service.report("canada-central", from, to);

        assertThat(report.outages()).hasSize(2);
        assertThat(report.outages().get(0).errorCategory()).isEqualTo("CONNECT_TIMEOUT");
        assertThat(report.outages().get(1).errorCategory()).isEqualTo("AUTH_FAILURE");
    }
}
