package com.example.amrkpi.redis;

import com.azure.identity.DefaultAzureCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.example.amrkpi.config.AmrProperties;
import com.example.amrkpi.metrics.EventRecorder;
import com.example.amrkpi.persistence.entity.EventCategory;
import com.example.amrkpi.persistence.entity.Run;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.authentication.core.TokenAuthConfig;
import redis.clients.authentication.entraid.AzureTokenAuthConfigBuilder;
import redis.clients.jedis.authentication.AuthXEventListener;
import redis.clients.jedis.authentication.AuthXManager;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Entra ID Workload Identity authentication (KPI 8: token lifecycle).
 * <p>
 * Auth is DefaultAzureCredential, resolving purely from the env vars the AKS workload-identity
 * webhook injects (AZURE_CLIENT_ID, AZURE_TENANT_ID, AZURE_FEDERATED_TOKEN_FILE,
 * AZURE_AUTHORITY_HOST) — no client ID/secret/access key here or anywhere else in this app.
 * <p>
 * One {@link AuthXManager} instance is shared by every JedisClientConfig in the app (both AMR
 * endpoints' pools, and both probe connections) so token renewal re-authenticates all pooled
 * connections from a single lifecycle. redis-authx-entraid is beta (0.1.1-beta2 as of this
 * writing, no GA release) — see README "Known risks".
 */
@Configuration
public class AmrAuthConfig {

    private static final Logger log = LoggerFactory.getLogger(AmrAuthConfig.class);

    private AuthXManager authXManager;

    @Bean
    public AuthXManager authXManager(AmrProperties props, EventRecorder eventRecorder) {
        DefaultAzureCredential credential = new DefaultAzureCredentialBuilder().build();

        TokenAuthConfig tokenAuthConfig = AzureTokenAuthConfigBuilder.builder()
                .defaultAzureCredential(credential)
                .scopes(Set.of(props.getAuth().getTokenScope()))
                .tokenRequestExecTimeoutInMs(props.getAuth().getTokenRequestTimeoutMs())
                .build();

        AuthXManager manager = new AuthXManager(tokenAuthConfig);

        manager.setListener(new AuthXEventListener() {
            @Override
            public void onIdentityProviderError(Exception reason) {
                log.error("Entra ID token acquisition/renewal failed", reason);
                eventRecorder.record(Run.BACKGROUND_RUN_ID, EventCategory.TOKEN_RENEWAL, null,
                        "FAILURE", null, com.example.amrkpi.metrics.ErrorCategory.AUTH_FAILURE,
                        Map.of("phase", "identity_provider", "error", String.valueOf(reason.getMessage())));
            }

            @Override
            public void onConnectionAuthenticationError(Exception reason) {
                log.error("Re-authenticating a pooled connection after token renewal failed", reason);
                eventRecorder.record(Run.BACKGROUND_RUN_ID, EventCategory.TOKEN_RENEWAL, null,
                        "FAILURE", null, com.example.amrkpi.metrics.ErrorCategory.AUTH_FAILURE,
                        Map.of("phase", "connection_reauth", "error", String.valueOf(reason.getMessage())));
            }
        });

        manager.addPostAuthenticationHook(token -> {
            long ttlMs = token.ttl();
            eventRecorder.record(Run.BACKGROUND_RUN_ID, EventCategory.TOKEN_RENEWAL, null,
                    "SUCCESS", null, null,
                    Map.of(
                            "user", String.valueOf(token.getUser()),
                            "receivedAt", Instant.ofEpochMilli(token.getReceivedAt()).toString(),
                            "expiresAt", Instant.ofEpochMilli(token.getExpiresAt()).toString(),
                            "ttlMillis", ttlMs
                    ));
            log.info("Entra ID token renewed for AMR, ttl={}ms expiresAt={}", ttlMs,
                    Instant.ofEpochMilli(token.getExpiresAt()));
        });

        // Must be started explicitly — nothing in Jedis calls this for us. Without it the token
        // manager never fetches an initial token and every connection auth attempt fails.
        manager.start();
        this.authXManager = manager;
        return manager;
    }

    @PreDestroy
    public void shutdown() {
        if (authXManager != null) {
            authXManager.stop();
        }
    }
}
