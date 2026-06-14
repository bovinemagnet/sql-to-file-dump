package com.example.jdbcexport.daemon;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.get;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class DashboardResourceTest {

    @Test
    void indexServesSluiceShell() {
        get("/")
            .then()
            .statusCode(200)
            .body(containsString("Sluice"))
            .body(containsString("/sluice/app.js"));
    }

    @Test
    void stateChangingApiRequestWithoutCsrfHeaderIsRejected(@TempDir Path tempDir) {
        given()
            .formParam("url", "jdbc:duckdb:")
            .formParam("user", "ignored")
            .formParam("sql", "SELECT 1 AS a")
            .formParam("format", "csv")
            .formParam("output", tempDir.resolve("blocked.csv").toString())
            .when().post("/api/jobs")
            .then()
            .statusCode(403);
    }

    @Test
    void submittedJobRunsToCompletionAndNeverLeaksPassword(@TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("dashboard-out.csv");

        given()
            .header("X-Requested-By", "jdbc-export")
            .formParam("url", "jdbc:duckdb:")
            .formParam("user", "ignored")
            .formParam("password", "super-secret-value")
            .formParam("sql", "SELECT 7 AS lucky")
            .formParam("format", "csv")
            .formParam("output", output.toString())
            .when().post("/api/jobs")
            .then()
            .statusCode(200)
            .body(containsString("\"id\""))
            .body(not(containsString("super-secret-value")));

        awaitCompletedJob();
        assertThat(Files.readAllLines(output)).containsExactly("lucky", "7");
    }

    @Test
    void invalidSqlReturnsBadRequest(@TempDir Path tempDir) {
        given()
            .header("X-Requested-By", "jdbc-export")
            .formParam("url", "jdbc:duckdb:")
            .formParam("user", "ignored")
            .formParam("sql", "DROP TABLE bookings")
            .formParam("format", "csv")
            .formParam("output", tempDir.resolve("never.csv").toString())
            .when().post("/api/jobs")
            .then()
            .statusCode(400)
            .body(containsString("SELECT"));
    }

    @Test
    void describeReturnsColumnsAsJson() {
        given()
            .header("X-Requested-By", "jdbc-export")
            .formParam("url", "jdbc:duckdb:")
            .formParam("user", "ignored")
            .formParam("sql", "SELECT 1 AS first_col, 'x' AS second_col")
            .when().post("/api/describe")
            .then()
            .statusCode(200)
            .body(containsString("first_col"))
            .body(containsString("second_col"));
    }

    @Test
    void jobDetailExposesSqlAndMetricsButNeverThePassword(@TempDir Path tempDir) {
        given()
            .header("X-Requested-By", "jdbc-export")
            .formParam("url", "jdbc:duckdb:")
            .formParam("user", "ignored")
            .formParam("password", "super-secret-value")
            .formParam("sql", "SELECT 42 AS answer")
            .formParam("format", "json")
            .formParam("output", tempDir.resolve("detail.json").toString())
            .when().post("/api/jobs");

        // Jobs render newest-first, so the first id in the list is the job submitted above.
        String jobsJson = get("/api/jobs").then().statusCode(200).extract().asString();
        Matcher matcher = Pattern.compile("\"id\":\"(\\d+)\"").matcher(jobsJson);
        assertThat(matcher.find()).isTrue();
        String id = matcher.group(1);

        get("/api/jobs/" + id)
            .then()
            .statusCode(200)
            .body(containsString("SELECT 42 AS answer"))
            .body(containsString("\"fetchSize\""))
            .body(not(containsString("super-secret-value")));
    }

    @Test
    void unknownJobReturnsNotFound() {
        get("/api/jobs/999999")
            .then()
            .statusCode(404)
            .body(containsString("Job not found"));
    }

    @Test
    void metricsReportsHeapAndCounts() {
        get("/api/metrics")
            .then()
            .statusCode(200)
            .body(containsString("heapUsedBytes"))
            .body(containsString("heapMaxBytes"))
            .body(containsString("uptimeMillis"));
    }

    private void awaitCompletedJob() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            String json = get("/api/jobs").then().statusCode(200).extract().asString();
            if (json.contains("\"status\":\"completed\"")) {
                return;
            }
            if (json.contains("\"status\":\"failed\"")) {
                throw new AssertionError("Job failed:\n" + json);
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Job did not complete in time");
    }
}
