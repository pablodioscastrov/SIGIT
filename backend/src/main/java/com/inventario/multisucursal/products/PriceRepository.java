package com.inventario.multisucursal.products;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PriceRepository extends JpaRepository<Price, Long> {

    Optional<Price> findByPriceListIdAndProductIdAndValidToIsNull(Long priceListId, Long productId);

    List<Price> findByPriceListIdAndValidToIsNull(Long priceListId);

    List<Price> findByPriceListId(Long priceListId);

    /** Resolución en lote del precio vigente de varios productos en una misma lista (BR-051, tabla de productos). */
    List<Price> findByPriceListIdAndProductIdInAndValidToIsNull(Long priceListId, Collection<Long> productIds);
}
