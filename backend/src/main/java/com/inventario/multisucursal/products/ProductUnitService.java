package com.inventario.multisucursal.products;

import com.inventario.multisucursal.common.exception.BusinessRuleViolationException;
import com.inventario.multisucursal.common.exception.ResourceConflictException;
import com.inventario.multisucursal.common.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * RF-011 / BR-011: unidades alternativas de un producto y su conversión
 * hacia la unidad base. La unidad base en sí no se gestiona aquí — la crea
 * {@link ProductService} al crear el producto y este servicio la trata como
 * inmutable (BR-011: "conversiones deben ser deterministas y validadas" — la
 * conversión de la base consigo misma, 1, no tiene sentido que cambie).
 */
@Service
public class ProductUnitService {

    private final ProductRepository productRepository;
    private final UnitOfMeasureRepository unitOfMeasureRepository;
    private final ProductUnitRepository productUnitRepository;

    public ProductUnitService(
            ProductRepository productRepository,
            UnitOfMeasureRepository unitOfMeasureRepository,
            ProductUnitRepository productUnitRepository) {
        this.productRepository = productRepository;
        this.unitOfMeasureRepository = unitOfMeasureRepository;
        this.productUnitRepository = productUnitRepository;
    }

    public List<ProductUnitResponse> list(Long productId) {
        requireProductExists(productId);
        List<ProductUnit> productUnits = productUnitRepository.findByProductId(productId);
        Map<Long, UnitOfMeasure> unitsById = unitOfMeasureRepository
                .findAllById(productUnits.stream().map(ProductUnit::getUnitOfMeasureId).toList())
                .stream()
                .collect(Collectors.toMap(UnitOfMeasure::getId, Function.identity()));

        return productUnits.stream()
                .map(productUnit -> ProductUnitResponse.from(productUnit, unitsById.get(productUnit.getUnitOfMeasureId())))
                .toList();
    }

    @Transactional
    public ProductUnitResponse add(Long productId, AddProductUnitRequest request) {
        requireProductExists(productId);
        UnitOfMeasure unit = unitOfMeasureRepository.findById(request.unitOfMeasureId())
                .orElseThrow(() -> new ResourceNotFoundException("UNIDAD_DE_MEDIDA_NO_ENCONTRADA", "Unidad de medida no encontrada."));

        if (productUnitRepository.existsByProductIdAndUnitOfMeasureId(productId, unit.getId())) {
            throw new ResourceConflictException("UNIDAD_YA_ASOCIADA", "Esta unidad ya está asociada al producto.");
        }

        ProductUnit productUnit = productUnitRepository.save(
                new ProductUnit(productId, unit.getId(), request.conversionFactorToBase(), false));
        return ProductUnitResponse.from(productUnit, unit);
    }

    @Transactional
    public ProductUnitResponse updateConversionFactor(Long productId, Long unitOfMeasureId, UpdateProductUnitConversionRequest request) {
        requireProductExists(productId);
        ProductUnit productUnit = productUnitRepository.findByProductIdAndUnitOfMeasureId(productId, unitOfMeasureId)
                .orElseThrow(() -> new ResourceNotFoundException("UNIDAD_NO_ASOCIADA", "Esta unidad no está asociada al producto."));

        if (productUnit.isBaseUnit()) {
            throw new BusinessRuleViolationException(
                    "UNIDAD_BASE_INMUTABLE", "El factor de conversión de la unidad base no se puede modificar.");
        }

        productUnit.updateConversionFactor(request.conversionFactorToBase());
        UnitOfMeasure unit = unitOfMeasureRepository.findById(unitOfMeasureId).orElseThrow();
        return ProductUnitResponse.from(productUnit, unit);
    }

    private void requireProductExists(Long productId) {
        if (!productRepository.existsById(productId)) {
            throw new ResourceNotFoundException("PRODUCTO_NO_ENCONTRADO", "Producto no encontrado.");
        }
    }
}
