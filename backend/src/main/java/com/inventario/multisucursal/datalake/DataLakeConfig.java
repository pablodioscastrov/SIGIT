package com.inventario.multisucursal.datalake;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;

/**
 * Configuración del cliente de Azure Blob Storage.
 *
 * <p>Solo se instancia cuando {@code app.datalake.enabled=true} y
 * {@code app.datalake.connection-string} tiene valor. Si falta alguna de
 * las dos condiciones los beans no se crean y el módulo queda inactivo sin
 * afectar el resto de la aplicación.
 */
@Configuration
@EnableConfigurationProperties(DataLakeProperties.class)
public class DataLakeConfig {

    private static final Logger log = LoggerFactory.getLogger(DataLakeConfig.class);

    @Bean
    @ConditionalOnProperty(name = "app.datalake.enabled", havingValue = "true")
    public BlobContainerClient blobContainerClient(DataLakeProperties props) {
        log.info("Data Lake habilitado — conectando a: {} (contenedor: {})",
                sanitizeConnectionString(props.getConnectionString()),
                props.getContainerName());

        BlobServiceClient serviceClient = new BlobServiceClientBuilder()
                .connectionString(props.getConnectionString())
                .buildClient();

        BlobContainerClient containerClient = serviceClient
                .getBlobContainerClient(props.getContainerName());

        // Crea el contenedor si no existe (idempotente)
        if (!containerClient.exists()) {
            containerClient.create();
            log.info("Contenedor '{}' creado en el Data Lake.", props.getContainerName());
        }

        return containerClient;
    }

    /** Oculta la clave de la connection string en logs para no exponer secretos. */
    private String sanitizeConnectionString(String cs) {
        if (cs == null) return "(null)";
        if (cs.contains("AccountKey=")) {
            return cs.replaceAll("AccountKey=[^;]+", "AccountKey=***");
        }
        return cs;
    }
}
