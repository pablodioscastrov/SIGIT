package com.inventario.multisucursal.reports;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * BR-056: único punto del sistema que sabe convertir un {@link ReportSheet}
 * en bytes de `.xlsx` (Apache POI). Sin ninguna consulta ni regla de
 * autorización aquí — eso ya se resolvió antes de construir el
 * {@code ReportSheet} que recibe.
 *
 * <p>Fechas/horas se escriben como {@code LocalDateTime} (no
 * {@code java.util.Date} vía {@code Instant}) para que Excel muestre
 * exactamente los mismos dígitos UTC que persiste el backend, sin que la
 * zona horaria por defecto de la JVM que ejecuta el servidor los desplace en
 * silencio — por eso además el encabezado de cada columna de fecha/hora del
 * reporte incluye literalmente "(UTC)".
 */
@Component
public class ExcelReportWriter {

    public byte[] write(ReportSheet sheet) {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet poiSheet = workbook.createSheet("Reporte");

            CellStyle titleStyle = titleStyle(workbook);
            CellStyle metaStyle = metaStyle(workbook);
            CellStyle headerStyle = headerStyle(workbook);
            CellStyle dateStyle = dateStyle(workbook, "yyyy-mm-dd");
            CellStyle datetimeStyle = dateStyle(workbook, "yyyy-mm-dd hh:mm");
            CellStyle moneyStyle = numberStyle(workbook, "#,##0.00");
            CellStyle numberStyle = numberStyle(workbook, "#,##0.######");

            int rowIndex = 0;

            Row titleRow = poiSheet.createRow(rowIndex++);
            Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue(sheet.title());
            titleCell.setCellStyle(titleStyle);

            for (String line : sheet.metadataLines()) {
                Cell metaCell = poiSheet.createRow(rowIndex++).createCell(0);
                metaCell.setCellValue(line);
                metaCell.setCellStyle(metaStyle);
            }

            rowIndex++; // fila en blanco entre los metadatos y la tabla

            Row headerRow = poiSheet.createRow(rowIndex++);
            for (int column = 0; column < sheet.columns().size(); column++) {
                Cell cell = headerRow.createCell(column);
                cell.setCellValue(sheet.columns().get(column).header());
                cell.setCellStyle(headerStyle);
            }

            for (Object[] values : sheet.rows()) {
                Row row = poiSheet.createRow(rowIndex++);
                for (int column = 0; column < values.length; column++) {
                    writeCell(row.createCell(column), values[column], sheet.columns().get(column).type(),
                            dateStyle, datetimeStyle, moneyStyle, numberStyle);
                }
            }

            if (sheet.rows().isEmpty()) {
                poiSheet.createRow(rowIndex).createCell(0)
                        .setCellValue("Sin resultados para los filtros aplicados.");
            }

            for (int column = 0; column < sheet.columns().size(); column++) {
                poiSheet.autoSizeColumn(column);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            // Escribir a un ByteArrayOutputStream en memoria no falla por I/O real;
            // si POI lo hace de todas formas, es un error del servidor, no del cliente.
            throw new UncheckedIOException("No se pudo generar el archivo Excel.", e);
        }
    }

    private void writeCell(
            Cell cell, Object value, ReportSheet.ColumnType type,
            CellStyle dateStyle, CellStyle datetimeStyle, CellStyle moneyStyle, CellStyle numberStyle) {
        if (value == null) {
            return;
        }
        switch (type) {
            case NUMBER -> {
                cell.setCellValue(((Number) value).doubleValue());
                cell.setCellStyle(numberStyle);
            }
            case MONEY -> {
                cell.setCellValue(value instanceof BigDecimal bd ? bd.doubleValue() : ((Number) value).doubleValue());
                cell.setCellStyle(moneyStyle);
            }
            case DATE -> {
                cell.setCellValue((LocalDate) value);
                cell.setCellStyle(dateStyle);
            }
            case DATETIME -> {
                cell.setCellValue(toUtcLocalDateTime((Instant) value));
                cell.setCellStyle(datetimeStyle);
            }
            default -> cell.setCellValue(String.valueOf(value));
        }
    }

    private LocalDateTime toUtcLocalDateTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private CellStyle titleStyle(XSSFWorkbook workbook) {
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 14);
        CellStyle style = workbook.createCellStyle();
        style.setFont(font);
        return style;
    }

    private CellStyle metaStyle(XSSFWorkbook workbook) {
        Font font = workbook.createFont();
        font.setItalic(true);
        font.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
        CellStyle style = workbook.createCellStyle();
        style.setFont(font);
        return style;
    }

    private CellStyle headerStyle(XSSFWorkbook workbook) {
        Font font = workbook.createFont();
        font.setBold(true);
        CellStyle style = workbook.createCellStyle();
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private CellStyle dateStyle(XSSFWorkbook workbook, String pattern) {
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(workbook.createDataFormat().getFormat(pattern));
        return style;
    }

    private CellStyle numberStyle(XSSFWorkbook workbook, String pattern) {
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(workbook.createDataFormat().getFormat(pattern));
        return style;
    }
}
