package com.inventario.multisucursal.common.web;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Sobre uniforme de paginación de toda la API (docs/API_DESIGN.md, sección 1).
 * Envuelve un {@link Page} de Spring Data en la forma pública que expone la
 * API, sin filtrar tipos de Spring Data directamente en la respuesta.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
