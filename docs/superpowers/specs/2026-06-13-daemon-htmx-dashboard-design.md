# Design: Daemon Mode with HTMX Dashboard

Date: 2026-06-13
Author: Paul Snow
Status: Approved via session goal ("implement daemon mode and a htmx dashboard with no or minimal js")

## Purpose

Add a long-running daemon mode to `jdbc-export` that serves a browser dashboard for
submitting and monitoring export jobs. The dashboard uses HTMX for interactivity with
no custom JavaScript — the only script on the page is the vendored `htmx.min.js`.

The existing single-shot CLI behaviour is unchanged: `jdbc-export --url ... --sql ...`
still runs one export and exits. Daemon mode is an additive subcommand.

## CLI Surface

```bash
jdbc-export daemon [--port 8080] [--host localhost]
```

- Starts the Quarkus HTTP server and blocks until interrupted (Ctrl-C / SIGTERM).
- Binds to `localhost` by default — this is a local operator tool with no
  authentication, so it must not listen on all interfaces unless asked.
- In normal (non-daemon) CLI runs the HTTP listener is disabled, so exports never
  fail because a port is busy.

## Architecture

New package `com.example.jdbcexport.daemon`:

| Unit | Responsibility |
| ---- | -------------- |
| `DaemonCommand` | Picocli subcommand `daemon`. Prints the dashboard URL, waits for shutdown via `Quarkus.waitForExit()`. |
| `ExportJob` | Mutable job record: id, submitted/started/completed timestamps, request params (minus password), status (`QUEUED`/`RUNNING`/`COMPLETED`/`FAILED`), row count, duration, error message. |
| `ExportJobRequest` | Immutable snapshot of a dashboard form submission. |
| `ExportJobService` | `@ApplicationScoped`. Holds jobs in a `ConcurrentHashMap`, executes them on a virtual-thread executor by reusing the existing pipeline (`PasswordResolver` → `SqlSafetyValidator` → `JdbcConnectionFactory` → `ResultSetSchemaReader` → `RowWriterFactory` → `JdbcExporter`). No new JDBC logic. |
| `DashboardResource` | JAX-RS resource rendering Qute templates. Full page at `GET /`; HTML fragments for HTMX swaps. |

HTTP lifecycle: `JdbcExportApplication.configureHttp()` inspects `args` before
`Quarkus.run()`. If the first positional arg is `daemon`, it maps `--port`/`--host` to
`quarkus.http.port`/`quarkus.http.host` system properties; a real CLI export (args
present, not `daemon`) disables the HTTP listener so it never binds a port. This is
done before `Quarkus.run()` because Quarkus starts the server before Picocli parses.

No-args is deliberately left enabled. `run()` then checks `LaunchMode.current()`
(reliable once Quarkus has booted): in `DEVELOPMENT` it rewrites empty args to `daemon`,
so `gradle quarkusDev` boots straight into the dashboard with hot reload. The packaged
jar invoked with no args stays a usage error (`Missing required option: --url`) and
exits immediately, so leaving the listener enabled for that path is harmless.

## Dashboard (HTMX, no custom JS)

Routes:

- `GET /` — full page: submit form + jobs table.
- `POST /jobs` — submit a job, returns the jobs-table fragment (`hx-post`, target `#jobs`).
- `GET /jobs` — jobs-table fragment, polled by `hx-get` + `hx-trigger="every 2s"`.
- `GET /jobs/{id}` — job detail fragment (full SQL, error detail, column schema when available).
- `POST /describe` — runs schema-only inspection of the form's SQL, returns a columns-table fragment.

Form fields mirror the CLI: JDBC URL, user, password or password-env, SQL, format,
output path, overwrite. The password is used for the connection only and never stored
on the job or rendered back.

Jobs are held in memory only; restart clears history. Persistence is out of scope.

Assets: `htmx.org` webjar (versioned path), one small stylesheet under
`META-INF/resources`. No CDN, no custom JS.

## Approaches Considered

1. **Quarkus REST + Qute + HTMX (chosen)** — idiomatic, CDI-integrated, testable with
   `@QuarkusTest` + RestAssured. Cost: new extensions and the HTTP-in-CLI-mode
   lifecycle issue, solved in `main()`.
2. JDK `com.sun.net.httpserver` — zero new dependencies and trivially daemon-only, but
   hand-rolled routing/escaping/templating; worse maintainability and testability.
3. Quarkus Web Bundler / Renarde — heavier stack than a one-page dashboard justifies.

## Error Handling

- Job failures capture the `ExportException` message and exit-code semantics on the job;
  the dashboard shows status `FAILED` with the message. Unexpected exceptions are
  recorded as `FAILED` with the exception message.
- Form validation reuses the same checks as the CLI (SQL safety, overwrite protection,
  exactly-one-of password/password-env etc.) and surfaces errors as an inline fragment.

## Testing

- `ExportJobServiceTest` — submit against in-memory DuckDB, await completion, assert
  output file, row count, and FAILED path (bad SQL).
- `DashboardResourceTest` — `@QuarkusTest` + RestAssured: page loads, job submission
  renders rows, describe fragment renders columns, password never echoed.
- Existing CLI tests must stay green; non-daemon runs must not open a listening socket.

## Security Model

The dashboard has no authentication by design (local operator tool). It is hardened
against the realistic attack — a malicious page in the operator's browser reaching the
loopback daemon:

- **CSRF**: every state-changing request (`POST /jobs`, `POST /describe`) must carry the
  `X-Requested-By: jdbc-export` header (`CsrfFilter`). HTMX sends it via `hx-headers` on
  the page body; a cross-origin HTML form cannot set a custom header without a CORS
  preflight that is never granted, so drive-by posts get `403`.
- **Bind host**: binding to a non-loopback host is refused unless `--allow-remote` is
  passed explicitly, so the unauthenticated endpoint is not exposed to the network by
  accident.

Deliberately not added (would diverge from CLI behaviour without closing a remote
vector once CSRF is in place and binding stays loopback): output-path allowlisting and
`passwordEnv` confirmation. The local operator already has these capabilities via the
CLI, and remote actors are blocked by the two controls above.

## Known Limitations

- No authentication — mitigated by localhost-only default binding, CSRF header, and the
  non-loopback bind guard.
- Job history is in-memory and lost on restart.
- No job cancellation in v1.
