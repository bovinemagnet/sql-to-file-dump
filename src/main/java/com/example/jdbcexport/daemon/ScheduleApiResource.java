package com.example.jdbcexport.daemon;

import com.example.jdbcexport.error.ExportException;
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

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * JSON API for recurring/one-off export schedules, backing the console's Schedules view.
 * Schedules are persisted by {@link ScheduleStore} and fired by {@link ScheduleRunner}.
 * State-changing calls carry the CSRF header enforced by {@link CsrfFilter}.
 */
@Path("/api/schedules")
@Produces(MediaType.APPLICATION_JSON)
public class ScheduleApiResource {

    @Inject
    ScheduleStore store;

    @Inject
    ScheduleRunner runner;

    @GET
    public List<ScheduleView> list() {
        return store.list().stream().map(ScheduleApiResource::view).toList();
    }

    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response create(Form form) {
        try {
            return Response.ok(view(store.create(form.toDraft()))).build();
        } catch (ExportException e) {
            return badRequest(e);
        }
    }

    @PUT
    @Path("{id}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response update(@PathParam("id") String id, Form form) {
        try {
            return Response.ok(view(store.update(id, form.toDraft()))).build();
        } catch (ExportException e) {
            return e.getMessage() != null && e.getMessage().contains("not found")
                ? Response.status(Response.Status.NOT_FOUND).entity(new DashboardApiResource.ErrorView(e.getMessage())).build()
                : badRequest(e);
        }
    }

    @DELETE
    @Path("{id}")
    public Response delete(@PathParam("id") String id) {
        return store.delete(id)
            ? Response.noContent().build()
            : notFound(id);
    }

    @POST
    @Path("{id}/run")
    public Response runNow(@PathParam("id") String id) {
        try {
            return Response.ok(new RunView(runner.runNow(id))).build();
        } catch (ExportException e) {
            return e.getMessage() != null && e.getMessage().contains("not found") ? notFound(id) : badRequest(e);
        }
    }

    @POST
    @Path("{id}/toggle")
    public Response toggle(@PathParam("id") String id) {
        return store.get(id)
            .map(s -> store.setEnabled(id, !s.enabled()).map(ScheduleApiResource::view).orElse(null))
            .map(v -> Response.ok(v).build())
            .orElseGet(() -> notFound(id));
    }

    private static Response badRequest(ExportException e) {
        return Response.status(Response.Status.BAD_REQUEST)
            .entity(new DashboardApiResource.ErrorView(e.getMessage())).build();
    }

    private static Response notFound(String id) {
        return Response.status(Response.Status.NOT_FOUND)
            .entity(new DashboardApiResource.ErrorView("Schedule not found: " + id)).build();
    }

    private static ScheduleView view(Schedule s) {
        Instant from = s.lastRunAt() != null ? s.lastRunAt() : s.createdAt();
        Long nextRun = s.enabled()
            ? ScheduleTimes.nextFire(s, from).map(Instant::toEpochMilli).orElse(null)
            : null;
        return new ScheduleView(
            s.id(), s.name(), s.enabled(), s.connectionId(), s.sql(), s.format(), s.compression(),
            s.outputPattern(), s.overwrite(), s.triggerType(), s.cron(), s.every(), s.unit(), s.at(),
            nextRun, s.lastRunAt() == null ? null : s.lastRunAt().toEpochMilli(), s.lastStatus());
    }

    /** Form body for create/update. Booleans default to a friendly value when omitted. */
    public static class Form {
        @FormParam("name") public String name;
        @FormParam("connectionId") public String connectionId;
        @FormParam("sql") public String sql;
        @FormParam("format") public String format;
        @FormParam("compression") public String compression;
        @FormParam("outputPattern") public String outputPattern;
        @FormParam("overwrite") public String overwrite;
        @FormParam("triggerType") public String triggerType;
        @FormParam("cron") public String cron;
        @FormParam("every") public String every;
        @FormParam("unit") public String unit;
        @FormParam("at") public String at;
        @FormParam("enabled") public String enabled;

        Schedule toDraft() {
            return new Schedule(null, name, parseBool(enabled, true), connectionId, sql, format, compression,
                outputPattern, parseBool(overwrite, true), triggerType, cron, parseInt(every), unit, at,
                null, null, null, null);
        }

        private static boolean parseBool(String v, boolean dflt) {
            return v == null || v.isBlank() ? dflt : Boolean.parseBoolean(v);
        }

        private static Integer parseInt(String v) {
            if (v == null || v.isBlank()) {
                return null;
            }
            try {
                return Integer.valueOf(v.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    public record RunView(String jobId) {
    }

    public record ScheduleView(
        String id, String name, boolean enabled, String connectionId, String sql, String format,
        String compression, String outputPattern, boolean overwrite, String triggerType,
        String cron, Integer every, String unit, String at,
        Long nextRunEpochMs, Long lastRunEpochMs, String lastStatus) {
    }
}
