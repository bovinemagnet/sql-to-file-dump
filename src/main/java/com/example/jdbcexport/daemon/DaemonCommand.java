package com.example.jdbcexport.daemon;

import io.quarkus.arc.Unremovable;
import io.quarkus.runtime.Quarkus;
import jakarta.enterprise.context.Dependent;
import org.eclipse.microprofile.config.ConfigProvider;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

@Dependent
@Unremovable
@Command(
    name = "daemon",
    description = "Run as a long-lived daemon serving the export dashboard",
    mixinStandardHelpOptions = true
)
public class DaemonCommand implements Callable<Integer> {

    /*
     * The HTTP server starts before Picocli parses anything, so --port and --host are
     * applied in JdbcExportApplication.main(). They are declared here so they appear
     * in --help and are accepted by the parser.
     */
    @Option(names = "--port", description = "Dashboard HTTP port", defaultValue = "8080")
    int port;

    @Option(names = "--host", description = "Dashboard bind address", defaultValue = "localhost")
    String host;

    @Option(names = "--allow-remote",
        description = "Permit binding to a non-loopback host (dashboard has no authentication)")
    boolean allowRemote;

    @Override
    public Integer call() {
        String actualHost = ConfigProvider.getConfig().getValue("quarkus.http.host", String.class);
        int actualPort = ConfigProvider.getConfig().getValue("quarkus.http.port", Integer.class);
        System.out.printf("Dashboard running at http://%s:%d/ (Ctrl-C to stop)%n", actualHost, actualPort);
        // Redirected stdout is block-buffered, and waitForExit() blocks indefinitely.
        System.out.flush();
        Quarkus.waitForExit();
        return 0;
    }
}
