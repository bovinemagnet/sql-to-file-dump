package com.example.jdbcexport.jdbc;

import com.example.jdbcexport.error.ExportException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlSafetyValidatorTest {

    @Test
    void acceptsSelectQuery() {
        assertThatNoException().isThrownBy(() -> SqlSafetyValidator.validate("SELECT * FROM foo"));
    }

    @Test
    void acceptsLowercaseSelect() {
        assertThatNoException().isThrownBy(() -> SqlSafetyValidator.validate("select id from foo"));
    }

    @Test
    void acceptsWithQuery() {
        assertThatNoException().isThrownBy(() -> SqlSafetyValidator.validate("WITH cte AS (SELECT 1) SELECT * FROM cte"));
    }

    @Test
    void rejectsInsert() {
        assertThatThrownBy(() -> SqlSafetyValidator.validate("INSERT INTO foo VALUES (1)"))
            .isInstanceOf(ExportException.class);
    }

    @Test
    void rejectsDelete() {
        assertThatThrownBy(() -> SqlSafetyValidator.validate("DELETE FROM foo"))
            .isInstanceOf(ExportException.class);
    }

    @Test
    void rejectsBlank() {
        assertThatThrownBy(() -> SqlSafetyValidator.validate(""))
            .isInstanceOf(ExportException.class);
    }

    // --- comment handling -------------------------------------------------

    @Test
    void acceptsLineCommentBeforeSelect() {
        assertThatNoException().isThrownBy(() ->
            SqlSafetyValidator.validate("-- daily export\n-- owner: ops\nSELECT * FROM foo"));
    }

    @Test
    void acceptsBlockCommentBeforeSelect() {
        assertThatNoException().isThrownBy(() ->
            SqlSafetyValidator.validate("/* header\n   spanning lines */ SELECT * FROM foo"));
    }

    @Test
    void acceptsNestedBlockComment() {
        assertThatNoException().isThrownBy(() ->
            SqlSafetyValidator.validate("/* outer /* inner */ still comment */ SELECT 1"));
    }

    @Test
    void acceptsTrailingComment() {
        assertThatNoException().isThrownBy(() ->
            SqlSafetyValidator.validate("SELECT 1 -- trailing note"));
    }

    @Test
    void rejectsCommentOnlySql() {
        assertThatThrownBy(() -> SqlSafetyValidator.validate("-- nothing here\n"))
            .isInstanceOf(ExportException.class);
    }

    @Test
    void rejectsUnterminatedBlockComment() {
        assertThatThrownBy(() -> SqlSafetyValidator.validate("/* SELECT 1 FROM foo"))
            .isInstanceOf(ExportException.class);
    }

    @Test
    void ignoresKeywordsInsideComments() {
        assertThatNoException().isThrownBy(() ->
            SqlSafetyValidator.validate("SELECT 1 /* we do not DELETE here; honest */"));
    }

    // --- statement separator ---------------------------------------------

    @Test
    void rejectsMultiStatement() {
        assertThatThrownBy(() -> SqlSafetyValidator.validate("SELECT 1; DROP TABLE users"))
            .isInstanceOf(ExportException.class)
            .hasMessageContaining("single statement");
    }

    @Test
    void rejectsSemicolonHiddenAfterComment() {
        assertThatThrownBy(() -> SqlSafetyValidator.validate("SELECT 1 -- note\n; DROP TABLE users"))
            .isInstanceOf(ExportException.class);
    }

    @Test
    void acceptsTrailingSemicolon() {
        assertThatNoException().isThrownBy(() -> SqlSafetyValidator.validate("SELECT * FROM foo;"));
    }

    @Test
    void acceptsTrailingSemicolonFollowedByComment() {
        assertThatNoException().isThrownBy(() -> SqlSafetyValidator.validate("SELECT * FROM foo; -- done\n"));
    }

    @Test
    void acceptsSemicolonInsideStringLiteral() {
        assertThatNoException().isThrownBy(() ->
            SqlSafetyValidator.validate("SELECT * FROM foo WHERE name = 'a;b'"));
    }

    // --- literals and identifiers are not scanned for keywords ------------

    @Test
    void acceptsForbiddenKeywordInsideStringLiteral() {
        assertThatNoException().isThrownBy(() ->
            SqlSafetyValidator.validate("SELECT * FROM audit WHERE action = 'delete'"));
    }

    @Test
    void acceptsEscapedQuoteInsideStringLiteral() {
        assertThatNoException().isThrownBy(() ->
            SqlSafetyValidator.validate("SELECT * FROM t WHERE s = 'it''s; drop table x'"));
    }

    @Test
    void acceptsForbiddenKeywordAsQuotedIdentifier() {
        assertThatNoException().isThrownBy(() ->
            SqlSafetyValidator.validate("SELECT \"delete\" FROM t"));
    }

    @Test
    void acceptsDollarQuotedStringContainingSemicolon() {
        assertThatNoException().isThrownBy(() ->
            SqlSafetyValidator.validate("SELECT $tag$ ; DROP TABLE users $tag$ AS lit"));
    }

    @Test
    void rejectsUnterminatedStringLiteral() {
        assertThatThrownBy(() -> SqlSafetyValidator.validate("SELECT * FROM t WHERE s = 'oops"))
            .isInstanceOf(ExportException.class);
    }

    @Test
    void acceptsIdentifiersThatMerelyContainKeywords() {
        assertThatNoException().isThrownBy(() ->
            SqlSafetyValidator.validate("SELECT create_date, updated_at FROM update_log"));
    }

    // --- CTE bodies -------------------------------------------------------

    @Test
    void rejectsDeleteInsideCteBody() {
        assertThatThrownBy(() ->
            SqlSafetyValidator.validate("WITH d AS (DELETE FROM t RETURNING *) SELECT * FROM d"))
            .isInstanceOf(ExportException.class)
            .hasMessageContaining("DELETE");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "WITH u AS (UPDATE t SET a = 1 RETURNING *) SELECT * FROM u",
        "WITH i AS (INSERT INTO t VALUES (1) RETURNING *) SELECT * FROM i",
        "SELECT * FROM t WHERE id IN (SELECT id FROM (DROP TABLE x) y)",
    })
    void rejectsDataModifyingKeywordsAnywhere(String sql) {
        assertThatThrownBy(() -> SqlSafetyValidator.validate(sql))
            .isInstanceOf(ExportException.class);
    }

    // --- locale independence ----------------------------------------------

    @Test
    void acceptsUppercaseKeywordsUnderTurkishLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThatNoException().isThrownBy(() -> SqlSafetyValidator.validate("WITH cte AS (SELECT 1) SELECT * FROM cte"));
            assertThatNoException().isThrownBy(() -> SqlSafetyValidator.validate("SELECT * FROM foo"));
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void rejectsInsertUnderTurkishLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThatThrownBy(() -> SqlSafetyValidator.validate("INSERT INTO foo VALUES (1)"))
                .isInstanceOf(ExportException.class);
        } finally {
            Locale.setDefault(original);
        }
    }
}
