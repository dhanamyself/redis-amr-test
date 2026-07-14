package com.example.amrkpi.redis;

import com.example.amrkpi.config.AmrProperties;
import jakarta.annotation.PreDestroy;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.Connection;
import redis.clients.jedis.ConnectionPoolConfig;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.RedisClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * KPI probe traffic (uptime, network-time, geo-replication probes) runs on dedicated
 * connections/pools, never through the load generator's pool — otherwise probes queue behind
 * load traffic and measure pool contention instead of AMR (Metrics architecture: probe
 * isolation). Each region gets its own small single-endpoint pooled client, independent of the
 * MultiDbClient used by the load generator and cache-aside path.
 */
@Configuration
public class ProbeConnectionFactory {

    private static final int PROBE_POOL_MAX_TOTAL = 4;

    private Map<String, RedisClient> clients;

    @Bean
    public ProbeClients probeClients(AmrProperties props, AmrEndpoints endpoints, JedisClientConfig amrJedisClientConfig) {
        GenericObjectPoolConfig<Connection> poolConfig = new ConnectionPoolConfig();
        poolConfig.setMaxTotal(PROBE_POOL_MAX_TOTAL);
        poolConfig.setMaxIdle(PROBE_POOL_MAX_TOTAL);
        poolConfig.setMinIdle(1);
        poolConfig.setMaxWait(Duration.ofMillis(props.getPool().getMaxWaitMillis()));

        Map<String, RedisClient> map = new LinkedHashMap<>();
        map.put(endpoints.localName(), RedisClient.builder().hostAndPort(endpoints.local())
                .clientConfig(amrJedisClientConfig).poolConfig(poolConfig).build());
        map.put(endpoints.failoverName(), RedisClient.builder().hostAndPort(endpoints.failover())
                .clientConfig(amrJedisClientConfig).poolConfig(poolConfig).build());
        this.clients = map;
        return new ProbeClients(map);
    }

    @PreDestroy
    public void shutdown() {
        if (clients != null) {
            clients.values().forEach(RedisClient::close);
        }
    }

    /** Read-only view handed out to KPI probe services. */
    public record ProbeClients(Map<String, RedisClient> byRegion) {
        public RedisClient get(String regionName) {
            RedisClient client = byRegion.get(regionName);
            if (client == null) {
                throw new IllegalArgumentException("No probe client for region: " + regionName);
            }
            return client;
        }
    }
}
