package com.inventario.multisucursal.users;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/** docs/API_DESIGN.md, sección 7.2: catálogo fijo de roles para formularios de UC-14. */
@RestController
@RequestMapping("/api/v1/roles")
@PreAuthorize("hasRole('ADMIN')")
public class RoleController {

    @GetMapping
    public List<RoleResponse> list() {
        return Arrays.stream(RoleCode.values()).map(RoleResponse::from).toList();
    }
}
