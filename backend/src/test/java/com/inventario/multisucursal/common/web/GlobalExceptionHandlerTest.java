package com.inventario.multisucursal.common.web;

import com.inventario.multisucursal.auth.JwtAuthenticationFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifica el formato uniforme de error (docs/API_DESIGN.md, sección 3) y el
 * mapeo de códigos HTTP (sección 4) usando {@link ErrorFormatTestController},
 * un controlador propio del test — no depende de ningún módulo de negocio
 * todavía inexistente.
 *
 * <p>Excluye la autoconfiguración de Spring Security y el bean
 * {@link JwtAuthenticationFilter}: esta prueba verifica el mapeo de
 * excepciones de aplicación a la respuesta uniforme, no el mecanismo de
 * autenticación/autorización (eso lo cubre {@code AuthenticationFlowTest}).
 * Sin la primera exclusión, {@code anyRequest().authenticated()}
 * (SecurityConfig) rechazaría con 401 antes de llegar al controlador; sin la
 * segunda, {@code @WebMvcTest} igual intenta instanciar el filtro (detecta
 * beans {@code Filter} independientemente de la autoconfiguración de
 * seguridad) y falla porque este slice no expone un {@code JwtService} real.
 */
@WebMvcTest(
        controllers = ErrorFormatTestController.class,
        excludeAutoConfiguration = {SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class},
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthenticationFilter.class))
@Import({GlobalExceptionHandler.class, CorrelationIdFilter.class})
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void notFoundReturnsUniformEnvelope() throws Exception {
        mockMvc.perform(get("/test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RECURSO_NO_ENCONTRADO"))
                .andExpect(jsonPath("$.error.status").value(404))
                .andExpect(jsonPath("$.error.requestId").exists());
    }

    @Test
    void conflictReturnsCallerProvidedCode() throws Exception {
        mockMvc.perform(get("/test/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TRANSICION_INVALIDA"));
    }

    @Test
    void businessRuleViolationReturns422() throws Exception {
        mockMvc.perform(get("/test/business-rule"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("STOCK_INSUFICIENTE"));
    }

    @Test
    void beanValidationErrorReturns400WithFieldDetails() throws Exception {
        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"quantity\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details").isArray())
                .andExpect(jsonPath("$.error.details.length()").value(2));
    }

    @Test
    void malformedJsonReturns400() throws Exception {
        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void unexpectedExceptionReturns500WithoutLeakingInternalDetails() throws Exception {
        mockMvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("ERROR_INTERNO"))
                .andExpect(jsonPath("$.error.message").value("Ocurrió un error inesperado."));
    }

    @Test
    void everyResponseEchoesRequestIdHeader() throws Exception {
        mockMvc.perform(get("/test/not-found"))
                .andExpect(header().exists(CorrelationIdFilter.HEADER_NAME));
    }

    @Test
    void unmappedRouteReturns404NotInternalServerError() throws Exception {
        // Sin un handler específico para NoResourceFoundException, el catch-all
        // de Exception la capturaría y respondería 500 en vez del 404 esperado
        // por defecto (bug real detectado al probar contra el backend real, no
        // solo con este test).
        mockMvc.perform(get("/test/una-ruta-que-no-existe"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RECURSO_NO_ENCONTRADO"));
    }
}
