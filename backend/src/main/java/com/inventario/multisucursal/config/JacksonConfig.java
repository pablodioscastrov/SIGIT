package com.inventario.multisucursal.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.TimeZone;

/**
 * Convenciones de serialización de toda la API (docs/API_DESIGN.md, sección 1):
 * timestamps ISO-8601 en UTC (no epoch millis), y deserialización tolerante a
 * campos desconocidos para no romper compatibilidad hacia adelante entre
 * versiones del contrato. Los tipos de fecha en los DTOs deben usar
 * {@link java.time.Instant} (no {@code LocalDateTime} ni {@code OffsetDateTime})
 * para que el sufijo de salida sea siempre "Z", tal como documenta
 * docs/API_DESIGN.md.
 *
 * <p>{@code WRITE_DATES_AS_TIMESTAMPS} ya viene deshabilitado por defecto en
 * Spring Boot; se fija aquí de forma explícita para que la convención quede
 * documentada en código y no dependa de un default implícito del framework.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
        return builder -> builder
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .featuresToDisable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .timeZone(TimeZone.getTimeZone("UTC"));
    }
}
