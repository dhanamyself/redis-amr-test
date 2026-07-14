package com.example.amrkpi.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Full effective AMR connection/auth/resilience configuration. Every field here is stamped
 * into a run's persisted metadata (see RunConfigSnapshot) so results stay reproducible.
 */
@Data
@ConfigurationProperties(prefix = "amr")
public class AmrProperties {

    @NestedConfigurationProperty
    private Endpoints endpoints = new Endpoints();

    private Tls tls = new Tls();

    /** "enterprise" (single endpoint, UnifiedJedis/MultiDbClient) or "oss-cluster" (cluster-aware client required). */
    private String clusteringPolicy = "enterprise";

    private Auth auth = new Auth();
    private Pool pool = new Pool();
    private Socket socket = new Socket();
    private Retry retry = new Retry();
    private CircuitBreaker circuitBreaker = new CircuitBreaker();
    private HealthCheck healthCheck = new HealthCheck();
    private Failback failback = new Failback();

    @Data
    public static class Endpoints {
        private Endpoint local = new Endpoint();
        private Endpoint failover = new Endpoint();
    }

    @Data
    public static class Endpoint {
        private String name;
        private String host;
        private int port = 10000;
        private float weight = 1.0f;
        private int priority = 1;
    }

    @Data
    public static class Tls {
        private boolean enabled = true;
    }

    @Data
    public static class Auth {
        private String tokenScope = "https://redis.azure.com/.default";
        private int tokenRequestTimeoutMs = 2000;
    }

    @Data
    public static class Pool {
        private int maxTotal = 64;
        private int maxIdle = 64;
        private int minIdle = 4;
        private int maxWaitMillis = 500;
    }

    @Data
    public static class Socket {
        private int connectionTimeoutMillis = 2000;
        private int socketTimeoutMillis = 1000;
        private boolean tcpKeepAlive = true;
    }

    @Data
    public static class Retry {
        private int maxAttempts = 3;
        private int waitDurationMillis = 500;
        private int exponentialBackoffMultiplier = 2;
    }

    @Data
    public static class CircuitBreaker {
        private float failureRateThreshold = 10.0f;
        private int slidingWindowSize = 10;
        private int minimumNumberOfCalls = 20;
        private long waitDurationInOpenStateMillis = 60000;
    }

    @Data
    public static class HealthCheck {
        private long intervalMillis = 1000;
        private long timeoutMillis = 1000;
        private int numProbes = 3;
    }

    @Data
    public static class Failback {
        private boolean supported = true;
        private long gracePeriodMillis = 60000;
        private long checkIntervalMillis = 120000;
    }
}
