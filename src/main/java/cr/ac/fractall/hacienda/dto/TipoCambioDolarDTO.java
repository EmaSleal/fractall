package cr.ac.fractall.hacienda.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Respuesta completa de {@code https://api.hacienda.go.cr/indicadores/tc/dolar} -- ver
 * {@link IndicadorTipoCambioDTO}. {@code venta} es el tipo de cambio usado para convertir a
 * colones en facturación; {@code compra} se guarda por completitud pero no se usa para
 * autocompletar una factura.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class TipoCambioDolarDTO {

    private IndicadorTipoCambioDTO venta;

    private IndicadorTipoCambioDTO compra;
}
