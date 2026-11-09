package com.inventario.multisucursal.common.audit;

import com.inventario.multisucursal.auth.JpaAuditingConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica que {@link Auditable} completa createdAt/updatedAt/createdBy/updatedBy
 * automáticamente al persistir y al actualizar (docs/DOMAIN_MODEL.md, sección 1).
 *
 * <p>Usa una entidad exclusiva de test ({@link AuditableTestEntity}) sobre una
 * base H2 embebida creada por esquema (Hibernate {@code ddl-auto=create-drop}),
 * en vez de Flyway/PostgreSQL — no hay ninguna tabla de negocio real que
 * probar todavía, solo el comportamiento de la clase base de auditoría.
 */
@DataJpaTest
@Import(JpaAuditingConfig.class)
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class AuditableEntityTest {

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void populatesCreatedAndUpdatedFieldsOnPersist() {
        AuditableTestEntity entity = new AuditableTestEntity();
        entity.setName("demo");

        AuditableTestEntity saved = entityManager.persistFlushFind(entity);

        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.getCreatedBy()).isEqualTo("system");
        assertThat(saved.getUpdatedBy()).isEqualTo("system");
    }

    @Test
    void keepsCreatedAtStableAndAdvancesUpdatedAtOnModification() {
        AuditableTestEntity entity = new AuditableTestEntity();
        entity.setName("demo");
        AuditableTestEntity saved = entityManager.persistFlushFind(entity);
        var originalCreatedAt = saved.getCreatedAt();

        saved.setName("actualizado");
        entityManager.persistAndFlush(saved);
        AuditableTestEntity reloaded = entityManager.find(AuditableTestEntity.class, saved.getId());

        assertThat(reloaded.getCreatedAt()).isEqualTo(originalCreatedAt);
        assertThat(reloaded.getUpdatedAt()).isAfterOrEqualTo(originalCreatedAt);
    }
}
