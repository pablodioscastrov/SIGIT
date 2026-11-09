package com.inventario.multisucursal.common.reports;

import com.inventario.multisucursal.common.exception.BusinessRuleViolationException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** BR-056: validación compartida por los cuatro reportes exportables — probada una sola vez aquí, sin necesidad de arrancar Spring. */
class ReportRangeValidatorTest {

    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-01-31T23:59:59Z");

    @Test
    void acceptsAValidRange() {
        assertThatCode(() -> ReportRangeValidator.requireValidRange(FROM, TO)).doesNotThrowAnyException();
    }

    @Test
    void acceptsTheSameInstantForFromAndTo() {
        assertThatCode(() -> ReportRangeValidator.requireValidRange(FROM, FROM)).doesNotThrowAnyException();
    }

    @Test
    void rejectsAMissingDateFrom() {
        assertThatThrownBy(() -> ReportRangeValidator.requireValidRange(null, TO))
                .isInstanceOf(BusinessRuleViolationException.class)
                .extracting(ex -> ((BusinessRuleViolationException) ex).getCode())
                .isEqualTo("RANGO_FECHAS_REQUERIDO");
    }

    @Test
    void rejectsAMissingDateTo() {
        assertThatThrownBy(() -> ReportRangeValidator.requireValidRange(FROM, null))
                .isInstanceOf(BusinessRuleViolationException.class)
                .extracting(ex -> ((BusinessRuleViolationException) ex).getCode())
                .isEqualTo("RANGO_FECHAS_REQUERIDO");
    }

    @Test
    void rejectsAnInvertedRange() {
        assertThatThrownBy(() -> ReportRangeValidator.requireValidRange(TO, FROM))
                .isInstanceOf(BusinessRuleViolationException.class)
                .extracting(ex -> ((BusinessRuleViolationException) ex).getCode())
                .isEqualTo("RANGO_FECHAS_INVALIDO");
    }

    @Test
    void acceptsARowCountAtTheLimit() {
        assertThatCode(() -> ReportRangeValidator.requireWithinRowLimit(5000, 5000, "prueba")).doesNotThrowAnyException();
    }

    @Test
    void rejectsARowCountAboveTheLimit() {
        assertThatThrownBy(() -> ReportRangeValidator.requireWithinRowLimit(5001, 5000, "prueba"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .extracting(ex -> ((BusinessRuleViolationException) ex).getCode())
                .isEqualTo("REPORTE_DEMASIADO_GRANDE");
    }
}
