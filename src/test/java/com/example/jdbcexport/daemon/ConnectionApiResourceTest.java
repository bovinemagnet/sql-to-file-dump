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
class ConnectionApiResourceTest {

    @Test
    void createWithoutCsrfHeaderIsRejected() {
        given()
            .formParam("name", "blocked")
            .formParam("url", "jdbc:duckdb:")
            .formParam("user", "u")
            .when().post("/api/connections")
            .then().statusCode(403);
    }

    @Test
    void updateWithoutCsrfHeaderIsRejected() {
        // Issue #40: only POST was covered end-to-end; PUT must be rejected the same way.
        given()
            .formParam("name", "blocked")
            .formParam("url", "jdbc:duckdb:")
            .formParam("user", "u")
            .when().put("/api/connections/does-not-matter")
            .then().statusCode(403);
    }

    @Test
    void deleteWithoutCsrfHeaderIsRejected() {
        // Issue #40: only POST was covered end-to-end; DELETE must be rejected the same way.
        given()
            .when().delete("/api/connections/does-not-matter")
            .then().statusCode(403);
    }

    @Test
    void createListTestAndDelete() {
        // No passwordEnv: DuckDB needs no credentials, so the test-connect can succeed.
        // (Storage of a passwordEnv reference is covered by ConnectionStoreTest.)
        String createBody = given()
            .header("X-Requested-By", "jdbc-export")
            .formParam("name", "test-duck")
            .formParam("driver", "duckdb")
            .formParam("url", "jdbc:duckdb:")
            .formParam("user", "ignored")
            .when().post("/api/connections")
            .then().statusCode(200)
            .body(containsString("\"name\":\"test-duck\""))
            .extract().asString();

        String id = extractId(createBody);

        get("/api/connections").then().statusCode(200).body(containsString(id));

        // jdbc:duckdb: is on the test classpath, so a real test connection succeeds.
        given()
            .header("X-Requested-By", "jdbc-export")
            .when().post("/api/connections/" + id + "/test")
            .then().statusCode(200)
            .body(containsString("\"ok\":true"))
            .body(containsString("DuckDB"));

        given()
            .header("X-Requested-By", "jdbc-export")
            .when().delete("/api/connections/" + id)
            .then().statusCode(204);

        get("/api/connections").then().statusCode(200).body(not(containsString(id)));
    }

    @Test
    void adHocTestOfUnreachableUrlReportsFailure() {
        given()
            .header("X-Requested-By", "jdbc-export")
            .formParam("url", "jdbc:postgresql://127.0.0.1:1/none")
            .formParam("user", "u")
            .when().post("/api/connections/test")
            .then().statusCode(200)
            .body(containsString("\"ok\":false"));
    }

    @Test
    void listRedactsInlineUrlCredentials() {
        // Issue #30: GET /api/connections must not echo credentials embedded in a saved URL.
        String createBody = given()
            .header("X-Requested-By", "jdbc-export")
            .formParam("name", "leaky")
            .formParam("driver", "postgresql")
            .formParam("url", "jdbc:postgresql://bob:hunter2@db:5432/appdb")
            .formParam("user", "bob")
            .when().post("/api/connections")
            .then().statusCode(200)
            .body(not(containsString("hunter2")))
            .extract().asString();

        String id = extractId(createBody);
        try {
            get("/api/connections").then().statusCode(200)
                .body(containsString("jdbc:postgresql://bob:*****@db:5432/appdb"))
                .body(not(containsString("hunter2")));
        } finally {
            given().header("X-Requested-By", "jdbc-export")
                .when().delete("/api/connections/" + id)
                .then().statusCode(204);
        }
    }

    @Test
    void adHocTestFailureMessageRedactsInlineUrlCredentials() {
        // Issue #30: the driver's failure message often echoes the connection string.
        given()
            .header("X-Requested-By", "jdbc-export")
            .formParam("url", "jdbc:duckdb-no-such-driver://bob:hunter2@db/none")
            .formParam("user", "bob")
            .when().post("/api/connections/test")
            .then().statusCode(200)
            .body(containsString("\"ok\":false"))
            .body(not(containsString("hunter2")));
    }

    @Test
    void createWithMissingFieldsReturnsBadRequest() {
        given()
            .header("X-Requested-By", "jdbc-export")
            .formParam("name", "")
            .formParam("url", "jdbc:duckdb:")
            .formParam("user", "u")
            .when().post("/api/connections")
            .then().statusCode(400)
            .body(containsString("name"));
    }

    private static String extractId(String json) {
        Matcher m = Pattern.compile("\"id\":\"([^\"]+)\"").matcher(json);
        assertThat(m.find()).isTrue();
        return m.group(1);
    }
}
