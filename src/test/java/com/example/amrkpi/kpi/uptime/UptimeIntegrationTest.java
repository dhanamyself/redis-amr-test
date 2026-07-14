package com.example.amrkpi.kpi.uptime;

import com.example.amrkpi.metrics.ErrorCategory;
import com.example.amrkpi.metrics.EventRecorder;
import com.example.amrkpi.persistence.entity.EventCategory;
import com.example.amrkpi.persistence.entity.Run;
import com.example.amrkpi.redis.AmrEndpoints;
import com.example.amrkpi.redis.ProbeConnectionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.util.UriComponentsBuilder;
import redis.clients.jedis.MultiDbClient;
import redis.clients.jedis.authentication.AuthXManager;
import redis.clients.jedis.mcf.MultiDbConnectionProvider;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full application-context integration test through the real REST layer, real JPA/H2 repository,
 * and real service/aggregation logic (UptimeController -> UptimeService -> RawEventRepository).
 * <p>
 * The four beans that would otherwise perform real network I/O against Azure/AMR at context
 * startup (AuthXManager.start() blocks on a token fetch; MultiDbConnectionProvider and
 * ProbeConnectionFactory.ProbeClients open real connection pools) are mocked, since this
 * environment has neither Azure credentials nor a reachable AMR instance — see the "AuthXManager
 * failed to start!" fail-fast behavior documented in the README. Probe/rollup intervals are
 * pushed out to 10 minutes so no scheduled task fires against the mocks during the test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:uptime-integration-test;DB_CLOSE_DELAY=-1",
        "amr.endpoints.local.host=127.0.0.1",
        "amr.endpoints.failover.host=127.0.0.1",
        "amrkpi.probes.uptime-interval-seconds=600",
        "amrkpi.probes.network-time-interval-millis=600000",
        "amrkpi.probes.geo-replication-interval-seconds=600"
})
class UptimeIntegrationTest {

    @MockitoBean
    private AuthXManager authXManager;

    @MockitoBean
    private MultiDbConnectionProvider multiDbConnectionProvider;

    @MockitoBean
    private MultiDbClient amrRedisClient;

    @MockitoBean
    private ProbeConnectionFactory.ProbeClients probeClients;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private EventRecorder eventRecorder;

    @Autowired
    private AmrEndpoints endpoints;

    @Test
    void reportsFullUptimeWhenNoProbeSamplesExist() {
        String region = endpoints.localName() + "-untouched";

        ResponseEntity<UptimeReport> response = restTemplate.getForEntity(
                "/kpi/uptime?region=" + region, UptimeReport.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().uptimePercent()).isEqualTo(100.0);
        assertThat(response.getBody().outages()).isEmpty();
    }

    @Test
    void reconstructsOutageWindowFromPersistedDownProbe() throws InterruptedException {
        String region = endpoints.localName();
        // The scheduled probe fires once immediately at context startup (Spring's fixedRate
        // semantics), against the unstubbed ProbeClients mock — that produces its own DOWN
        // event before this test ever runs. Scope the query window to start after it so this
        // test only sees the two events it records itself.
        Instant from = Instant.now();

        eventRecorder.record(Run.BACKGROUND_RUN_ID, EventCategory.UPTIME_PROBE, region,
                "DOWN", 50L, ErrorCategory.CONNECT_TIMEOUT, Map.of());
        // Uptime is time-weighted between consecutive probe timestamps (millisecond precision) —
        // a real gap is needed here or the DOWN interval truncates to 0ms and this test would
        // pass for the wrong reason regardless of whether the outage math is actually correct.
        Thread.sleep(50);
        eventRecorder.record(Run.BACKGROUND_RUN_ID, EventCategory.UPTIME_PROBE, region,
                "UP", 5L, null, Map.of());

        String url = UriComponentsBuilder.fromPath("/kpi/uptime")
                .queryParam("region", region)
                .queryParam("from", from)
                .toUriString();
        ResponseEntity<UptimeReport> response = restTemplate.getForEntity(url, UptimeReport.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        UptimeReport report = response.getBody();
        assertThat(report).isNotNull();
        assertThat(report.probeCount()).isEqualTo(2);
        assertThat(report.outages()).hasSize(1);
        assertThat(report.outages().get(0).errorCategory()).isEqualTo("CONNECT_TIMEOUT");
        assertThat(report.uptimePercent()).isLessThan(100.0);
    }
}
