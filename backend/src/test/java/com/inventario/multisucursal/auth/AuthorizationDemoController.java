package com.inventario.multisucursal.auth;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controlador exclusivo de pruebas para verificar el mecanismo de
 * autorización por rol y por sucursal de extremo a extremo, ya que este
 * módulo no implementa todavía inventario, ventas ni transferencias (que son
 * los que en producción usarán {@code @authz.requireBranchAccess(...)}). No
 * forma parte del código de producción — vive en src/test.
 */
@RestController
@RequestMapping("/test/authz")
public class AuthorizationDemoController {

    @GetMapping("/admin-only")
    @PreAuthorize("hasRole('ADMIN')")
    public String adminOnly() {
        return "ok";
    }

    @GetMapping("/branches/{branchId}/data")
    @PreAuthorize("@authz.requireBranchAccess(#branchId)")
    public String branchData(@PathVariable Long branchId) {
        return "ok";
    }
}
