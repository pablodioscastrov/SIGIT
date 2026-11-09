package com.inventario.multisucursal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica el arranque de contexto y la configuración de migraciones contra
 * PostgreSQL real (no H2) — Flyway y sus constraints son específicos de
 * Postgres, probarlos contra H2 no demostraría nada (ver nota en
 * backend/pom.xml). No hay migraciones de negocio todavía
 * (backend/src/main/resources/db/migration está vacío a propósito); lo que
 * esta prueba demuestra es que la configuración de Flyway/datasource es
 * correcta y que el arranque no falla contra un motor real.
 *
 * <p>{@code disabledWithoutDocker = true}: en un entorno sin un daemon Docker
 * realmente accesible (p. ej. Docker-in-Docker restringido), esta prueba se
 * omite en vez de fallar el build completo — el resto de la suite no depende
 * de Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class FlywayMigrationIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void contextLoadsAgainstRealPostgres() {
        // Si el contexto arranca, Flyway ya corrió (o no tenía nada que correr)
        // exitosamente contra una instancia real de PostgreSQL.
    }

    @Test
    void flywayCreatedItsSchemaHistoryTable() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'flyway_schema_history'",
                Integer.class);

        assertThat(count).isEqualTo(1);
    }
}
