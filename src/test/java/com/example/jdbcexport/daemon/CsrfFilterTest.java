package com.example.jdbcexport.daemon;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct unit coverage for {@link CsrfFilter} (issue #40): the daemon {@code @QuarkusTest}
 * suites only ever exercise the header check through real POST endpoints. This drives
 * {@code filter()} directly so PUT/PATCH/DELETE are verified too — including PATCH, which has
 * no real resource method anywhere in the app to hit end-to-end.
 *
 * <p>{@link ContainerRequestContext} is a large JAX-RS interface; a {@link Proxy} stub answers
 * only the three methods the filter actually calls and fails loudly on anything else, avoiding
 * a hand-written stub with ~30 unused overrides or a new mocking-library dependency.
 */
class CsrfFilterTest {

    private final CsrfFilter filter = new CsrfFilter();

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void stateChangingMethodWithoutHeaderIsAborted(String method) {
        AtomicReference<Response> aborted = new AtomicReference<>();
        filter.filter(context(method, null, aborted));

        assertThat(aborted.get()).isNotNull();
        assertThat(aborted.get().getStatus()).isEqualTo(Response.Status.FORBIDDEN.getStatusCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void stateChangingMethodWithWrongHeaderIsAborted(String method) {
        AtomicReference<Response> aborted = new AtomicReference<>();
        filter.filter(context(method, "someone-else", aborted));

        assertThat(aborted.get()).isNotNull();
        assertThat(aborted.get().getStatus()).isEqualTo(Response.Status.FORBIDDEN.getStatusCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void stateChangingMethodWithCorrectHeaderPasses(String method) {
        AtomicReference<Response> aborted = new AtomicReference<>();
        filter.filter(context(method, "jdbc-export", aborted));

        assertThat(aborted.get()).isNull();
    }

    @Test
    void getIsNeverBlockedEvenWithoutTheHeader() {
        AtomicReference<Response> aborted = new AtomicReference<>();
        filter.filter(context("GET", null, aborted));

        assertThat(aborted.get()).isNull();
    }

    private static ContainerRequestContext context(String method, String header, AtomicReference<Response> aborted) {
        InvocationHandler handler = (proxy, invokedMethod, args) -> {
            switch (invokedMethod.getName()) {
                case "getMethod":
                    return method;
                case "getHeaderString":
                    return header;
                case "abortWith":
                    aborted.set((Response) args[0]);
                    return null;
                default:
                    throw new UnsupportedOperationException(
                        "CsrfFilter should not call " + invokedMethod.getName());
            }
        };
        return (ContainerRequestContext) Proxy.newProxyInstance(
            CsrfFilterTest.class.getClassLoader(),
            new Class<?>[] {ContainerRequestContext.class},
            handler);
    }
}
