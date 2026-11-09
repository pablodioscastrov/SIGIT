package com.inventario.multisucursal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @EnableScheduling} habilita el latido del canal SSE
 * ({@code EventBroadcaster.heartbeat}), único trabajo programado del sistema:
 * sin él, una conexión ya muerta seguiría ocupando memoria hasta agotar su
 * timeout, y los proxies intermedios cortarían los canales inactivos.
 *
 * {@code @EnableAsync} habilita la ejecución asíncrona del sumidero del
 * Data Lake ({@code DataLakeSink}), de modo que la escritura a Azurite/ADLS
 * no bloquea el hilo HTTP que procesó la operación de negocio.
 */
@EnableAsync
@EnableScheduling
@SpringBootApplication
public class InventarioApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventarioApplication.class, args);
    }
}
