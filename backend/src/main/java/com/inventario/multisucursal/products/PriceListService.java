package com.inventario.multisucursal.products;

import com.inventario.multisucursal.common.exception.BadRequestException;
import com.inventario.multisucursal.common.exception.ResourceConflictException;
import com.inventario.multisucursal.common.exception.ResourceNotFoundException;
import com.inventario.multisucursal.common.web.PageResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Listas de precios (RF-020; docs/DOMAIN_MODEL.md, secciones 2.13/2.14;
 * decisión 3.4; BR-019). Lectura abierta; escritura ADMIN-only
 * (docs/API_DESIGN.md, sección 6: "fijación de precios es administrativa").
 */
@Service
public class PriceListService {

    private final PriceListRepository priceListRepository;
    private final PriceRepository priceRepository;
    private final ProductRepository productRepository;

    public PriceListService(PriceListRepository priceListRepository, PriceRepository priceRepository, ProductRepository productRepository) {
        this.priceListRepository = priceListRepository;
        this.priceRepository = priceRepository;
        this.productRepository = productRepository;
    }

    @Transactional
    public PriceListResponse create(CreatePriceListRequest request) {
        if (priceListRepository.existsByNameAndBranch(request.name(), request.branchId())) {
            throw new ResourceConflictException("LISTA_PRECIOS_YA_EXISTE", "Ya existe una lista de precios con ese nombre para esa sucursal.");
        }
        return PriceListResponse.from(priceListRepository.save(new PriceList(request.name(), request.branchId())));
    }

    public PageResponse<PriceListResponse> list(Long branchId, Boolean active, Pageable pageable) {
        return PageResponse.from(priceListRepository.search(branchId, active, pageable).map(PriceListResponse::from));
    }

    public List<PriceResponse> listPrices(Long priceListId, boolean includeHistory) {
        requirePriceList(priceListId);
        List<Price> prices = includeHistory
                ? priceRepository.findByPriceListId(priceListId)
                : priceRepository.findByPriceListIdAndValidToIsNull(priceListId);
        return prices.stream().map(PriceResponse::from).toList();
    }

    /**
     * Fija un nuevo precio vigente (cierra el anterior si existe) — docs/API_DESIGN.md,
     * sección 7.8; decisión 3.4. {@code Idempotency-Key} se exige estructuralmente
     * (400 si falta) pero no se deduplica en esta fase: no está entre las
     * pruebas mínimas pedidas para `sales`, que es lo que motivó esta pieza
     * (solo se necesitaba para poder fijar precios y probar el flujo de venta).
     */
    @Transactional
    public PriceResponse setPrice(Long priceListId, SetPriceRequest request, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BadRequestException("IDEMPOTENCY_KEY_REQUERIDO", "El encabezado Idempotency-Key es obligatorio.");
        }
        PriceList priceList = requirePriceList(priceListId);
        if (!productRepository.existsById(request.productId())) {
            throw new ResourceNotFoundException("PRODUCTO_NO_ENCONTRADO", "Producto no encontrado.");
        }
        priceRepository.findByPriceListIdAndProductIdAndValidToIsNull(priceListId, request.productId())
                .ifPresent(current -> {
                    current.close();
                    priceRepository.save(current);
                });
        return PriceResponse.from(priceRepository.save(new Price(priceList.getId(), request.productId(), request.unitPrice())));
    }

    private PriceList requirePriceList(Long id) {
        return priceListRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("LISTA_PRECIOS_NO_ENCONTRADA", "Lista de precios no encontrada."));
    }
}
