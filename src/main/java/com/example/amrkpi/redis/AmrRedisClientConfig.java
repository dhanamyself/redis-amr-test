package com.example.amrkpi.redis;

import com.example.amrkpi.config.AmrProperties;
import com.example.amrkpi.metrics.EventRecorder;
import com.example.amrkpi.persistence.entity.EventCategory;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.Connection;
import redis.clients.jedis.ConnectionPoolConfig;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.Endpoint;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.MultiDbClient;
import redis.clients.jedis.MultiDbConfig;
import redis.clients.jedis.SslOptions;
import redis.clients.jedis.authentication.AuthXManager;
import redis.clients.jedis.mcf.DatabaseSwitchEvent;
import redis.clients.jedis.mcf.HealthCheckStrategy;
import redis.clients.jedis.mcf.MultiDbConnectionProvider;
import redis.clients.jedis.mcf.PingStrategy;

import java.time.Instant;
import java.util.Map;

/**
 * Wires the AMR active-active geo-replication group as a single {@link MultiDbClient}: two
 * weighted endpoints (Canada Central priority 1, Canada East priority 2), Resilience4j circuit
 * breaker + retry (via MultiDbConfig — Jedis does not bundle resilience4j itself, see pom.xml),
 * health checks, and failback. All of it externalized through {@link AmrProperties} and stamped
 * into run metadata elsewhere.
 * <p>
 * Do NOT hand-roll a parallel failover mechanism here — this configures and instruments the
 * client library's built-in one (weighted endpoints, breaker, health checks, failover/failback
 * callbacks), per the build spec.
 * <p>
 * The {@link MultiDbConnectionProvider} is built and retained explicitly (rather than left for
 * {@code MultiDbClient.builder()} to construct internally) purely so the app can keep a handle to
 * it: {@code MultiDbConnectionProvider.getDatabase(Endpoint)} is the only path to the real
 * Resilience4j {@link CircuitBreaker} per endpoint (KPI 5) and to {@code Database.setDisabled()},
 * the mechanism KPI 4's controlled failure simulation uses.
 */
@Configuration
public class AmrRedisClientConfig {

    private static final Logger log = LoggerFactory.getLogger(AmrRedisClientConfig.class);

    @Bean
    public JedisClientConfig amrJedisClientConfig(AmrProperties props, AuthXManager authXManager) {
        DefaultJedisClientConfig.Builder builder = DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis(props.getSocket().getConnectionTimeoutMillis())
                .socketTimeoutMillis(props.getSocket().getSocketTimeoutMillis())
                .authXManager(authXManager);
        // TLS must stay on everywhere, including load-test paths; SslOptions.defaults() uses the
        // JVM's default trust manager, so certificate validation is never disabled here.
        if (props.getTls().isEnabled()) {
            builder.sslOptions(SslOptions.defaults());
        }
        return builder.build();
    }

    @Bean
    public GenericObjectPoolConfig<Connection> amrConnectionPoolConfig(AmrProperties props) {
        ConnectionPoolConfig poolConfig = new ConnectionPoolConfig();
        poolConfig.setMaxTotal(props.getPool().getMaxTotal());
        poolConfig.setMaxIdle(props.getPool().getMaxIdle());
        poolConfig.setMinIdle(props.getPool().getMinIdle());
        poolConfig.setMaxWait(java.time.Duration.ofMillis(props.getPool().getMaxWaitMillis()));
        return poolConfig;
    }

