package com.offershield.common.db;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs infra/mysql/init/01-app-user.sh in a fresh mysql:8.4 container, the way docker-compose does, with fake
 * passwords full of characters that break naive quoting. The app password (fully ours) has quotes, backslashes (also
 * a trailing one), '#' and '$'. The root password can only use what the official image's own entrypoint survives
 * (it doesn't escape quotes or backslashes itself); its '#' still proves our option file quotes the value.
 */
@Testcontainers
class MysqlInitScriptIT {

    private static final Path SCRIPT = Path.of("..", "infra", "mysql", "init", "01-app-user.sh");
    private static final String ROOT_PASSWORD = "R00t#pa$s;w@rd!";
    private static final String APP_PASSWORD = "Ap'p\\x\"y$z#1\\";

    @Container
    static GenericContainer<?> mysql = new GenericContainer<>("mysql:8.4")
            .withEnv("MYSQL_ROOT_PASSWORD", ROOT_PASSWORD)
            .withEnv("DB_PASSWORD", APP_PASSWORD)
            .withCopyFileToContainer(MountableFile.forHostPath(SCRIPT, 0755), "/docker-entrypoint-initdb.d/01-app-user.sh")
            .withExposedPorts(3306)
            .waitingFor(Wait.forLogMessage(".*ready for connections.*port: 3306.*\\n", 1)
                    .withStartupTimeout(Duration.ofMinutes(3)));

    @Test
    void initScript_trickyPasswords_appUserCanSignInWithGrantsOnAppSchema() throws Exception {
        String url = "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306)
                + "/offershield_db?allowPublicKeyRetrieval=true&useSSL=false";

        try (Connection connection = DriverManager.getConnection(url, "offershield_app", APP_PASSWORD);
             Statement statement = connection.createStatement()) {
            assertThat(single(statement, "SELECT CURRENT_USER()")).isEqualTo("offershield_app@%");
            assertThat(single(statement, "SELECT DATABASE()")).isEqualTo("offershield_db");
            statement.execute("CREATE TABLE init_probe (id INT PRIMARY KEY)");
            statement.execute("DROP TABLE init_probe");
        }
    }

    @Test
    void initScript_rootPasswordNotInEnvironmentOfClient_optionFileRemoved() throws Exception {
        assertThat(Files.readString(SCRIPT)).doesNotContain("MYSQL_PWD");

        ExecResult leftovers = mysql.execInContainer("sh", "-c", "ls -A /tmp | grep -c offershield || true");
        assertThat(leftovers.getStdout().trim()).isEqualTo("0");
    }

    private static String single(Statement statement, String sql) throws Exception {
        try (ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        }
    }
}
