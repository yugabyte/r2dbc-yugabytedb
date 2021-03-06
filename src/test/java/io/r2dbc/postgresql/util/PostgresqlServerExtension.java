/*
 * Copyright 2017-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.r2dbc.postgresql.util;

import com.zaxxer.hikari.HikariDataSource;
import io.r2dbc.postgresql.PostgresqlConnectionConfiguration;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import reactor.util.annotation.Nullable;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.Socket;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.testcontainers.utility.MountableFile.forHostPath;

/**
 * JUnit Extension to establish a Postgres database context during integration tests.
 * Uses either {@link TestContainer Testcontainers} or a {@link External locally available database}.
 */
public final class PostgresqlServerExtension implements BeforeAllCallback, AfterAllCallback {

    private volatile PostgreSQLContainer<?> containerInstance = null;

    private final Supplier<PostgreSQLContainer<?>> container = () -> {
        if (this.containerInstance != null) {
            return this.containerInstance;
        }
        return this.containerInstance = container();
    };

    private final DatabaseContainer postgres = new TestContainer(this.container.get());

    private final boolean useTestContainer = this.postgres instanceof TestContainer;

    private final AtomicInteger nestingCounter = new AtomicInteger(0);

    private HikariDataSource dataSource;

    private JdbcOperations jdbcOperations;

    public PostgresqlServerExtension() {
    }

    @Override
    public void afterAll(ExtensionContext context) {
        int nesting = this.nestingCounter.decrementAndGet();
        if (nesting == 0) {
            if (this.dataSource != null) {
                this.dataSource.close();
            }
            if (this.useTestContainer) {
                this.container.get().stop();
            }
        }
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        initialize();
    }

    public void initialize() {
        int nesting = this.nestingCounter.incrementAndGet();
        if (nesting == 1) {
            if (this.useTestContainer) {
                this.container.get().start();
            }

            HikariDataSource dataSource = new HikariDataSource();
            dataSource.setUsername(getUsername());
            dataSource.setPassword(getPassword());
            dataSource.setJdbcUrl(String.format("jdbc:postgresql://%s:%d/%s", getHost(), getPort(), getDatabase()));

            HikariDataSource hikariDataSource = new HikariDataSource();
            hikariDataSource.setUsername(getUsername());
            hikariDataSource.setPassword(getPassword());
            hikariDataSource.setJdbcUrl(String.format("jdbc:postgresql://%s:%d/%s", getHost(), getPort(), getDatabase()));

            this.dataSource = hikariDataSource;
            this.jdbcOperations = new JdbcTemplate(this.dataSource);
        }
    }

    public String getClientCrt() {
        return getResourcePath("client.crt").toAbsolutePath().toString();
    }

    public String getClientKey() {
        return getResourcePath("client.key").toAbsolutePath().toString();
    }

    public PostgresqlConnectionConfiguration.Builder configBuilder() {
        return PostgresqlConnectionConfiguration.builder().database(getDatabase()).host(getHost()).port(getPort()).username(getUsername()).password(getPassword());
    }

    public PostgresqlConnectionConfiguration getConnectionConfiguration() {
        return configBuilder().build();
    }

    public String getDatabase() {
        return this.postgres.getDatabase();
    }

    public DataSource getDataSource() {
        return dataSource;
    }

    @Nullable
    public JdbcOperations getJdbcOperations() {
        return this.jdbcOperations;
    }

    public String getHost() {
        return this.postgres.getHost();
    }

    public int getPort() {
        return this.postgres.getPort();
    }

    public String getServerCrt() {
        return getResourcePath("server.crt").toAbsolutePath().toString();
    }

    public String getServerKey() {
        return getResourcePath("server.key").toAbsolutePath().toString();
    }

    public String getUsername() {
        return this.postgres.getUsername();
    }

    public String getPassword() {
        return this.postgres.getPassword();
    }


    private <T extends PostgreSQLContainer<T>> T container() {
        T container = new PostgreSQLContainer<T>("postgres:latest")
            .withCopyFileToContainer(getHostPath("server.crt", 0600), "/var/server.crt")
            .withCopyFileToContainer(getHostPath("server.key", 0600), "/var/server.key")
            .withCopyFileToContainer(getHostPath("client.crt", 0600), "/var/client.crt")
            .withCopyFileToContainer(getHostPath("pg_hba.conf", 0600), "/var/pg_hba.conf")
            .withCopyFileToContainer(getHostPath("setup.sh", 0755), "/var/setup.sh")
            .withCopyFileToContainer(getHostPath("test-db-init-script.sql", 0755), "/docker-entrypoint-initdb.d/test-db-init-script.sql")
            .withCommand("/var/setup.sh");

        return container;
    }

    private Path getResourcePath(String name) {

        URL resource = getClass().getClassLoader().getResource(name);
        if (resource == null) {
            throw new IllegalStateException("Resource not found: " + name);
        }

        try {
            return Paths.get(resource.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Cannot convert to path for: " + name, e);
        }
    }

    private MountableFile getHostPath(String name, int mode) {
        return forHostPath(getResourcePath(name), mode);
    }

    /**
     * Interface to be implemented by database providers (provided database, test container).
     */
    interface DatabaseContainer {

        String getHost();

        int getPort();

        String getDatabase();

        String getUsername();

        String getPassword();
    }

    /**
     * Externally provided Postgres instance.
     */
    static class External implements DatabaseContainer {

        public static final External INSTANCE = new External();

        @Override
        public String getHost() {
            return "localhost";
        }

        @Override
        public int getPort() {
            return 5432;
        }

        @Override
        public String getDatabase() {
            return "postgres";
        }

        @Override
        public String getUsername() {
            return "postgres";
        }

        @Override
        public String getPassword() {
            return "postgres";
        }

        /**
         * Returns whether this container is available.
         *
         * @return
         */
        @SuppressWarnings("try")
        boolean isAvailable() {

            try (Socket ignored = new Socket(getHost(), getPort())) {

                return true;
            } catch (IOException e) {
                return false;
            }
        }
    }

    /**
     * {@link DatabaseContainer} provided by {@link JdbcDatabaseContainer}.
     */
    static class TestContainer implements DatabaseContainer {

        private final JdbcDatabaseContainer<?> container;

        TestContainer(JdbcDatabaseContainer<?> container) {
            this.container = container;
        }

        @Override
        public String getHost() {
            return this.container.getContainerIpAddress();
        }

        @Override
        public int getPort() {
            return this.container.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT);
        }

        @Override
        public String getDatabase() {
            return this.container.getDatabaseName();
        }

        @Override
        public String getUsername() {
            return this.container.getUsername();
        }

        @Override
        public String getPassword() {
            return this.container.getPassword();
        }
    }
}
