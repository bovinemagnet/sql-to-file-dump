package com.example.jdbcexport.daemon;

import com.example.jdbcexport.cli.OutputFormat;
import com.example.jdbcexport.error.ExportException;
import com.example.jdbcexport.error.ExitCodes;
import com.example.jdbcexport.jdbc.ResultSetColumn;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Locale;

/**
 * JSON API backing the Sluice ops console. The Dashboard and Live Run views render
 * client-side from these endpoints; state-changing calls still carry the CSRF header
 * enforced by {@link CsrfFilter}.
 */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class DashboardApiResource {

    @Inject
    ExportJobService jobService;

    @Inject
    ConnectionStore connectionStore;

    @GET
    @Path("jobs")
    public List<JobView> jobs() {
        return jobService.jobs().stream().map(JobView::from).toList();
    }

    @GET
    @Path("jobs/{id}")
    public Response job(@PathParam("id") String id) {
        return jobService.find(id)
            .map(job -> Response.ok(JobView.from(job)).build())
            .orElseGet(() -> Response.status(Response.Status.NOT_FOUND)
                .entity(new ErrorView("Job not found: " + id)).build());
    }

    @GET
    @Path("metrics")
    public ExportJobService.DaemonMetrics metrics() {
        return jobService.metrics();
    }

    @POST
    @Path("jobs")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response submit(
        @FormParam("url") String url,
        @FormParam("user") String user,
        @FormParam("password") String password,
        @FormParam("passwordEnv") String passwordEnv,
        @FormParam("sql") String sql,
        @FormParam("format") String format,
        @FormParam("output") String output,
        @FormParam("compression") String compression,
        @FormParam("connectionId") String connectionId,
        @FormParam("overwrite") boolean overwrite) {
        try {
            ExportJobRequest request = new ExportJobRequest(
                url, user, password, passwordEnv, sql, parseFormat(format), output, overwrite, compression);
            ExportJob job = jobService.submit(request);
            if (connectionId != null && !connectionId.isBlank()) {
                connectionStore.markUsed(connectionId);
            }
            return Response.ok(new SubmitView(job.getId(), job.getOutput())).build();
        } catch (ExportException e) {
            return badRequest(e);
        }
    }

    @POST
    @Path("describe")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response describe(
        @FormParam("url") String url,
        @FormParam("user") String user,
        @FormParam("password") String password,
        @FormParam("passwordEnv") String passwordEnv,
        @FormParam("sql") String sql) {
        try {
            ExportJobRequest request = new ExportJobRequest(
                url, user, password, passwordEnv, sql, null, null, false);
            List<ColumnView> columns = jobService.describe(request).stream().map(ColumnView::from).toList();
            return Response.ok(columns).build();
        } catch (ExportException e) {
            return badRequest(e);
        }
    }

    private static Response badRequest(ExportException e) {
        return Response.status(Response.Status.BAD_REQUEST).entity(new ErrorView(e.getMessage())).build();
    }

    private OutputFormat parseFormat(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OutputFormat.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new ExportException(ExitCodes.UNSUPPORTED_FORMAT, "Unsupported format: " + value);
        }
    }

    public record SubmitView(String id, String output) {
    }

    public record ErrorView(String error) {
    }

    public record ColumnView(int index, String label, String outputName, String jdbcTypeName, boolean nullable) {
        static ColumnView from(ResultSetColumn c) {
            return new ColumnView(c.index(), c.label(), c.outputName(), c.jdbcTypeName(), c.nullable());
        }
    }

    /** Snapshot of an {@link ExportJob} including live run metrics, safe to serialise (no password). */
    public record JobView(
        String id,
        String submittedAt,
        String url,
        String user,
        String driver,
        String sql,
        String format,
        String output,
        String status,
        long rowCount,
        Long durationMillis,
        long elapsedMillis,
        double throughput,
        long outputBytes,
        int fetchSize,
        int columnCount,
        String serverInfo,
        String compression,
        String error
    ) {
        static JobView from(ExportJob j) {
            return new JobView(
                j.getId(),
                j.getSubmittedAtDisplay(),
                j.getUrl(),
                j.getUser(),
                j.getDriver(),
                j.getSql(),
                j.getFormat() == null ? null : j.getFormat().name().toLowerCase(Locale.ROOT),
                j.getOutput(),
                j.getStatus().name().toLowerCase(Locale.ROOT),
                j.getRowCount(),
                j.getDurationMillis(),
                j.getElapsedMillis(),
                j.getThroughputRowsPerSecond(),
                j.getOutputBytes(),
                j.getFetchSize(),
                j.getColumnCount(),
                j.getServerInfo(),
                j.getCompression(),
                j.getError());
        }
    }
}
