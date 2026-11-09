package com.inventario.multisucursal.reports;

/** BR-056: bytes ya formateados de un reporte exportado, listos para servir como descarga. */
public record ExportedFile(String filename, byte[] content) {

    public static final String XLSX_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
}
