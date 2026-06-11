package com.example.jdbcexport;

import com.example.jdbcexport.cli.JdbcExportCommand;
import io.quarkus.picocli.runtime.annotations.TopCommand;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import picocli.CommandLine;

@QuarkusMain
public class JdbcExportApplication implements QuarkusApplication {

    @Inject
    CommandLine.IFactory factory;

    @Inject
    @TopCommand
    JdbcExportCommand command;

    public static void main(String[] args) {
        Quarkus.run(JdbcExportApplication.class, args);
    }

    @Override
    public int run(String... args) {
        return new CommandLine(command, factory).execute(args);
    }
}
