package com.example.amrkpi.metrics;

import org.junit.jupiter.api.Test;
import redis.clients.jedis.exceptions.JedisAccessControlException;
import redis.clients.jedis.exceptions.JedisConnectionException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Error taxonomy is what lets a report tell "all errors were breaker rejections" apart from
 * "all errors were read timeouts" — every branch of the classifier is worth pinning down
 * explicitly so a refactor can't silently misclassify a whole error category.
 */
class ErrorCategoryTest {

    @Test
    void nullThrowableClassifiesAsOther() {
        assertThat(ErrorCategory.classify(null)).isEqualTo(ErrorCategory.OTHER);
    }

    @Test
    void jedisAccessControlExceptionClassifiesAsAuthFailure() {
        assertThat(ErrorCategory.classify(new JedisAccessControlException("nope")))
                .isEqualTo(ErrorCategory.AUTH_FAILURE);
    }

    @Test
    void wrongpassMessageClassifiesAsAuthFailure() {
        assertThat(ErrorCategory.classify(new RuntimeException("WRONGPASS invalid username-password pair")))
                .isEqualTo(ErrorCategory.AUTH_FAILURE);
    }

    @Test
    void noauthMessageClassifiesAsAuthFailure() {
        assertThat(ErrorCategory.classify(new RuntimeException("NOAUTH Authentication required")))
                .isEqualTo(ErrorCategory.AUTH_FAILURE);
    }

    @Test
    void callNotPermittedExceptionClassifiesAsCircuitBreakerRejection() {
        class CallNotPermittedException extends RuntimeException {
        }
        assertThat(ErrorCategory.classify(new CallNotPermittedException()))
                .isEqualTo(ErrorCategory.CIRCUIT_BREAKER_REJECTION);
    }

    @Test
    void poolExhaustionMessageClassifiesAsPoolExhaustion() {
        assertThat(ErrorCategory.classify(new RuntimeException("Could not get a resource from the pool")))
                .isEqualTo(ErrorCategory.POOL_EXHAUSTION);
    }

    @Test
    void timeoutExceptionClassifiesAsSocketReadTimeout() {
        assertThat(ErrorCategory.classify(new TimeoutException("no reply in time")))
                .isEqualTo(ErrorCategory.SOCKET_READ_TIMEOUT);
    }

    @Test
    void connectExceptionAsCauseClassifiesAsConnectTimeout() {
        RuntimeException wrapper = new RuntimeException("wrapped", new ConnectException("Connection refused"));
        assertThat(ErrorCategory.classify(wrapper)).isEqualTo(ErrorCategory.CONNECT_TIMEOUT);
    }

    @Test
    void connectionRefusedMessageClassifiesAsConnectTimeout() {
        assertThat(ErrorCategory.classify(new RuntimeException("Connection refused: connect")))
                .isEqualTo(ErrorCategory.CONNECT_TIMEOUT);
    }

    @Test
    void socketTimeoutExceptionClassifiesAsSocketReadTimeout() {
        assertThat(ErrorCategory.classify(new SocketTimeoutException("Read timed out")))
                .isEqualTo(ErrorCategory.SOCKET_READ_TIMEOUT);
    }

    @Test
    void genericJedisConnectionExceptionFallsBackToSocketReadTimeout() {
        assertThat(ErrorCategory.classify(new JedisConnectionException("unreachable")))
                .isEqualTo(ErrorCategory.SOCKET_READ_TIMEOUT);
    }

    @Test
    void unrelatedExceptionClassifiesAsOther() {
        assertThat(ErrorCategory.classify(new IllegalStateException("something else entirely")))
                .isEqualTo(ErrorCategory.OTHER);
    }
}
