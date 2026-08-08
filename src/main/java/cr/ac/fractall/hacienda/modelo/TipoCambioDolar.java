package cr.ac.fractall.hacienda.modelo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Cache local (read-through) del tipo de cambio del dólar publicado por Hacienda Costa Rica
 * (V17__tipo_cambio_dolar.sql) -- ver el javadoc de {@code HaciendaConsultaServiceImpl} para
 * cuándo se sirve desde aquí y cuándo se llama a Hacienda en vivo.
 *
 * <p>Sin {@code id} sustituto: la clave natural ({@code fecha}, la fecha del SERVIDOR al momento
 * de la consulta, no la que venga en el JSON de Hacienda) ES la PK -- mismo patrón que
 * {@link Cabys}.
 */
@Entity
@Table(name = "tipo_cambio_dolar")
@Getter
@Setter
@NoArgsConstructor
public class TipoCambioDolar {

    @Id
    @Column(name = "fecha")
    private LocalDate fecha;

    @Column(name = "venta", nullable = false)
    private BigDecimal venta;

    @Column(name = "compra", nullable = false)
    private BigDecimal compra;

    @Column(name = "consultado_en", nullable = false)
    private LocalDateTime consultadoEn;
}
