package com.inventario.multisucursal.auth;

import com.inventario.multisucursal.common.exception.BranchAccessDeniedException;
import com.inventario.multisucursal.users.RoleCode;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Autorización por sucursal, para usar en {@code @PreAuthorize} junto con
 * (o en vez de) {@code hasRole(...)} — BR-018 exige que ciertas acciones se
 * limiten tanto por rol como por la sucursal del usuario, no solo por rol.
 *
 * <p>{@code ADMIN} tiene alcance global (docs/DOMAIN_MODEL.md, sección 2.3) y
 * siempre pasa esta comprobación. Ya la consumen en producción
 * {@code DashboardService}, {@code InventoryMovementService},
 * {@code PurchaseOrderService}, {@code PurchaseReceiptService},
 * {@code SaleService}, {@code SaleReturnService} y {@code TransferService}
 * (vía {@code requireBranchAccess}/{@code requireAnyBranchAccess}/
 * {@code resolveBranchFilter}).
 */
@Component("authz")
public class AuthorizationService {

    public boolean requireBranchAccess(Long branchId) {
        AuthenticatedUser user = currentUser();
        if (user.role() == RoleCode.ADMIN) {
            return true;
        }
        if (branchId != null && branchId.equals(user.branchId())) {
            return true;
        }
        throw new BranchAccessDeniedException(
                "El usuario no pertenece a la sucursal solicitada.");
    }

    /**
     * Acceso cuando un recurso involucra a más de una sucursal y basta con
     * pertenecer a cualquiera de ellas — el caso de {@code Transfer}, visible
     * tanto desde su origen como desde su destino (docs/API_DESIGN.md,
     * sección 6). {@code ADMIN} conserva su alcance global.
     */
    public boolean requireAnyBranchAccess(Long... branchIds) {
        AuthenticatedUser user = currentUser();
        if (user.role() == RoleCode.ADMIN) {
            return true;
        }
        for (Long branchId : branchIds) {
            if (branchId != null && branchId.equals(user.branchId())) {
                return true;
            }
        }
        throw new BranchAccessDeniedException(
                "El usuario no pertenece a ninguna de las sucursales involucradas.");
    }

    /** La sucursal del usuario autenticado, o {@code null} si es {@code ADMIN} (alcance global). */
    public Long currentBranchScopeOrNull() {
        AuthenticatedUser user = currentUser();
        return user.role() == RoleCode.ADMIN ? null : user.branchId();
    }

    /**
     * Resuelve el filtro de sucursal para una consulta/reporte (BR-056): si
     * se pide una sucursal explícita, exige pertenecer a ella (o ser
     * {@code ADMIN}); si no se pide ninguna, usa el alcance propio del
     * usuario ({@code null} para {@code ADMIN} = sin restricción). A
     * diferencia de silenciar la sucursal pedida y sustituirla por la propia,
     * rechazar explícitamente es más transparente para una exportación: el
     * archivo nunca contiene datos de una sucursal distinta a la solicitada.
     */
    public Long resolveBranchFilter(Long requestedBranchId) {
        if (requestedBranchId != null) {
            requireBranchAccess(requestedBranchId);
            return requestedBranchId;
        }
        return currentBranchScopeOrNull();
    }

    private AuthenticatedUser currentUser() {
        return (AuthenticatedUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
