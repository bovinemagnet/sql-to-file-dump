package com.example.jdbcexport.daemon;

import com.example.jdbcexport.cli.OutputFormat;
import com.example.jdbcexport.error.ExportException;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/")
@Produces(MediaType.TEXT_HTML)
public class DashboardResource {

    @Inject
    ExportJobService jobService;

    @Inject
    Template index;

    @Inject
    @Location("jobs-table.html")
    Template jobsTable;

    @Inject
    @Location("job-detail.html")
    Template jobDetail;

    @Inject
    @Location("columns.html")
    Template columns;

    @Inject
    @Location("feedback.html")
    Template feedback;

    @GET
    public TemplateInstance index() {
        return index.instance();
    }

    @GET
    @Path("jobs")
    public TemplateInstance jobs() {
        return jobsTable.data("jobs", jobService.jobs());
    }

    @POST
    @Path("jobs")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public TemplateInstance submit(
        @FormParam("url") String url,
        @FormParam("user") String user,
        @FormParam("password") String password,
        @FormParam("passwordEnv") String passwordEnv,
        @FormParam("sql") String sql,
        @FormParam("format") String format,
        @FormParam("output") String output,
        @FormParam("overwrite") boolean overwrite) {
        try {
            ExportJobRequest request = new ExportJobRequest(
                url, user, password, passwordEnv, sql, parseFormat(format), output, overwrite);
            ExportJob job = jobService.submit(request);
            return success("Job " + job.getId() + " submitted: " + job.getOutput());
        } catch (ExportException e) {
            return error(e.getMessage());
        }
    }

    @GET
    @Path("jobs/{id}")
    public TemplateInstance detail(@PathParam("id") String id) {
        return jobService.find(id)
            .map(job -> jobDetail.data("job", job))
            .orElseGet(() -> error("Job not found: " + id));
    }

    @POST
    @Path("describe")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public TemplateInstance describe(
        @FormParam("url") String url,
        @FormParam("user") String user,
        @FormParam("password") String password,
        @FormParam("passwordEnv") String passwordEnv,
        @FormParam("sql") String sql) {
        try {
            ExportJobRequest request = new ExportJobRequest(
                url, user, password, passwordEnv, sql, null, null, false);
            return columns.data("columns", jobService.describe(request));
        } catch (ExportException e) {
            return error(e.getMessage());
        }
    }

    private OutputFormat parseFormat(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OutputFormat.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new ExportException(com.example.jdbcexport.error.ExitCodes.UNSUPPORTED_FORMAT,
                "Unsupported format: " + value);
        }
    }

    private TemplateInstance success(String message) {
        return feedback.data("message", message).data("error", null);
    }

    private TemplateInstance error(String message) {
        return feedback.data("message", null).data("error", message);
    }
}
