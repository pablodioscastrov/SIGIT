import { Navigate, Route, Routes } from "react-router-dom";
import { AppLayout } from "./components/layout/AppLayout";
import { ProtectedRoute, RequireRole } from "./routes/ProtectedRoute";
import { LoginPage } from "./pages/LoginPage";
import { BranchesPage } from "./pages/BranchesPage";
import { ProductsPage } from "./pages/products/ProductsPage";
import { UnitsOfMeasurePage } from "./pages/products/UnitsOfMeasurePage";
import { InventoryPage } from "./pages/inventory/InventoryPage";
import { MovementsPage } from "./pages/inventory/MovementsPage";
import { StockAlertsPage } from "./pages/inventory/StockAlertsPage";
import { SuppliersPage } from "./pages/suppliers/SuppliersPage";
import { PurchaseOrdersPage } from "./pages/purchases/PurchaseOrdersPage";
import { NewPurchaseOrderPage } from "./pages/purchases/NewPurchaseOrderPage";
import { PurchaseOrderDetailPage } from "./pages/purchases/PurchaseOrderDetailPage";
import { SalesPage } from "./pages/sales/SalesPage";
import { NewSalePage } from "./pages/sales/NewSalePage";
import { SaleDetailPage } from "./pages/sales/SaleDetailPage";
import { TransfersPage } from "./pages/transfers/TransfersPage";
import { NewTransferRequestPage } from "./pages/transfers/NewTransferRequestPage";
import { TransferDetailPage } from "./pages/transfers/TransferDetailPage";
import { RoutesPage } from "./pages/logistics/RoutesPage";
import { LogisticsCompliancePage } from "./pages/logistics/LogisticsCompliancePage";
import { DashboardPage } from "./pages/dashboard/DashboardPage";
import { BranchComparisonPage } from "./pages/dashboard/BranchComparisonPage";
import { UsersPage } from "./pages/users/UsersPage";
import { ForbiddenPage, NotFoundPage } from "./pages/SimplePages";

/**
 * Mapa de rutas. Todo lo que cuelga de `ProtectedRoute` exige sesión; lo que
 * además cuelga de `RequireRole` exige un rol — recordando siempre que ambas
 * guardas son de navegación, y que quien autoriza de verdad es el backend.
 */
export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />

      <Route element={<ProtectedRoute />}>
        <Route element={<AppLayout />}>
          {/* Sin módulo "Inicio" propio (por instrucción explícita, no aportaba nada
              más que un saludo estático) — la raíz redirige directo al Dashboard,
              landing útil para cualquier rol autenticado. */}
          <Route index element={<Navigate to="/dashboard" replace />} />
          <Route path="/dashboard" element={<DashboardPage />} />
          <Route path="/sucursales" element={<BranchesPage />} />
          <Route path="/inventario" element={<InventoryPage />} />
          <Route path="/inventario/movimientos" element={<MovementsPage />} />
          <Route path="/inventario/alertas" element={<StockAlertsPage />} />
          <Route path="/productos" element={<ProductsPage />} />
          <Route path="/productos/unidades" element={<UnitsOfMeasurePage />} />
          {/* CRUD completo abierto a cualquier rol autenticado (BR-049) — no
              hace falta guarda de rol, a diferencia del resto de módulos. */}
          <Route path="/proveedores" element={<SuppliersPage />} />
          <Route path="/compras" element={<PurchaseOrdersPage />} />
          <Route path="/compras/:id" element={<PurchaseOrderDetailPage />} />
          <Route path="/ventas" element={<SalesPage />} />
          <Route path="/ventas/:id" element={<SaleDetailPage />} />
          <Route path="/transferencias" element={<TransfersPage />} />
          {/* Solicitar una transferencia lo puede hacer cualquiera de los tres
              roles (OPERATOR/MANAGER/ADMIN) — no hace falta guarda de rol
              aquí, a diferencia de compras/ventas donde MANAGER queda fuera. */}
          <Route path="/transferencias/nueva" element={<NewTransferRequestPage />} />
          <Route path="/transferencias/:id" element={<TransferDetailPage />} />
          <Route path="/logistica/rutas" element={<RoutesPage />} />
          <Route path="/logistica/cumplimiento" element={<LogisticsCompliancePage />} />

          {/* Crear orden de compra o venta es una acción de escritura de pleno
              derecho, no una vista mixta como /productos o /inventario: sin
              esta guarda, un rol sin permiso llegaría por URL a un formulario
              que parece operativo y solo fallaría con 403 al enviarlo.
              Compras y ventas admiten MANAGER (mismas capacidades que ADMIN,
              BR-047/BR-053). */}
          <Route element={<RequireRole roles={["OPERATOR", "MANAGER", "ADMIN"]} />}>
            <Route path="/compras/nueva" element={<NewPurchaseOrderPage />} />
            <Route path="/ventas/nueva" element={<NewSalePage />} />
          </Route>

          <Route element={<RequireRole roles={["ADMIN"]} />}>
            <Route path="/usuarios" element={<UsersPage />} />
          </Route>

          {/* RF-035: la comparativa entre sucursales es exclusivamente para
              perfiles administrativos — un OPERATOR que fuerce la URL cae en
              "Sin permiso", no en una versión reducida de la misma pantalla. */}
          <Route element={<RequireRole roles={["MANAGER", "ADMIN"]} />}>
            <Route path="/dashboard/comparativa" element={<BranchComparisonPage />} />
          </Route>

          <Route path="/sin-permiso" element={<ForbiddenPage />} />
          <Route path="*" element={<NotFoundPage />} />
        </Route>
      </Route>
    </Routes>
  );
}
