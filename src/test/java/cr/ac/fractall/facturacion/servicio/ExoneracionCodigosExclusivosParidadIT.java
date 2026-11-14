package cr.ac.fractall.facturacion.servicio;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
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
 * Paridad entre los codigos de exoneracion exclusivos de Nota de Credito/Debito en Java
 * ({@link LineaFacturaEnsamblador#TIPOS_EXONERACION_EXCLUSIVOS_NC_ND}) y la condicion
 * {@code v_tipo_documento IN (...)} de la funcion {@code fn_validar_exoneracion_vigente}
 * desplegada por Flyway (lee la definicion vigente, no el archivo .sql).
 */
@Testcontainers
@SpringBootTest
class ExoneracionCodigosExclusivosParidadIT {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.1");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final Pattern CONDICION =
            Pattern.compile("(?is)v_tipo_documento\\s+IN\\s*\\(([^)]*)\\)");
    private static final Pattern LITERAL = Pattern.compile("'(\\w+)'");

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("Codigos exclusivos NC/ND de Java coinciden con los del trigger de exoneracion")
    void codigosExclusivosJavaCoincidenConTrigger() {
        String definicion = jdbc.queryForObject(
                "SELECT pg_get_functiondef('fn_validar_exoneracion_vigente'::regproc)", String.class);
        assertThat(definicion).isNotNull();

        Matcher m = CONDICION.matcher(definicion);
        assertThat(m.find())
                .as("No se encontro 'v_tipo_documento IN (...)' en fn_validar_exoneracion_vigente")
                .isTrue();
        String lista = m.group(1);
        assertThat(m.find())
                .as("Se esperaba una unica condicion 'v_tipo_documento IN (...)' en la funcion")
                .isFalse();

        Set<String> trigger = new TreeSet<>();
        Matcher lit = LITERAL.matcher(lista);
        while (lit.find()) {
            trigger.add(lit.group(1));
        }
        Set<String> java = new TreeSet<>(LineaFacturaEnsamblador.TIPOS_EXONERACION_EXCLUSIVOS_NC_ND);

        assertThat(java)
                .as("Divergencia de codigos exclusivos NC/ND: Java=%s trigger=%s", java, trigger)
                .isNotEmpty()
                .isEqualTo(trigger);
    }
}
