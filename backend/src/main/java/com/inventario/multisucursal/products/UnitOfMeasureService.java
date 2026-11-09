package com.inventario.multisucursal.products;

import com.inventario.multisucursal.common.exception.ResourceConflictException;
import com.inventario.multisucursal.common.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * docs/API_DESIGN.md, sección 7.4. Catálogo global de unidades de medida —
 * sin baja lógica (ver {@link UnitOfMeasure}); sí admite editar el nombre
 * (BR-050), ADMIN-only igual que la creación.
 */
@Service
public class UnitOfMeasureService {

    private final UnitOfMeasureRepository unitOfMeasureRepository;

    public UnitOfMeasureService(UnitOfMeasureRepository unitOfMeasureRepository) {
        this.unitOfMeasureRepository = unitOfMeasureRepository;
    }

    public List<UnitOfMeasureResponse> list() {
        return unitOfMeasureRepository.findAll().stream().map(UnitOfMeasureResponse::from).toList();
    }

    @Transactional
    public UnitOfMeasureResponse create(CreateUnitOfMeasureRequest request) {
        if (unitOfMeasureRepository.existsByCode(request.code())) {
            throw new ResourceConflictException("CODIGO_UNIDAD_YA_EXISTE", "Ya existe una unidad de medida con ese código.");
        }
        UnitOfMeasure unit = new UnitOfMeasure(request.code(), request.name());
        return UnitOfMeasureResponse.from(unitOfMeasureRepository.save(unit));
    }

    @Transactional
    public UnitOfMeasureResponse update(Long id, UpdateUnitOfMeasureRequest request) {
        UnitOfMeasure unit = unitOfMeasureRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("UNIDAD_DE_MEDIDA_NO_ENCONTRADA", "Unidad de medida no encontrada."));
        unit.updateDetails(request.name());
        return UnitOfMeasureResponse.from(unit);
    }
}
