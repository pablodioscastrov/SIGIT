package com.inventario.multisucursal.datalake;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Propiedades del Data Lake (Azurite en local, ADLS Gen2 en producción).
 *
 * <p>Se leen desde {@code application.yml} bajo el prefijo
 * {@code app.datalake}. Cuando {@code enabled = false} el módulo arranca
 * pero no hace nada — ninguna operación de negocio falla por la
 * indisponibilidad del Data Lake (ADR-007: el canal nunca puede romper una
 * operación de negocio).
 */
@ConfigurationProperties(prefix = "app.datalake")
public class DataLakeProperties {

    /**
     * Habilita o deshabilita la escritura al Data Lake.
     * Default {@code false} para que el sistema siga funcionando sin Azurite
     * en entornos que no lo tengan configurado.
     */
    private boolean enabled = false;

    /**
     * Connection string de Azure Blob Storage / Azurite.
     * Ejemplo local: {@code UseDevelopmentStorage=true;DevelopmentStorageProxyUri=http://azurite:10000}
     * Ejemplo Azure real: {@code DefaultEndpointsProtocol=https;AccountName=...;AccountKey=...}
     */
    private String connectionString;

    /**
     * Nombre del contenedor de blobs donde se almacenan los eventos.
     * Se crea automáticamente si no existe (ver {@link DataLakeContainerInitializer}).
     */
    private String containerName = "datalake-sigit";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getConnectionString() {
        return connectionString;
    }

    public void setConnectionString(String connectionString) {
        this.connectionString = connectionString;
    }

    public String getContainerName() {
        return containerName;
    }

    public void setContainerName(String containerName) {
        this.containerName = containerName;
    }
}
