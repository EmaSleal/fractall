package cr.ac.fractall.facturacion.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * {@code fechaEmision} y {@code fechaRespuesta} se calculan en UTC (ver {@code FacturaService} y
 * {@code HaciendaComprobanteApiServiceImpl}), pero antes se exponían como {@code LocalDateTime}
 * sin ningún indicador de zona en el JSON -- un consumidor que lo interpretara como hora local (o
 * como UTC-6) quedaba desfasado 6 horas. Deben serializarse como instante explícito (sufijo
 * {@code Z}) replicando la configuración por defecto de Jackson en Spring Boot
 * ({@code write-dates-as-timestamps=false}).
 */
class FacturaResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE);

    @Test
    void fechaEmisionYFechaRespuesta_seSerializanConSufijoZ() throws Exception {
        FacturaResponse respuesta = new FacturaResponse(
                UUID.randomUUID(), UUID.randomUUID(), "Cliente de prueba",
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of(),
                UUID.randomUUID(), "SANDBOX", "01", "00100001010000000001",
                "50601010100011010000000001000000001100000000001", null,
                "GENERADO", Instant.parse("2026-08-23T14:32:10Z"), null, null, BigDecimal.ZERO,
                List.of(), List.of(), List.of(), "00", "aceptado",
                Instant.parse("2026-08-23T14:35:00Z"), null, 0);

        String json = objectMapper.writeValueAsString(respuesta);

        assertThat(json).contains("\"fechaEmision\":\"2026-08-23T14:32:10Z\"");
        assertThat(json).contains("\"fechaRespuesta\":\"2026-08-23T14:35:00Z\"");
    }
}
