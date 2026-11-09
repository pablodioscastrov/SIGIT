import { apiRequest } from "../httpClient";
import type { CreateSaleRequest, CreateSaleReturnRequest, Page, Price, PriceList, Sale, SaleReturnResponse, SaleStatus } from "../../types/api";

/** docs/API_DESIGN.md, sección 7.8. */

export function listSales(params: {
  branchId?: string;
  status?: SaleStatus;
  dateFrom?: string;
  dateTo?: string;
  page?: number;
  size?: number;
}): Promise<Page<Sale>> {
  return apiRequest<Page<Sale>>("/sales", { query: params });
}

export function getSale(id: string): Promise<Sale> {
  return apiRequest<Sale>(`/sales/${id}`);
}

export function createSale(body: CreateSaleRequest, idempotencyKey: string): Promise<Sale> {
  return apiRequest<Sale>("/sales", { method: "POST", body, idempotencyKey });
}

/** BR-052: devolución total o parcial de una venta confirmada — repone las líneas indicadas al inventario. */
export function createSaleReturn(saleId: string, body: CreateSaleReturnRequest, idempotencyKey: string): Promise<SaleReturnResponse> {
  return apiRequest<SaleReturnResponse>(`/sales/${saleId}/returns`, { method: "POST", body, idempotencyKey });
}

export function listPriceLists(params: { branchId?: string; active?: boolean } = {}): Promise<Page<PriceList>> {
  return apiRequest<Page<PriceList>>("/price-lists", { query: { ...params, size: 100 } });
}

export function listPrices(priceListId: string): Promise<Price[]> {
  return apiRequest<Price[]>(`/price-lists/${priceListId}/prices`);
}
