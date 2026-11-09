package com.inventario.multisucursal.reports;

import java.util.List;

/**
 * Forma neutral de un reporte tabular, independiente del formato de salida
 * (BR-056): la capa de consulta de cada reporte construye uno de estos, y
 * {@link ExcelReportWriter} es lo único que sabe convertirlo a bytes de
 * `.xlsx`. Un futuro exportador a PDF consumiría exactamente el mismo
 * {@code ReportSheet}, sin tocar ninguna consulta.
 *
 * @param title título del reporte, primera fila del archivo
 * @param metadataLines líneas de contexto bajo el título — filtros aplicados
 *        (rango de fechas, sucursal, etc.) y fecha de generación, para que el
 *        archivo sea legible por sí solo fuera de la aplicación
 * @param columns encabezados y tipo de cada columna, en orden
 * @param rows una fila por resultado; cada {@code Object[]} debe tener
 *        exactamente {@code columns.size()} elementos, en el mismo orden.
 *        {@code null} se escribe como celda vacía.
 */
public record ReportSheet(String title, List<String> metadataLines, List<ReportColumn> columns, List<Object[]> rows) {

    public record ReportColumn(String header, ColumnType type) {
    }

    /** {@code MONEY} se formatea igual que {@code NUMBER} (dos decimales, separador de miles) — la aplicación no usa símbolo de moneda en ninguna pantalla, así que el reporte tampoco inventa uno (consistencia con la UI/API). */
    public enum ColumnType {
        TEXT, NUMBER, MONEY, DATE, DATETIME
    }
}
