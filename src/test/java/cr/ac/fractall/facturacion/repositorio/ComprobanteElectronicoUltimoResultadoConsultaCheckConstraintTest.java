package cr.ac.fractall.facturacion.repositorio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Prueba de la restricción {@code CHECK} sobre {@code comprobante_electronico.ultimo_resultado_consulta}
 * (columna añadida por V12, restricción ampliada por V21 para aceptar {@code ERROR_CONFIGURACION}).
 *
 * <p>Usa {@link JdbcTemplate} con SQL crudo -- deliberadamente sin pasar por JPA/Hibernate ni por
 * {@code TenantContext} -- porque lo único que esta prueba necesita observar es el comportamiento
 * del motor Postgres real ante la restricción, no ninguna regla de negocio de la capa de servicio.
 * El grafo mínimo de filas ({@code usuario} → {@code empresa} → {@code cliente} → {@code factura} →
 * {@code comprobante_electronico}) se arma a mano solo para satisfacer las FK NOT NULL de
 * {@code comprobante_electronico}, siguiendo el esquema real (ver V1/V2/V4).
 */
@Testcontainers
@SpringBootTest
class ComprobanteElectronicoUltimoResultadoConsultaCheckConstraintTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.1");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Arma el grafo mínimo usuario → empresa → cliente → factura y devuelve
     * {@code [empresaId, facturaId]}, únicos valores que necesita el INSERT sobre
     * {@code comprobante_electronico} bajo prueba.
     */
    private UUID[] crearFacturaMinima() {
        UUID usuarioId = jdbcTemplate.queryForObject(
                """
                INSERT INTO usuario (nombre, email, password_hash)
                VALUES (?, ?, 'hash-no-relevante')
                RETURNING id
                """,
                UUID.class,
                "Usuario de prueba CHECK",
                "usuario-check-" + UUID.randomUUID() + "@fractall.test");

        UUID empresaId = jdbcTemplate.queryForObject(
                """
                INSERT INTO empresa (razon_social, creado_por)
                VALUES (?, ?)
                RETURNING id
                """,
                UUID.class,
                "Empresa de prueba CHECK S.A.",
                usuarioId);

        UUID clienteId = jdbcTemplate.queryForObject(
                """
                INSERT INTO cliente (empresa_id, nombre, tipo_identificacion, numero_identificacion)
                VALUES (?, ?, '02', ?)
                RETURNING id
                """,
                UUID.class,
                empresaId,
                "Cliente de prueba CHECK",
                String.valueOf(System.nanoTime()));

        UUID facturaId = jdbcTemplate.queryForObject(
                """
                INSERT INTO factura (empresa_id, cliente_id, subtotal, total_impuesto, total, creado_por)
                VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id
                """,
                UUID.class,
                empresaId,
                clienteId,
                new BigDecimal("1000.00000"),
                new BigDecimal("130.00000"),
                new BigDecimal("1130.00000"),
                usuarioId);

        return new UUID[] {empresaId, facturaId};
    }

    private void insertarComprobanteConResultado(UUID empresaId, UUID facturaId, String ultimoResultadoConsulta) {
        String claveNumerica = ("506" + UUID.randomUUID().toString().replaceAll("[^0-9]", "") + "0".repeat(50))
                .substring(0, 50);

        jdbcTemplate.update(
                """
                INSERT INTO comprobante_electronico
                    (empresa_id, factura_id, ambiente_hacienda, consecutivo, clave_numerica,
                     ultimo_resultado_consulta, fecha_ultima_consulta_hacienda)
                VALUES (?, ?, 'SANDBOX', '00100001010000000001', ?, ?, ?)
                """,
                empresaId,
                facturaId,
                claveNumerica,
                ultimoResultadoConsulta,
                LocalDateTime.now());
    }

    @Test
    void errorConfiguracionEsAceptadoPorLaRestriccionCheck() {
        UUID[] empresaYFactura = crearFacturaMinima();
        UUID empresaId = empresaYFactura[0];
        UUID facturaId = empresaYFactura[1];

        assertThatCode(() -> insertarComprobanteConResultado(empresaId, facturaId, "ERROR_CONFIGURACION"))
                .doesNotThrowAnyException();

        String persistido = jdbcTemplate.queryForObject(
                "SELECT ultimo_resultado_consulta FROM comprobante_electronico WHERE factura_id = ?",
                String.class,
                facturaId);
        assertThat(persistido).isEqualTo("ERROR_CONFIGURACION");
    }

    @Test
    void erroComunicacionSigueSiendoAceptadoPorLaRestriccionCheck() {
        UUID[] empresaYFactura = crearFacturaMinima();
        UUID empresaId = empresaYFactura[0];
        UUID facturaId = empresaYFactura[1];

        assertThatCode(() -> insertarComprobanteConResultado(empresaId, facturaId, "ERROR_COMUNICACION"))
                .doesNotThrowAnyException();

        String persistido = jdbcTemplate.queryForObject(
                "SELECT ultimo_resultado_consulta FROM comprobante_electronico WHERE factura_id = ?",
                String.class,
                facturaId);
        assertThat(persistido).isEqualTo("ERROR_COMUNICACION");
    }
}
