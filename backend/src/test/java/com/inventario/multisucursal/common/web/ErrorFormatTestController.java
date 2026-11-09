package com.inventario.multisucursal.common.web;

import com.inventario.multisucursal.common.exception.BusinessRuleViolationException;
import com.inventario.multisucursal.common.exception.ResourceConflictException;
import com.inventario.multisucursal.common.exception.ResourceNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controlador exclusivo de {@link GlobalExceptionHandlerTest} para ejercitar
 * {@link GlobalExceptionHandler} sin depender de ningún módulo de negocio
 * todavía inexistente. Vive en su propio archivo (no anidado en la clase de
 * test) porque el registro de rutas de {@code @WebMvcTest(controllers = ...)}
 * requiere una clase de nivel superior.
 */
@RestController
@RequestMapping("/test")
public class ErrorFormatTestController {

    @GetMapping("/not-found")
    public void notFound() {
        throw new ResourceNotFoundException("Producto no encontrado.");
    }

    @GetMapping("/conflict")
    public void conflict() {
        throw new ResourceConflictException("TRANSICION_INVALIDA", "La transferencia ya no está en REQUESTED.");
    }

    @GetMapping("/business-rule")
    public void businessRule() {
        throw new BusinessRuleViolationException("STOCK_INSUFICIENTE", "No hay stock suficiente.");
    }

    @PostMapping("/validate")
    public void validate(@Valid @RequestBody SampleRequest request) {
    }

    @GetMapping("/boom")
    public void boom() {
        throw new IllegalStateException("fallo interno inesperado");
    }

    public record SampleRequest(@NotBlank String name, @Positive int quantity) {
    }
}
