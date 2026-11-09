package com.inventario.multisucursal.inventory;

import com.inventario.multisucursal.common.web.PageResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** docs/API_DESIGN.md, sección 7.5. Lectura abierta a cualquier rol autenticado, cualquier sucursal (RF-003). */
@RestController
@RequestMapping("/api/v1/inventory")
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @GetMapping
    public PageResponse<InventoryResponse> list(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false, defaultValue = "false") boolean lowStock,
            @PageableDefault(size = 20) Pageable pageable) {
        return inventoryService.list(branchId, productId, search, lowStock, pageable);
    }

    @GetMapping("/{id}")
    public InventoryResponse get(@PathVariable Long id) {
        return inventoryService.getById(id);
    }
}
