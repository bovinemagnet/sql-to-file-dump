package com.example.jdbcexport.daemon;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static io.restassured.RestAssured.get;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class DashboardResourceTest {

    @Test
    void indexPageLoadsWithFormAndHtmx() {
        get("/")
            .then()
            .statusCode(200)
            .body(containsString("jdbc-export"))
            .body(containsString("hx-post=\"/jobs\""))
            .body(containsString("htmx.min.js"));
    }

    @Test
    void stateChangingRequestWithoutCsrfHeaderIsRejected(@TempDir Path tempDir) {
        given()
            .formParam("url", "jdbc:duckdb:")
            .formParam("user", "ignored")
            .formParam("sql", "SELECT 1 AS a")
            .formParam("format", "csv")
            .formParam("output", tempDir.resolve("blocked.csv").toString())
            .when().post("/jobs")
            .then()
            .statusCode(403);
    }

    @Test
    void submittedJobAppearsInJobsTableAndCompletes(@TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("dashboard-out.csv");

        given()
            .header("X-Requested-By", "jdbc-export")
            .formParam("url", "jdbc:duckdb:")
            .formParam("user", "ignored")
            .formParam("password", "super-secret-value")
            .formParam("sql", "SELECT 7 AS lucky")
            .formParam("format", "csv")
            .formParam("output", output.toString())
            .when().post("/jobs")
            .then()
            .statusCode(200)
            .body(containsString("submitted"))
            .body(not(containsString("super-secret-value")));

        awaitCompletedRow();
        assertThat(Files.readAllLines(output)).containsExactly("lucky", "7");
    }

    @Test
    void invalidSqlShowsInlineError(@TempDir Path tempDir) {
        given()
            .header("X-Requested-By", "jdbc-export")
            .formParam("url", "jdbc:duckdb:")
            .formParam("user", "ignored")
            .formParam("sql", "DROP TABLE bookings")
            .formParam("format", "csv")
            .formParam("output", tempDir.resolve("never.csv").toString())
            .when().post("/jobs")
            .then()
            .statusCode(200)
            .body(containsString("class=\"feedback error\""))
            .body(containsString("SELECT"));
    }

    @Test
    void describeRendersColumnsFragment() {
        given()
            .header("X-Requested-By", "jdbc-export")
            .formParam("url", "jdbc:duckdb:")
            .formParam("user", "ignored")
            .formParam("sql", "SELECT 1 AS first_col, 'x' AS second_col")
            .when().post("/describe")
            .then()
            .statusCode(200)
            .body(containsString("first_col"))
            .body(containsString("second_col"))
            .body(containsString("Schema"));
    }

    @Test
    void jobDetailShowsSqlAndNeverThePassword(@TempDir Path tempDir) {
        given()
            .header("X-Requested-By", "jdbc-export")
            .formParam("url", "jdbc:duckdb:")
            .formParam("user", "ignored")
            .formParam("password", "super-secret-value")
            .formParam("sql", "SELECT 42 AS answer")
            .formParam("format", "json")
            .formParam("output", tempDir.resolve("detail.json").toString())
            .when().post("/jobs");

        // Jobs render newest-first, so the first detail link is the job submitted above.
        String jobsHtml = get("/jobs").then().statusCode(200).extract().asString();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("hx-get=\"/jobs/(\\d+)\"").matcher(jobsHtml);
        assertThat(matcher.find()).isTrue();
        String id = matcher.group(1);

        get("/jobs/" + id)
            .then()
            .statusCode(200)
            .body(containsString("SELECT 42 AS answer"))
            .body(not(containsString("super-secret-value")));
    }

    @Test
    void unknownJobShowsError() {
        get("/jobs/999999")
            .then()
            .statusCode(200)
            .body(containsString("Job not found"));
    }

    private void awaitCompletedRow() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            String html = get("/jobs").then().statusCode(200).extract().asString();
            if (html.contains("status-COMPLETED")) {
                return;
            }
            if (html.contains("status-FAILED")) {
                throw new AssertionError("Job failed:\n" + html);
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Job did not complete in time");
    }
}
