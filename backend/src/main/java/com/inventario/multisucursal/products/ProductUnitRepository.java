package com.inventario.multisucursal.products;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProductUnitRepository extends JpaRepository<ProductUnit, Long> {

    List<ProductUnit> findByProductId(Long productId);

    boolean existsByProductIdAndUnitOfMeasureId(Long productId, Long unitOfMeasureId);

    Optional<ProductUnit> findByProductIdAndUnitOfMeasureId(Long productId, Long unitOfMeasureId);
}
