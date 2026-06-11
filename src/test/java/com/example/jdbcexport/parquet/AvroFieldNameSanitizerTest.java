package com.example.jdbcexport.parquet;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AvroFieldNameSanitizerTest {

    @Test
    void sanitizesNormalName() {
        assertThat(AvroFieldNameSanitizer.sanitize("booking_id")).isEqualTo("booking_id");
    }

    @Test
    void sanitizesNameStartingWithDigit() {
        assertThat(AvroFieldNameSanitizer.sanitize("1col")).isEqualTo("_1col");
    }

    @Test
    void sanitizesSpecialChars() {
        assertThat(AvroFieldNameSanitizer.sanitize("my-col.name")).isEqualTo("my_col_name");
    }

    @Test
    void handlesNull() {
        assertThat(AvroFieldNameSanitizer.sanitize(null)).isEqualTo("_column");
    }

    @Test
    void handlesBlank() {
        assertThat(AvroFieldNameSanitizer.sanitize("")).isEqualTo("_column");
    }
}
