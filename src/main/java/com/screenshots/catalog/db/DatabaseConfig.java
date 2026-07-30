package com.screenshots.catalog.db;

import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;

import javax.sql.DataSource;

public final class DatabaseConfig {

    private final DataSource dataSource;

    public DatabaseConfig(String jdbcUrl, String user, String password) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL(jdbcUrl);
        ds.setUser(user);
        ds.setPassword(password);
        this.dataSource = ds;

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    public DataSource dataSource() {
        return dataSource;
    }
}
