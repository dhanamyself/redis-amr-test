package com.example.amrkpi.kpi.uptime;

import com.example.amrkpi.persistence.entity.EventCategory;
import com.example.amrkpi.persistence.entity.RawEvent;
import com.example.amrkpi.persistence.repository.RawEventRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Reconstructs uptime % and the outage timeline from raw UPTIME_PROBE events. Uptime is
 * time-weighted (duration between consecutive probes), not a naive count ratio, since probes are
 * evenly spaced but a report window's edges rarely align with a probe tick.
 */
@Service
public class UptimeService {

    private final RawEventRepository repository;

    public UptimeService(RawEventRepository repository) {
        this.repository = repository;
    }

    public UptimeReport report(String region, Instant from, Instant to) {
        List<RawEvent> events = repository.findByCategoryAndRegionAndTimestampBetweenOrderByTimestampAsc(
                EventCategory.UPTIME_PROBE, region, from, to);

        if (events.isEmpty()) {
            return new UptimeReport(region, from, to, 100.0, 0, List.of());
        }

        long upMillis = 0;
        long downMillis = 0;
        List<UptimeReport.Outage> outages = new ArrayList<>();

        Instant outageStart = null;
        String outageErrorCategory = null;

        for (int i = 0; i < events.size(); i++) {
            RawEvent current = events.get(i);
            Instant intervalEnd = (i + 1 < events.size()) ? events.get(i + 1).getTimestamp() : to;
            long intervalMillis = Math.max(Duration.between(current.getTimestamp(), intervalEnd).toMillis(), 0);

            boolean isUp = "UP".equals(current.getOutcome());
            if (isUp) {
                upMillis += intervalMillis;
                if (outageStart != null) {
                    outages.add(new UptimeReport.Outage(outageStart, current.getTimestamp(),
                            Duration.between(outageStart, current.getTimestamp()).toMillis(), outageErrorCategory));
                    outageStart = null;
                    outageErrorCategory = null;
                }
            } else {
                downMillis += intervalMillis;
                if (outageStart == null) {
                    outageStart = current.getTimestamp();
                    outageErrorCategory = current.getErrorCategory() != null ? current.getErrorCategory().name() : null;
                }
            }
        }

        if (outageStart != null) {
            outages.add(new UptimeReport.Outage(outageStart, to, Duration.between(outageStart, to).toMillis(), outageErrorCategory));
        }

        long totalMillis = upMillis + downMillis;
        double uptimePercent = totalMillis == 0 ? 100.0 : (upMillis * 100.0) / totalMillis;

        return new UptimeReport(region, from, to, uptimePercent, events.size(), outages);
    }
}
