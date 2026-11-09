package com.inventario.multisucursal.inventory;

import com.inventario.multisucursal.common.exception.ResourceNotFoundException;
import com.inventario.multisucursal.common.web.PageResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;

/**
 * Consulta de stock (RF-002, RF-003, RF-006). Lectura abierta a cualquier
 * rol autenticado sobre cualquier sucursal (docs/API_DESIGN.md, sección 6) —
 * a diferencia del ajuste manual, aquí no aplica ninguna restricción de
 * sucursal propia.
 */
@Service
public class InventoryService {

    private final InventoryRepository inventoryRepository;

    public InventoryService(InventoryRepository inventoryRepository) {
        this.inventoryRepository = inventoryRepository;
    }

    public PageResponse<InventoryResponse> list(Long branchId, Long productId, String search, boolean lowStock, Pageable pageable) {
        return PageResponse.from(inventoryRepository.search(branchId, productId, search, lowStock, pageable).map(InventoryResponse::from));
    }

    public InventoryResponse getById(Long id) {
        return inventoryRepository.findById(id)
                .map(InventoryResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("INVENTARIO_NO_ENCONTRADO", "Registro de inventario no encontrado."));
    }

    /**
     * Los siguientes métodos no aplican autorización propia: quien llama
     * —{@code dashboard}— ya resolvió el alcance de sucursal antes de pedir
     * el dato (mismo criterio que {@code SaleService.salesTotals}).
     */
    public List<Long> productIdsInBranch(Long branchId) {
        return inventoryRepository.productIdsInBranch(branchId);
    }

    public List<Inventory> currentStock(Long branchId, Collection<Long> productIds) {
        return inventoryRepository.findByBranchIdAndProductIdIn(branchId, productIds);
    }

    /** Conteo total bajo el umbral de reabastecimiento (BR-042). */
    public long countLowStock(Long branchId) {
        return inventoryRepository.countLowStock(branchId);
    }

    /** Los {@code limit} productos más urgentes de reabastecer (BR-042). */
    public List<Inventory> mostUrgentLowStock(Long branchId, int limit) {
        return inventoryRepository.findMostUrgentLowStock(branchId, PageRequest.of(0, limit));
    }
}