    @Bean
    public MultiDbConnectionProvider multiDbConnectionProvider(AmrProperties props,
                                                                 AmrEndpoints endpoints,
                                                                 JedisClientConfig clientConfig,
                                                                 GenericObjectPoolConfig<Connection> poolConfig,
                                                                 EventRecorder eventRecorder,
                                                                 FailoverSimulationState simulationState) {

        HealthCheckStrategy.Config healthCheckConfig = HealthCheckStrategy.Config.builder()
                .interval((int) props.getHealthCheck().getIntervalMillis())
                .timeout((int) props.getHealthCheck().getTimeoutMillis())
                .numProbes(props.getHealthCheck().getNumProbes())
                .build();

        MultiDbConfig.DatabaseConfig localDb = MultiDbConfig.DatabaseConfig
                .builder(endpoints.local(), clientConfig)
                .connectionPoolConfig(poolConfig)
                .weight(props.getEndpoints().getLocal().getWeight())
                .healthCheckStrategySupplier((hostAndPort, cfg) -> new PingStrategy(hostAndPort, cfg, healthCheckConfig))
                .build();

        MultiDbConfig.DatabaseConfig failoverDb = MultiDbConfig.DatabaseConfig
                .builder(endpoints.failover(), clientConfig)
                .connectionPoolConfig(poolConfig)
                .weight(props.getEndpoints().getFailover().getWeight())
                .healthCheckStrategySupplier((hostAndPort, cfg) -> new PingStrategy(hostAndPort, cfg, healthCheckConfig))
                .build();

        MultiDbConfig.RetryConfig retryConfig = MultiDbConfig.RetryConfig.builder()
                .maxAttempts(props.getRetry().getMaxAttempts())
                .waitDuration(props.getRetry().getWaitDurationMillis())
                .exponentialBackoffMultiplier(props.getRetry().getExponentialBackoffMultiplier())
                .build();

        MultiDbConfig.CircuitBreakerConfig circuitBreakerConfig = MultiDbConfig.CircuitBreakerConfig.builder()
                .failureRateThreshold(props.getCircuitBreaker().getFailureRateThreshold())
                .slidingWindowSize(props.getCircuitBreaker().getSlidingWindowSize())
                .minNumOfFailures(props.getCircuitBreaker().getMinimumNumberOfCalls())
                .build();

        MultiDbConfig multiDbConfig = MultiDbConfig.builder()
                .database(localDb)
                .database(failoverDb)
                .commandRetry(retryConfig)
                .failureDetector(circuitBreakerConfig)
                .failbackSupported(props.getFailback().isSupported())
                .gracePeriod(props.getFailback().getGracePeriodMillis())
                .failbackCheckInterval(props.getFailback().getCheckIntervalMillis())
                .build();

        MultiDbConnectionProvider provider = new MultiDbConnectionProvider(multiDbConfig);

        provider.setDatabaseSwitchListener(event ->
                onDatabaseSwitch(event, endpoints, eventRecorder, simulationState));

        registerBreakerTransitionListener(provider, endpoints.local(), endpoints.localName(), eventRecorder, simulationState);
        registerBreakerTransitionListener(provider, endpoints.failover(), endpoints.failoverName(), eventRecorder, simulationState);

        return provider;
    }

    @Bean
    public MultiDbClient amrRedisClient(MultiDbConnectionProvider multiDbConnectionProvider) {
        return MultiDbClient.builder()
                .connectionProvider(multiDbConnectionProvider)
                .build();
    }

    private void onDatabaseSwitch(DatabaseSwitchEvent event, AmrEndpoints endpoints,
                                   EventRecorder eventRecorder, FailoverSimulationState simulationState) {
        // DatabaseSwitchEvent carries no timestamp of its own (verified against Jedis source) —
        // we stamp it ourselves the instant the listener fires.
        Instant now = Instant.now();
        String regionName = endpoints.nameOf(event.getEndpoint());
        log.info("AMR database switch: reason={} newActiveEndpoint={} ({})", event.getReason(), event.getEndpoint(), regionName);

        eventRecorder.record(
                simulationState.currentRunIdOrBackground(),
                EventCategory.FAILOVER_TRANSITION,
                regionName,
                "SWITCHED_ACTIVE",
                null,
                null,
                Map.of(
                        "reason", event.getReason().name(),
                        "databaseName", event.getDatabaseName(),
                        "newActiveEndpoint", String.valueOf(event.getEndpoint()),
                        "detectedAt", now.toString()
                ));
    }

    private void registerBreakerTransitionListener(MultiDbConnectionProvider provider, Endpoint endpoint,
                                                     String regionName, EventRecorder eventRecorder,
                                                     FailoverSimulationState simulationState) {
        MultiDbConnectionProvider.Database database = provider.getDatabase(endpoint);
        CircuitBreaker breaker = database.getCircuitBreaker();
        breaker.getEventPublisher().onStateTransition(transitionEvent -> {
            CircuitBreaker.StateTransition transition = transitionEvent.getStateTransition();
            log.info("AMR circuit breaker [{}] transitioned {} -> {}", regionName,
                    transition.getFromState(), transition.getToState());
            eventRecorder.record(
                    simulationState.currentRunIdOrBackground(),
                    EventCategory.BREAKER_STATE_TRANSITION,
                    regionName,
                    transition.getToState().name(),
                    null,
                    null,
                    Map.of(
                            "fromState", transition.getFromState().name(),
                            "toState", transition.getToState().name()
                    ));
        });
    }
}
