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

    /**
     * Top-command options that take no value. Any other {@code -}/{@code --} token
     * without an inline {@code =value} is assumed to consume the next argument.
     * Keep in sync with the boolean options on {@link JdbcExportCommand}.
     */
    private static final java.util.Set<String> TOP_COMMAND_FLAGS = java.util.Set.of(
        "--overwrite", "--dry-run", "--describe", "--verbose", "--pretty",
        "--include-header", "--no-include-header", "-h", "--help", "-V", "--version");

    /*
     * The HTTP server starts before Picocli parses arguments, so daemon networking must
     * be configured here. A normal CLI export (real args, not "daemon") disables the
     * listener so a busy port can never break it. No args is left enabled: in dev mode
     * that serves the dashboard (see run()); the packaged jar with no args is a usage
     * error that exits immediately anyway.
     */
    static void configureHttp(String[] args) {
        int daemonIndex = daemonArgIndex(args);
        if (daemonIndex >= 0) {
            configureDaemonHttp(args, daemonIndex);
            return;
        }
        if (args.length > 0) {
            System.setProperty("quarkus.http.host-enabled", "false");
        }
    }

    /*
     * Picocli allows global options before the subcommand, so "daemon" is not always
     * args[0]. A bare "daemon" token is treated as the subcommand only when it is not
     * the value of a preceding value-taking option (e.g. --sql daemon must stay a CLI
     * export). Heuristic limit: options are classified against the hardcoded
     * TOP_COMMAND_FLAGS list; an unrecognised flag directly followed by "daemon" would
     * be misread as an option/value pair and leave HTTP disabled — Picocli then rejects
     * the unknown option anyway, so the process still fails fast rather than hanging.
     */
    private static int daemonArgIndex(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("daemon".equals(arg)) {
                return i;
            }
            if (arg.startsWith("-") && !arg.contains("=") && !TOP_COMMAND_FLAGS.contains(arg)) {
                i++; // value-taking option: skip its value so "--sql daemon" is not misdetected
            }
        }
        return -1;
    }

    private static void configureDaemonHttp(String[] args, int daemonIndex) {
        String host = "localhost";
        boolean allowRemote = false;
        for (int i = daemonIndex + 1; i < args.length; i++) {
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
        if (host == null || host.isBlank()) {
            // An empty quarkus.http.host means bind-all to the HTTP layer; fall back to loopback.
            host = "localhost";
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
            // configureDaemonHttp substitutes the loopback default before binding.
            return true;
        }
        String normalised = host.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalised.equals("localhost")) {
            return true;
        }
        if (normalised.startsWith("[") && normalised.endsWith("]")) {
            normalised = normalised.substring(1, normalised.length() - 1);
        }
        try {
            // Literal parse only — never a DNS lookup — so a hostname spelt like
            // "127.evil.example" cannot masquerade as loopback.
            return java.net.InetAddress.ofLiteral(normalised).isLoopbackAddress();
        } catch (IllegalArgumentException notAnIpLiteral) {
            return false;
        }
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
