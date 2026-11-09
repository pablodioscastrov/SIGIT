package com.inventario.multisucursal.products;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** docs/API_DESIGN.md, sección 7.4: unidades alternativas de un producto (RF-011). */
@RestController
@RequestMapping("/api/v1/products/{productId}/units")
public class ProductUnitController {

    private final ProductUnitService productUnitService;

    public ProductUnitController(ProductUnitService productUnitService) {
        this.productUnitService = productUnitService;
    }

    @GetMapping
    public List<ProductUnitResponse> list(@PathVariable Long productId) {
        return productUnitService.list(productId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    public ProductUnitResponse add(@PathVariable Long productId, @Valid @RequestBody AddProductUnitRequest request) {
        return productUnitService.add(productId, request);
    }

    @PatchMapping("/{unitOfMeasureId}")
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    public ProductUnitResponse updateConversionFactor(
            @PathVariable Long productId,
            @PathVariable Long unitOfMeasureId,
            @Valid @RequestBody UpdateProductUnitConversionRequest request) {
        return productUnitService.updateConversionFactor(productId, unitOfMeasureId, request);
    }
}
