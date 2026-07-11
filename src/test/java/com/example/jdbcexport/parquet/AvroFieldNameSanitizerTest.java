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
    void replacesNonAsciiLetters() {
        // Avro names must match [A-Za-z_][A-Za-z0-9_]* — 'é' is a letter to
        // Character.isLetter but illegal in an Avro name (issue #23).
        assertThat(AvroFieldNameSanitizer.sanitize("café")).isEqualTo("caf_");
    }

    @Test
    void replacesLeadingNonAsciiLetter() {
        assertThat(AvroFieldNameSanitizer.sanitize("über")).isEqualTo("_ber");
    }

    @Test
    void replacesNonAsciiDigits() {
        // Arabic-Indic digit one (U+0661) passes Character.isLetterOrDigit but is not [0-9].
        assertThat(AvroFieldNameSanitizer.sanitize("col١")).isEqualTo("col_");
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
