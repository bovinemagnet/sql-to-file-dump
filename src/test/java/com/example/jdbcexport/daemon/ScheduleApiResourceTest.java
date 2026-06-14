package com.example.jdbcexport.daemon;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.get;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class ScheduleApiResourceTest {

    @Test
    void createWithoutCsrfHeaderIsRejected() {
        given()
            .formParam("name", "blocked")
            .formParam("connectionId", "x")
            .formParam("sql", "select 1 as a")
            .formParam("outputPattern", "out_{date}.csv")
            .formParam("triggerType", "cron")
            .formParam("cron", "0 2 * * *")
            .when().post("/api/schedules")
            .then().statusCode(403);
    }

    @Test
    void createListRunToggleDelete() {
        String connId = extractId(createDuckConnection());

        String body = given()
            .header("X-Requested-By", "jdbc-export")
            .formParam("name", "nightly-course")
            .formParam("connectionId", connId)
            .formParam("sql", "select 1 as a")
            .formParam("format", "csv")
            .formParam("outputPattern", "build/sched-test_{date}.csv")
            .formParam("overwrite", "true")
            .formParam("triggerType", "cron")
            .formParam("cron", "0 2 * * *")
            .when().post("/api/schedules")
            .then().statusCode(200)
            .body(containsString("\"name\":\"nightly-course\""))
            .body(containsString("\"nextRunEpochMs\""))
            .extract().asString();
        String id = extractId(body);

        get("/api/schedules").then().statusCode(200).body(containsString(id));

        // Run now fires the export immediately (the connection is reachable in tests).
        given().header("X-Requested-By", "jdbc-export")
            .when().post("/api/schedules/" + id + "/run")
            .then().statusCode(200).body(containsString("\"jobId\""));

        // Toggle disables it.
        given().header("X-Requested-By", "jdbc-export")
            .when().post("/api/schedules/" + id + "/toggle")
            .then().statusCode(200).body(containsString("\"enabled\":false"));

        given().header("X-Requested-By", "jdbc-export")
            .when().delete("/api/schedules/" + id)
            .then().statusCode(204);
        get("/api/schedules").then().statusCode(200).body(not(containsString(id)));

        // tidy up the connection
        given().header("X-Requested-By", "jdbc-export").when().delete("/api/connections/" + connId);
    }

    @Test
    void invalidCronReturnsBadRequest() {
        given()
            .header("X-Requested-By", "jdbc-export")
            .formParam("name", "bad")
            .formParam("connectionId", "c")
            .formParam("sql", "select 1 as a")
            .formParam("outputPattern", "out_{date}.csv")
            .formParam("triggerType", "cron")
            .formParam("cron", "not-a-cron")
            .when().post("/api/schedules")
            .then().statusCode(400).body(containsString("cron"));
    }

    @Test
    void missingNameReturnsBadRequest() {
        given()
            .header("X-Requested-By", "jdbc-export")
            .formParam("connectionId", "c")
            .formParam("sql", "select 1 as a")
            .formParam("outputPattern", "out_{date}.csv")
            .formParam("triggerType", "interval")
            .formParam("every", "1")
            .formParam("unit", "hour")
            .when().post("/api/schedules")
            .then().statusCode(400).body(containsString("name"));
    }

    private String createDuckConnection() {
        return given()
            .header("X-Requested-By", "jdbc-export")
            .formParam("name", "sched-duck")
            .formParam("driver", "duckdb")
            .formParam("url", "jdbc:duckdb:")
            .formParam("user", "ignored")
            .when().post("/api/connections")
            .then().statusCode(200)
            .extract().asString();
    }

    private static String extractId(String json) {
        Matcher m = Pattern.compile("\"id\":\"([^\"]+)\"").matcher(json);
        assertThat(m.find()).isTrue();
        return m.group(1);
    }
}
