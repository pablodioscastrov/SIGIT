import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import type { FormEvent } from "react";
import { createProduct, updateProduct } from "../../api/endpoints/products";
import { queryPrefixes } from "../../api/queryClient";
import { Field, FormErrorMessage, SelectField, TextAreaField } from "../../components/form/Field";
import { toFormErrors } from "../../components/form/formErrors";
import { Modal } from "../../components/ui/Modal";
import type { Product, UnitOfMeasure } from "../../types/api";

/**
 * Alta y edición de producto en un mismo diálogo, porque comparten campos.
 *
 * <p>En edición, `sku` y unidad base quedan como datos fijos (inmutables por
 * decisión de modelo, BR-026). El precio de venta (BR-057) y el stock mínimo
 * (BR-059) sí se editan después de creado: el precio se fija como el nuevo
 * precio vigente en la lista de precios global por defecto, cerrando el
 * anterior; el stock mínimo solo cambia el valor de siembra para sucursales
 * que aún no tengan `Inventory` de este producto, sin tocar las que ya lo
 * tienen.
 */
export function ProductFormDialog({
  product,
  units,
  onClose,
}: {
  product?: Product;
  units: UnitOfMeasure[];
  onClose: () => void;
}) {
  const isEdit = product !== undefined;
  const queryClient = useQueryClient();
  const [sku, setSku] = useState(product?.sku ?? "");
  const [name, setName] = useState(product?.name ?? "");
  const [description, setDescription] = useState(product?.description ?? "");
  const [baseUnitId, setBaseUnitId] = useState(product?.baseUnitOfMeasureId ?? "");
  const [minimumStock, setMinimumStock] = useState(product ? String(product.minimumStock) : "");
  const [unitPrice, setUnitPrice] = useState(product?.salePrice != null ? String(product.salePrice) : "");
  const [localErrors, setLocalErrors] = useState<Record<string, string>>({});

  const mutation = useMutation({
    mutationFn: () =>
      isEdit
        ? updateProduct(product.id, {
            name: name.trim(),
            description: description.trim() || null,
            unitPrice: Number(unitPrice),
            minimumStock: Number(minimumStock),
          })
        : createProduct({
            sku: sku.trim(),
            name: name.trim(),
            description: description.trim() || null,
            baseUnitOfMeasureId: Number(baseUnitId),
            minimumStock: Number(minimumStock),
            unitPrice: Number(unitPrice),
          }),
    onSuccess: () => {
      // Revalidar después de mutar: la lista vuelve a consultarse contra la
      // API, que sigue siendo la fuente de verdad.
      void queryClient.invalidateQueries({ queryKey: queryPrefixes.products });
      onClose();
    },
  });

  const serverErrors = mutation.error ? toFormErrors(mutation.error) : { fields: {} as Record<string, string> };
  const errorFor = (field: string) => localErrors[field] ?? serverErrors.fields[field];

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    // Validación solo de forma (obligatorio/longitud); la semántica de negocio
    // —SKU repetido, unidad inexistente— la decide el backend.
    const errors: Record<string, string> = {};
    if (!isEdit && !sku.trim()) errors.sku = "El SKU es obligatorio.";
    if (!name.trim()) errors.name = "El nombre es obligatorio.";
    if (!isEdit && !baseUnitId) errors.baseUnitOfMeasureId = "Selecciona la unidad base.";
    if (!minimumStock.trim() || Number(minimumStock) < 0) {
      errors.minimumStock = "Indica el stock mínimo (0 o mayor).";
    }
    if (!unitPrice.trim() || Number(unitPrice) <= 0) {
      errors.unitPrice = "Indica el precio de venta (mayor que 0).";
    }
    setLocalErrors(errors);
    if (Object.keys(errors).length > 0) return;
    mutation.mutate();
  }

  return (
    <Modal title={isEdit ? `Editar ${product.sku}` : "Nuevo producto"} onClose={onClose}>
      <form onSubmit={handleSubmit} noValidate>
        {isEdit ? (
          <p className="state__hint">
            SKU {product.sku} · unidad base y SKU no son modificables.
          </p>
        ) : (
          <Field
            id="product-sku"
            label="SKU"
            value={sku}
            maxLength={50}
            onChange={(event) => setSku(event.target.value)}
            error={errorFor("sku")}
          />
        )}

        <Field
          id="product-name"
          label="Nombre"
          value={name}
          maxLength={150}
          onChange={(event) => setName(event.target.value)}
          error={errorFor("name")}
        />

        <TextAreaField
          id="product-description"
          label="Descripción"
          value={description ?? ""}
          maxLength={1000}
          rows={3}
          onChange={(event) => setDescription(event.target.value)}
          error={errorFor("description")}
        />

        {!isEdit ? (
          <SelectField
            id="product-base-unit"
            label="Unidad base"
            value={baseUnitId}
            onChange={(event) => setBaseUnitId(event.target.value)}
            error={errorFor("baseUnitOfMeasureId")}
          >
            <option value="">Selecciona…</option>
            {units.map((unit) => (
              <option key={unit.id} value={unit.id}>
                {unit.code} — {unit.name}
              </option>
            ))}
          </SelectField>
        ) : null}

        <Field
          id="product-minimum-stock"
          label="Stock mínimo"
          inputMode="decimal"
          value={minimumStock}
          onChange={(event) => setMinimumStock(event.target.value)}
          error={errorFor("minimumStock")}
        />
        <p className="state__hint">
          {isEdit
            ? "Cambiarlo no afecta el inventario de sucursales que ya reciben este producto: solo aplica como valor de arranque a las que aún no lo tengan."
            : "Umbral que usarán el estado de reabastecimiento y las alertas de stock en cada sucursal que reciba este producto por primera vez."}
        </p>

        <Field
          id="product-unit-price"
          label="Precio de venta"
          inputMode="decimal"
          value={unitPrice}
          onChange={(event) => setUnitPrice(event.target.value)}
          error={errorFor("unitPrice")}
        />
        <p className="state__hint">
          {isEdit
            ? "Cambiarlo no altera ventas ya registradas: se fija como el nuevo precio vigente para las próximas."
            : "Precio con el que este producto queda disponible de inmediato para nuevas ventas, sin configurar ninguna lista de precios aparte."}
        </p>

        <FormErrorMessage error={serverErrors.general ? mutation.error : null} />

        <div className="modal__actions">
          <button type="button" onClick={onClose} disabled={mutation.isPending}>
            Cancelar
          </button>
          <button type="submit" disabled={mutation.isPending}>
            {mutation.isPending ? "Guardando…" : "Guardar"}
          </button>
        </div>
      </form>
    </Modal>
  );
}
