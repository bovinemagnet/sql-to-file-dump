package com.example.jdbcexport.transform.spi;

import com.example.jdbcexport.jdbc.JdbcValueReader;
import com.example.jdbcexport.jdbc.ResultSetColumn;
import com.example.jdbcexport.transform.OutboundTransformer;
import com.example.jdbcexport.transform.Row;
import com.example.jdbcexport.transform.TransformContext;
import com.example.jdbcexport.transform.TransformInput;
import com.example.jdbcexport.transform.TransformProvider;
import com.example.jdbcexport.transform.TransformResult;
import com.example.jdbcexport.transform.TransformSpec;

import java.util.List;

/** Example external transform used to verify the {@link TransformProvider} ServiceLoader SPI. */
public final class UppercaseTransformProvider implements TransformProvider {

    @Override
    public String type() {
        return "uppercase";
    }

    @Override
    public OutboundTransformer create(TransformSpec spec) {
        String column = spec.requireString("column");
        return new OutboundTransformer() {
            @Override
            public String name() {
                return "uppercase";
            }

            @Override
            public List<ResultSetColumn> transformSchema(List<ResultSetColumn> columns) {
                return columns;
            }

            @Override
            public TransformResult transform(TransformInput input, TransformContext context) {
                Row row = input.row();
                Object value = row.get(column);
                if (value != null) {
                    row.put(column, JdbcValueReader.stringify(value, "").toUpperCase());
                }
                return TransformResult.keep(row);
            }
        };
    }
}
