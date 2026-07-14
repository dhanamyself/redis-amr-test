package com.example.amrkpi.redis;

import com.example.amrkpi.config.AmrProperties;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Endpoint;
import redis.clients.jedis.HostAndPort;

/**
 * Resolves the two AMR endpoints (priority-1 local, priority-2 failover) from config, and gives
 * every other component in the app a single place to translate between the logical region name
 * (as configured in application.yml, e.g. "canada-central") and the Jedis {@link Endpoint}
 * objects used by the client/provider APIs.
 */
@Component
public class AmrEndpoints {

    private final HostAndPort local;
    private final HostAndPort failover;
    private final String localName;
    private final String failoverName;

    public AmrEndpoints(AmrProperties props) {
        AmrProperties.Endpoint localCfg = props.getEndpoints().getLocal();
        AmrProperties.Endpoint failoverCfg = props.getEndpoints().getFailover();
        this.local = new HostAndPort(localCfg.getHost(), localCfg.getPort());
        this.failover = new HostAndPort(failoverCfg.getHost(), failoverCfg.getPort());
        this.localName = localCfg.getName();
        this.failoverName = failoverCfg.getName();
    }

    public HostAndPort local() {
        return local;
    }

    public HostAndPort failover() {
        return failover;
    }

    public String localName() {
        return localName;
    }

    public String failoverName() {
        return failoverName;
    }

    /** Best-effort reverse lookup used purely for tagging metrics/events with a human region name. */
    public String nameOf(Endpoint endpoint) {
        if (endpoint == null) {
            return "unknown";
        }
        if (local.equals(endpoint)) {
            return localName;
        }
        if (failover.equals(endpoint)) {
            return failoverName;
        }
        return endpoint.toString();
    }

    public HostAndPort byName(String regionName) {
        if (localName.equals(regionName)) {
            return local;
        }
        if (failoverName.equals(regionName)) {
            return failover;
        }
        throw new IllegalArgumentException("Unknown region name: " + regionName);
    }
}
