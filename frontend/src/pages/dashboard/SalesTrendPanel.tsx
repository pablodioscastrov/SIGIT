import { useQuery } from "@tanstack/react-query";
import { getSalesSummary } from "../../api/endpoints/dashboard";
import { queryKeys } from "../../api/queryClient";
import { AsyncBoundary } from "../../components/state/states";
import { formatCurrency } from "../../lib/currency";
import { MiniBarChart } from "./MiniBarChart";

/**
 * Ventas del mes actual vs. anteriores (RF-031, BR-039). La ventana (mes
 * actual + `months` anteriores) y la agregación son responsabilidad del
 * backend; aquí solo se dibuja lo que ya llegó calculado — el gráfico de
 * barras es de presentación, nunca la fuente de la cifra.
 */
export function SalesTrendPanel({ branchId, months }: { branchId: string; months: number }) {
  const params = { branchId, months };
  const query = useQuery({ queryKey: queryKeys.dashboardSales(params), queryFn: () => getSalesSummary(params) });

  return (
    <section className="panel dashboard-panel">
      <h2>Ventas: mes actual vs. anteriores</h2>
      <AsyncBoundary isLoading={query.isPending} error={query.error} data={query.data} onRetry={() => query.refetch()}>
        {(trend) => {
          const bars = [...trend.previousMonths, trend.currentMonth].map((month) => ({ label: month.period, value: Number(month.totalSales) }));
          return (
            <>
              <MiniBarChart bars={bars} formatValue={formatCurrency} />
              <dl className="detail-grid">
                <div>
                  <dt>Mes actual ({trend.currentMonth.period})</dt>
                  <dd>
                    {formatCurrency(trend.currentMonth.totalSales)} ({trend.currentMonth.salesCount} venta(s))
                  </dd>
                </div>
                <div>
                  <dt>Variación vs. mes anterior</dt>
                  <dd>
                    {trend.growthVsPreviousMonthPercentage === null ? (
                      <span className="state__hint">No calculable (el mes anterior no tuvo ventas)</span>
                    ) : (
                      <span className={`badge ${trend.growthVsPreviousMonthPercentage >= 0 ? "badge--ok" : "badge--warn"}`}>
                        {trend.growthVsPreviousMonthPercentage >= 0 ? "+" : ""}
                        {trend.growthVsPreviousMonthPercentage}%
                      </span>
                    )}
                  </dd>
                </div>
              </dl>
            </>
          );
        }}
      </AsyncBoundary>
    </section>
  );
}
