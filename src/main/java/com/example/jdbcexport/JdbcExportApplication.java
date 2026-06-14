package com.example.jdbcexport;

import com.example.jdbcexport.cli.JdbcExportCommand;
import io.quarkus.picocli.runtime.annotations.TopCommand;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import picocli.CommandLine;

@QuarkusMain
public class JdbcExportApplication implements QuarkusApplication {

    @Inject
    CommandLine.IFactory factory;

    @Inject
    @TopCommand
    JdbcExportCommand command;

    public static void main(String[] args) {
        configureHttp(args);
        Quarkus.run(JdbcExportApplication.class, args);
    }

    /*
     * The HTTP server starts before Picocli parses arguments, so daemon networking must
     * be configured here. A normal CLI export (real args, not "daemon") disables the
     * listener so a busy port can never break it. No args is left enabled: in dev mode
     * that serves the dashboard (see run()); the packaged jar with no args is a usage
     * error that exits immediately anyway.
     */
    static void configureHttp(String[] args) {
        boolean daemon = args.length > 0 && "daemon".equals(args[0]);
        if (daemon) {
            configureDaemonHttp(args);
            return;
        }
        if (args.length > 0) {
            System.setProperty("quarkus.http.host-enabled", "false");
        }
    }

    private static void configureDaemonHttp(String[] args) {
        String host = "localhost";
        boolean allowRemote = false;
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if ("--port".equals(arg) && i + 1 < args.length) {
                System.setProperty("quarkus.http.port", args[i + 1]);
            } else if (arg.startsWith("--port=")) {
                System.setProperty("quarkus.http.port", arg.substring("--port=".length()));
            } else if ("--host".equals(arg) && i + 1 < args.length) {
                host = args[i + 1];
            } else if (arg.startsWith("--host=")) {
                host = arg.substring("--host=".length());
            } else if ("--allow-remote".equals(arg)) {
                allowRemote = true;
            }
        }
        if (!isLoopbackHost(host) && !allowRemote) {
            System.err.printf(
                "Refusing to bind the unauthenticated dashboard to non-loopback host '%s'. "
                    + "Pass --allow-remote to override, and only on trusted networks.%n", host);
            System.exit(1);
        }
        System.setProperty("quarkus.http.host", host);
    }

    static boolean isLoopbackHost(String host) {
        if (host == null || host.isBlank()) {
            return true;
        }
        String normalized = host.trim().toLowerCase();
        return normalized.equals("localhost")
            || normalized.equals("::1")
            || normalized.equals("[::1]")
            || normalized.startsWith("127.");
    }

    @Override
    public int run(String... args) {
        String[] effectiveArgs = args;
        // In `quarkusDev` with no arguments, run the dashboard daemon with live reload
        // instead of the export command (which would fail on the missing --url option).
        if (args.length == 0 && LaunchMode.current() == LaunchMode.DEVELOPMENT) {
            effectiveArgs = new String[] {"daemon"};
        }
        return new CommandLine(command, factory).execute(effectiveArgs);
    }
}
