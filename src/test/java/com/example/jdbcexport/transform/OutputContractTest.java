package com.example.jdbcexport.transform;

import com.example.jdbcexport.error.ExportException;
import com.example.jdbcexport.jdbc.ResultSetColumn;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutputContractTest {

    private static List<ResultSetColumn> columns(String... names) {
        return java.util.Arrays.stream(names)
            .map(n -> new ResultSetColumn(1, n, n, Types.VARCHAR, "", 0, 0, true))
            .toList();
    }

    @Test
    void passesWhenRequiredFieldsPresent() {
        OutputContract contract = new OutputContract(List.of("id", "name"), List.of(), false);
        assertThatCode(() -> contract.validate(columns("id", "name", "extra"))).doesNotThrowAnyException();
    }

    @Test
    void failsWhenRequiredFieldMissing() {
        OutputContract contract = new OutputContract(List.of("id", "displayName"), List.of(), false);
        assertThatThrownBy(() -> contract.validate(columns("id")))
            .isInstanceOf(ExportException.class)
            .hasMessageContaining("required field \"displayName\" is missing");
    }

    @Test
    void failsOnUnknownFieldWhenStrict() {
        OutputContract contract = new OutputContract(List.of("id"), List.of("name"), true);
        assertThatThrownBy(() -> contract.validate(columns("id", "name", "surprise")))
            .isInstanceOf(ExportException.class)
            .hasMessageContaining("unexpected field \"surprise\"");
    }

    @Test
    void allowsOptionalFieldsWhenStrict() {
        OutputContract contract = new OutputContract(List.of("id"), List.of("name"), true);
        assertThatCode(() -> contract.validate(columns("id", "name"))).doesNotThrowAnyException();
    }
}
