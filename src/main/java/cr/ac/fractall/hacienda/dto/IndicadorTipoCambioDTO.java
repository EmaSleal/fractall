package cr.ac.fractall.hacienda.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Un indicador individual (compra o venta) dentro de la respuesta de
 * {@code https://api.hacienda.go.cr/indicadores/tc/dolar}. Ejemplo real verificado:
 * {@code {"venta": {"fecha": "2026-08-08", "valor": 453.68}, "compra": {"fecha": "2026-08-08", "valor": 447.88}}}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class IndicadorTipoCambioDTO {

    private LocalDate fecha;

    private BigDecimal valor;
}
