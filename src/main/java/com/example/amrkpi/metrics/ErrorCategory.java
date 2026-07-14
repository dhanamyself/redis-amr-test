package com.example.amrkpi.metrics;

import redis.clients.jedis.exceptions.JedisAccessControlException;
import redis.clients.jedis.exceptions.JedisConnectionException;

import java.net.SocketTimeoutException;
import java.net.ConnectException;

/**
 * Every failed operation is classified, not just counted. A run where all errors are breaker
 * rejections tells a completely different story from one full of read timeouts — reports must
 * be able to tell those apart.
 */
public enum ErrorCategory {
    CONNECT_TIMEOUT,
    SOCKET_READ_TIMEOUT,
    AUTH_FAILURE,
    CIRCUIT_BREAKER_REJECTION,
    POOL_EXHAUSTION,
    OTHER;

    public static ErrorCategory classify(Throwable t) {
        if (t == null) {
            return OTHER;
        }
        String className = t.getClass().getName();
        String message = String.valueOf(t.getMessage());

        if (t instanceof JedisAccessControlException || message.contains("WRONGPASS") || message.contains("NOAUTH")) {
            return AUTH_FAILURE;
        }
        if (className.contains("CallNotPermittedException")) {
            // Resilience4j fail-fast rejection while breaker is OPEN
            return CIRCUIT_BREAKER_REJECTION;
        }
        if (className.contains("PoolExhaustedException") || message.contains("Pool exhausted") || message.contains("Could not get a resource")) {
            return POOL_EXHAUSTION;
        }
        if (t instanceof java.util.concurrent.TimeoutException) {
            return SOCKET_READ_TIMEOUT;
        }
        if (t.getCause() instanceof ConnectException || message.contains("Connection refused") || message.contains("connect timed out")) {
            return CONNECT_TIMEOUT;
        }
        if (t instanceof SocketTimeoutException || t.getCause() instanceof SocketTimeoutException) {
            return SOCKET_READ_TIMEOUT;
        }
        if (t instanceof JedisConnectionException) {
            // JedisConnectionException wraps many lower-level causes; only fall back to OTHER
            // once none of the more specific classifications above matched.
            return SOCKET_READ_TIMEOUT;
        }
        return OTHER;
    }
}
