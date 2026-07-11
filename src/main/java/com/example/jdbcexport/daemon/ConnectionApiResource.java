package com.example.jdbcexport.daemon;

import com.example.jdbcexport.error.ExportException;
import com.example.jdbcexport.jdbc.JdbcConnectionFactory;
import com.example.jdbcexport.jdbc.JdbcUrlRedactor;
import com.example.jdbcexport.jdbc.PasswordResolver;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.List;

/**
 * JSON API for the operator's saved JDBC connections. Passwords are never accepted for
 * storage — only {@code passwordEnv} references; an inline password may be supplied to the
 * ad-hoc test endpoint but is used transiently and never persisted. State-changing calls
 * carry the CSRF header enforced by {@link CsrfFilter}.
 */
@Path("/api/connections")
@Produces(MediaType.APPLICATION_JSON)
public class ConnectionApiResource {

    @Inject
    ConnectionStore store;

    @GET
    public List<ConnectionView> list() {
        return store.list().stream().map(this::view).toList();
    }

    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response create(
        @FormParam("name") String name,
        @FormParam("driver") String driver,
        @FormParam("url") String url,
        @FormParam("user") String user,
        @FormParam("passwordEnv") String passwordEnv) {
        try {
            return Response.ok(view(store.create(name, driver, url, user, passwordEnv))).build();
        } catch (ExportException e) {
            return badRequest(e);
        }
    }

    @PUT
    @Path("{id}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response update(
        @PathParam("id") String id,
        @FormParam("name") String name,
        @FormParam("driver") String driver,
        @FormParam("url") String url,
        @FormParam("user") String user,
        @FormParam("passwordEnv") String passwordEnv) {
        try {
            return Response.ok(view(store.update(id, name, driver, url, user, passwordEnv))).build();
        } catch (ExportException e) {
            return badRequest(e);
        }
    }

    @DELETE
    @Path("{id}")
    public Response delete(@PathParam("id") String id) {
        return store.delete(id)
            ? Response.noContent().build()
            : Response.status(Response.Status.NOT_FOUND).entity(new DashboardApiResource.ErrorView("Connection not found: " + id)).build();
    }

    @POST
    @Path("{id}/test")
    public Response testSaved(@PathParam("id") String id) {
        return store.get(id).map(c -> {
            TestResult result = probe(c.url(), c.user(), c.passwordEnv(), null);
            store.recordStatus(id, result.ok(), result.message());
            return Response.ok(result).build();
        }).orElseGet(() -> Response.status(Response.Status.NOT_FOUND)
            .entity(new DashboardApiResource.ErrorView("Connection not found: " + id)).build());
    }

    @POST
    @Path("test")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public TestResult testAdHoc(
        @FormParam("url") String url,
        @FormParam("user") String user,
        @FormParam("passwordEnv") String passwordEnv,
        @FormParam("password") String password) {
        return probe(url, user, passwordEnv, password);
    }

    private TestResult probe(String url, String user, String passwordEnv, String password) {
        try {
            String resolved = PasswordResolver.resolve(blankToNull(password), blankToNull(passwordEnv));
            try (Connection connection = JdbcConnectionFactory.connect(url, user, resolved)) {
                DatabaseMetaData md = connection.getMetaData();
                return new TestResult(true, "reachable",
                    md.getDatabaseProductName() + " " + md.getDatabaseProductVersion());
            }
        } catch (Exception e) {
            // Issue #30: driver failure messages frequently echo the full connection string.
            return new TestResult(false, JdbcUrlRedactor.redact(e.getMessage()), null);
        }
    }

    private ConnectionView view(SavedConnection c) {
        ConnectionStore.Status status = store.statusOf(c.id()).orElse(null);
        // Issue #30: never echo inline URL credentials from the API (the raw URL stays in the
        // store so connections keep working; only the view is redacted).
        return new ConnectionView(
            c.id(), c.name(), c.driver(), JdbcUrlRedactor.redact(c.url()), c.user(), c.passwordEnv(),
            c.lastUsedAt() == null ? null : c.lastUsedAt().toEpochMilli(),
            status == null ? "unknown" : status.state(),
            status == null ? null : status.message(),
            status == null ? null : status.ok());
    }

    private static Response badRequest(ExportException e) {
        return Response.status(Response.Status.BAD_REQUEST)
            .entity(new DashboardApiResource.ErrorView(e.getMessage())).build();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public record TestResult(boolean ok, String message, String serverInfo) {
    }

    public record ConnectionView(
        String id, String name, String driver, String url, String user, String passwordEnv,
        Long lastUsedEpochMs, String status, String statusMessage, Boolean ok) {
    }
}
