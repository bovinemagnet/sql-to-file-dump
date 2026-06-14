package com.example.jdbcexport.daemon;

import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Serves the Sluice ops-console single-page shell. All data is loaded client-side
 * from {@link DashboardApiResource} (/api/*); the static assets live under
 * META-INF/resources/sluice/.
 */
@Path("/")
@Produces(MediaType.TEXT_HTML)
public class DashboardResource {

    @Inject
    Template index;

    @GET
    public TemplateInstance index() {
        return index.instance();
    }
}
