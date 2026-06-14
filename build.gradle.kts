plugins {
    java
    id("io.quarkus") version "3.36.2"
}

val quarkusVersion = "3.36.2"
val commonsCsvVersion = "1.12.0"
val parquetVersion = "1.14.4"
val hadoopVersion = "3.4.1"
val assertjVersion = "3.27.3"
val testcontainersVersion = "1.20.6"
val duckDbVersion = "1.1.3"

group = "com.example"
version = "1.0.0-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    implementation(enforcedPlatform("io.quarkus.platform:quarkus-bom:$quarkusVersion"))

    implementation("io.quarkus:quarkus-picocli")
    implementation("io.quarkus:quarkus-arc")
    implementation("io.quarkus:quarkus-rest-qute")
    implementation("io.quarkus:quarkus-rest-jackson")
    implementation("org.webjars.npm:htmx.org:2.0.4")

    implementation("com.fasterxml.jackson.core:jackson-core")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    implementation("com.cronutils:cron-utils:9.2.1")

    implementation("org.apache.commons:commons-csv:$commonsCsvVersion")

    implementation("org.apache.parquet:parquet-avro:$parquetVersion")
    implementation("org.apache.hadoop:hadoop-common:$hadoopVersion")

    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("com.oracle.database.jdbc:ojdbc11:23.6.0.24.10")

    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:$assertjVersion")
    testImplementation("org.testcontainers:junit-jupiter:$testcontainersVersion")
    testImplementation("org.testcontainers:postgresql:$testcontainersVersion")
    testImplementation("org.duckdb:duckdb_jdbc:$duckDbVersion")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Required by Quarkus @QuarkusMainTest launcher on Java 24+.
    jvmArgs("--add-opens", "java.base/java.lang=ALL-UNNAMED")
}

// Build the Antora documentation site from src/docs via the Antora CLI (Node/npx).
tasks.register<Exec>("antora") {
    group = "documentation"
    description = "Build the Antora documentation site into build/site"
    commandLine("npx", "--yes", "antora", "antora-playbook.yml")
}
