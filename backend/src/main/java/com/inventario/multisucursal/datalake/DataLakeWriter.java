package com.inventario.multisucursal.datalake;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.specialized.BlockBlobClient;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Escritor de bajo nivel hacia Azure Blob Storage / Azurite.
 *
 * <p>Cada evento se persiste como un archivo JSON independiente en la zona de
 * aterrizaje ({@code landing/}) siguiendo la estructura de partición temporal
 * estándar de Data Lake:
 *
 * <pre>
 * landing/{tipo}/{año}/{mes}/{día}/{timestamp-ms}-{id}.json
 * </pre>
 *
 * <p>Esta estructura permite a Trino (o cualquier motor analítico) leer datos
 * por rango de fechas sin escanear todo el contenedor, y facilita el
 * mantenimiento (borrar una partición entera es trivial).
 *
 * <p>Solo se instancia cuando el {@link BlobContainerClient} está disponible
 * (es decir, cuando {@code app.datalake.enabled=true}). Nunca lanza
 * excepciones al hilo llamador — cualquier fallo se registra en el log pero
 * no interrumpe la operación de negocio.
 */
@Component
@ConditionalOnBean(BlobContainerClient.class)
public class DataLakeWriter {

    private static final Logger log = LoggerFactory.getLogger(DataLakeWriter.class);
    private static final DateTimeFormatter YEAR  = DateTimeFormatter.ofPattern("yyyy").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MM").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DAY   = DateTimeFormatter.ofPattern("dd").withZone(ZoneOffset.UTC);

    private final BlobContainerClient containerClient;
    private final ObjectMapper objectMapper;

    public DataLakeWriter(BlobContainerClient containerClient, ObjectMapper objectMapper) {
        this.containerClient = containerClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Escribe un objeto como JSON en la zona {@code landing/} del Data Lake.
     *
     * @param eventType tipo lógico del evento (e.g. {@code "inventory-movement"},
     *                  {@code "sale"}, {@code "transfer"})
     * @param id        identificador único del registro (para generar el nombre del blob)
     * @param payload   objeto a serializar; debe ser serializable por Jackson
     */
    public void write(String eventType, String id, Object payload) {
        Instant now = Instant.now();
        String blobName = buildBlobName(eventType, id, now);

        try {
            byte[] json = objectMapper.writeValueAsBytes(payload);
            BlockBlobClient blobClient = containerClient.getBlobClient(blobName).getBlockBlobClient();

            BlobHttpHeaders headers = new BlobHttpHeaders().setContentType("application/json");
            blobClient.upload(new ByteArrayInputStream(json), json.length, true);
            blobClient.setHttpHeaders(headers);

            log.debug("Data Lake — blob escrito: {}", blobName);

        } catch (Exception e) {
            // El Data Lake es un repositorio secundario: nunca interrumpe la operación principal.
            log.error("Data Lake — error al escribir blob '{}': {}", blobName, e.getMessage(), e);
        }
    }

    /**
     * Construye la ruta del blob siguiendo el patrón de partición temporal:
     * {@code landing/{tipo}/{año}/{mes}/{día}/{timestamp-ms}-{id}.json}
     */
    private String buildBlobName(String eventType, String id, Instant now) {
        return String.format("landing/%s/%s/%s/%s/%d-%s.json",
                eventType,
                YEAR.format(now),
                MONTH.format(now),
                DAY.format(now),
                now.toEpochMilli(),
                id);
    }
}
