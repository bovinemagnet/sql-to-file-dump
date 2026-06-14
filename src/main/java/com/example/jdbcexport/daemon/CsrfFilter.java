package com.example.jdbcexport.daemon;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.util.Set;

/**
 * The dashboard has no authentication, so a custom header is required on every
 * state-changing request. Cross-origin pages cannot set a custom header without a
 * CORS preflight (which is not permitted here), so this blocks drive-by CSRF form
 * posts to the loopback daemon while same-origin HTMX requests carry the header.
 */
@Provider
public class CsrfFilter implements ContainerRequestFilter {

    static final String HEADER = "X-Requested-By";
    static final String EXPECTED = "jdbc-export";

    private static final Set<String> STATE_CHANGING = Set.of("POST", "PUT", "PATCH", "DELETE");

    @Override
    public void filter(ContainerRequestContext context) {
        if (!STATE_CHANGING.contains(context.getMethod())) {
            return;
        }
        if (!EXPECTED.equals(context.getHeaderString(HEADER))) {
            context.abortWith(Response.status(Response.Status.FORBIDDEN)
                .entity("Missing or invalid " + HEADER + " header")
                .type(MediaType.TEXT_PLAIN)
                .build());
        }
    }
}
