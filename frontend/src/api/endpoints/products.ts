import { apiRequest } from "../httpClient";
import type {
  CreateProductRequest,
  Page,
  Product,
  ProductUnit,
  UnitOfMeasure,
  UpdateProductRequest,
} from "../../types/api";

/** docs/API_DESIGN.md, sección 7.4. */

export function listProducts(params: {
  search?: string;
  active?: boolean;
  page?: number;
  size?: number;
}): Promise<Page<Product>> {
  return apiRequest<Page<Product>>("/products", { query: params });
}

export function createProduct(body: CreateProductRequest): Promise<Product> {
  return apiRequest<Product>("/products", { method: "POST", body });
}

export function updateProduct(id: string, body: UpdateProductRequest): Promise<Product> {
  return apiRequest<Product>(`/products/${id}`, { method: "PATCH", body });
}

export function setProductActive(id: string, active: boolean): Promise<Product> {
  return apiRequest<Product>(`/products/${id}/${active ? "activate" : "deactivate"}`, { method: "POST" });
}

export function listUnitsOfMeasure(): Promise<UnitOfMeasure[]> {
  return apiRequest<UnitOfMeasure[]>("/units-of-measure");
}

/** Alta de unidad en el catálogo global: ADMIN únicamente (más estricto que el resto de `products`). */
export function createUnitOfMeasure(body: { code: string; name: string }): Promise<UnitOfMeasure> {
  return apiRequest<UnitOfMeasure>("/units-of-measure", { method: "POST", body });
}

/** Edición del nombre: ADMIN únicamente (BR-050). El código es la clave de negocio y no se edita por esta vía. */
export function updateUnitOfMeasure(id: string, body: { name: string }): Promise<UnitOfMeasure> {
  return apiRequest<UnitOfMeasure>(`/units-of-measure/${id}`, { method: "PATCH", body });
}

export function listProductUnits(productId: string): Promise<ProductUnit[]> {
  return apiRequest<ProductUnit[]>(`/products/${productId}/units`);
}

export function addProductUnit(
  productId: string,
  body: { unitOfMeasureId: number; conversionFactorToBase: number },
): Promise<ProductUnit> {
  return apiRequest<ProductUnit>(`/products/${productId}/units`, { method: "POST", body });
}

export function updateProductUnitFactor(
  productId: string,
  unitOfMeasureId: string,
  conversionFactorToBase: number,
): Promise<ProductUnit> {
  return apiRequest<ProductUnit>(`/products/${productId}/units/${unitOfMeasureId}`, {
    method: "PATCH",
    body: { conversionFactorToBase },
  });
}
