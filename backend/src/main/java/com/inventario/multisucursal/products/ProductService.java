package com.inventario.multisucursal.products;

import com.inventario.multisucursal.common.exception.ResourceConflictException;
import com.inventario.multisucursal.common.exception.ResourceNotFoundException;
import com.inventario.multisucursal.common.web.PageResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RF-005 (docs/PROJECT_BRIEF.md, sección 3.2): CRUD de productos. Toda
 * escritura está restringida a OPERATOR/ADMIN a nivel de
 * {@link ProductController} (docs/API_DESIGN.md, sección 6) — este servicio
 * no vuelve a comprobar el rol, solo las reglas propias del recurso.
 */
@Service
public class ProductService {

    /** BR-051: nombre con el que se crea la lista de precios global por defecto, solo si todavía no existe ninguna. */
    private static final String DEFAULT_PRICE_LIST_NAME = "Lista General";

    private final ProductRepository productRepository;
    private final UnitOfMeasureRepository unitOfMeasureRepository;
    private final ProductUnitRepository productUnitRepository;
    private final PriceListRepository priceListRepository;
    private final PriceRepository priceRepository;

    public ProductService(
            ProductRepository productRepository,
            UnitOfMeasureRepository unitOfMeasureRepository,
            ProductUnitRepository productUnitRepository,
            PriceListRepository priceListRepository,
            PriceRepository priceRepository) {
        this.productRepository = productRepository;
        this.unitOfMeasureRepository = unitOfMeasureRepository;
        this.productUnitRepository = productUnitRepository;
        this.priceListRepository = priceListRepository;
        this.priceRepository = priceRepository;
    }

    @Transactional
    public ProductResponse create(CreateProductRequest request) {
        if (productRepository.existsBySku(request.sku())) {
            throw new ResourceConflictException("SKU_YA_EXISTE", "Ya existe un producto con ese SKU.");
        }
        UnitOfMeasure baseUnit = unitOfMeasureRepository.findById(request.baseUnitOfMeasureId())
                .orElseThrow(() -> new ResourceNotFoundException("UNIDAD_DE_MEDIDA_NO_ENCONTRADA", "Unidad de medida no encontrada."));

        Product product = productRepository.save(
                new Product(request.sku(), request.name(), request.description(), baseUnit.getId(), request.minimumStock()));

        // BR-011 / docs/DOMAIN_MODEL.md, sección 2.6: la unidad base se crea
        // automáticamente con factor 1 - garantiza que siempre exista una
        // única unidad base inequívoca sin depender de una segunda llamada
        // manual a POST /products/{id}/units.
        productUnitRepository.save(new ProductUnit(product.getId(), baseUnit.getId(), BigDecimal.ONE, true));

        // BR-051: el precio de venta pedido en el alta se fija como el primer
        // Price vigente del producto en la lista de precios global por
        // defecto — la misma que SaleService.resolvePriceList usaría como
        // último recurso (BR-030) si una venta no especifica lista, así que
        // una venta nueva resuelve este precio automáticamente sin ninguna
        // configuración adicional del lado de listas de precios.
        BigDecimal salePrice = setDefaultListPrice(product.getId(), request.unitPrice());

        return ProductResponse.from(product, salePrice);
    }

    public ProductResponse getById(Long id) {
        Product product = findOrThrow(id);
        return ProductResponse.from(product, resolveSalePrices(List.of(product)).get(product.getId()));
    }

    public PageResponse<ProductResponse> list(String search, Boolean active, Pageable pageable) {
        Page<Product> products = productRepository.search(search, active, pageable);
        Map<Long, BigDecimal> salePrices = resolveSalePrices(products.getContent());
        return PageResponse.from(products.map(product -> ProductResponse.from(product, salePrices.get(product.getId()))));
    }

    /** BR-051: precio vigente de cada producto en la lista de precios global por defecto, si existe alguna. */
    private Map<Long, BigDecimal> resolveSalePrices(List<Product> products) {
        if (products.isEmpty()) {
            return Map.of();
        }
        return priceListRepository.findFirstByBranchIdIsNullAndActiveTrue()
                .map(defaultPriceList -> {
                    List<Long> productIds = products.stream().map(Product::getId).toList();
                    return priceRepository.findByPriceListIdAndProductIdInAndValidToIsNull(defaultPriceList.getId(), productIds).stream()
                            .collect(Collectors.toMap(Price::getProductId, Price::getUnitPrice));
                })
                .orElseGet(Map::of);
    }

    @Transactional
    public ProductResponse update(Long id, UpdateProductRequest request) {
        Product product = findOrThrow(id);
        product.updateDetails(request.name(), request.description(), request.minimumStock());
        // BR-057: a diferencia de nombre/descripción, el precio no se sobrescribe
        // sobre la fila vigente — se cierra y se inserta una nueva, mismo
        // versionado que PriceListService.setPrice (BR-019).
        BigDecimal salePrice = setDefaultListPrice(product.getId(), request.unitPrice());
        return ProductResponse.from(product, salePrice);
    }

    /** BR-051/BR-057: fija el precio de venta como el nuevo {@code Price} vigente en la lista de precios global por defecto, cerrando el anterior si existía. */
    private BigDecimal setDefaultListPrice(Long productId, BigDecimal unitPrice) {
        PriceList defaultPriceList = priceListRepository.findFirstByBranchIdIsNullAndActiveTrue()
                .orElseGet(() -> priceListRepository.save(new PriceList(DEFAULT_PRICE_LIST_NAME, null)));
        priceRepository.findByPriceListIdAndProductIdAndValidToIsNull(defaultPriceList.getId(), productId)
                .ifPresent(current -> {
                    current.close();
                    // saveAndFlush: el UPDATE que cierra el precio anterior debe llegar a la base
                    // antes del INSERT del nuevo precio, o el índice único uq_price_list_product_current
                    // (price_list_id, product_id) WHERE valid_to IS NULL choca porque Hibernate agrupa
                    // los INSERT antes que los UPDATE dentro de un mismo flush.
                    priceRepository.saveAndFlush(current);
                });
        return priceRepository.save(new Price(defaultPriceList.getId(), productId, unitPrice)).getUnitPrice();
    }

    @Transactional
    public ProductResponse activate(Long id) {
        Product product = findOrThrow(id);
        product.activate();
        return ProductResponse.from(product, resolveSalePrices(List.of(product)).get(product.getId()));
    }

    @Transactional
    public ProductResponse deactivate(Long id) {
        Product product = findOrThrow(id);
        product.deactivate();
        return ProductResponse.from(product, resolveSalePrices(List.of(product)).get(product.getId()));
    }

    private Product findOrThrow(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("PRODUCTO_NO_ENCONTRADO", "Producto no encontrado."));
    }
}
