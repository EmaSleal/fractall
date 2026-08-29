package cr.ac.fractall.facturacion.calculo;

import java.math.BigDecimal;
import java.math.RoundingMode;

import cr.ac.fractall.facturacion.modelo.ImpuestoLineaExoneracion;
import cr.ac.fractall.facturacion.modelo.LineaFactura;

/**
 * Calculadora pura del impuesto neto de una línea de factura.
 *
 * <p>La exoneración se lee de EXACTAMENTE una de dos fuentes mutuamente excluyentes,
 * con precedencia inline sobre legacy: la fila {@code impuesto_linea_exoneracion} de
 * la línea (si existe) o, si no, el monto legacy denormalizado en
 * {@code linea_factura.monto_exoneracion_aplicado}. La precedencia se decide por
 * PRESENCIA de la fila inline, no por nulidad del monto — {@code monto_exoneracion}
 * es {@code NOT NULL} en el motor, así que su sola presencia ya implica un valor.
 *
 * <p>El resultado {@code impuestoNeto} se devuelve deliberadamente SIN piso en cero:
 * un monto de exoneración mayor al impuesto bruto produce un neto negativo, que debe
 * salir a la superficie en vez de ocultarse — asimetría intencional respecto al
 * comportamiento previo de {@code FacturaPdfService}, que aplicaba {@code .max(ZERO)}.
 *
 * <p>No es un {@code @Component}: no depende de Spring ni de ningún repositorio.
 * El monto legacy ya viene denormalizado en la línea; el monto inline debe ser
 * resuelto y pasado por el llamador (evita el N+1 que el reporte de IVA existe
 * para eliminar).
 */
public final class CalculadoraImpuestoLinea {

    public static final int ESCALA_MONETARIA = 5;

    public enum FuenteExoneracion {
        NINGUNA,
        INLINE,
        LEGACY
    }

    public record ResultadoImpuestoLinea(
            BigDecimal impuestoBruto,
            BigDecimal montoExoneracion,
            BigDecimal impuestoNeto,
            FuenteExoneracion fuente) {
    }

    /**
     * @param linea             la línea de factura sobre la que se calcula el impuesto.
     * @param exoneracionInline la fila {@code ImpuestoLineaExoneracion} de esta línea, o {@code null}
     *                          si no existe.
     */
    public static ResultadoImpuestoLinea calcular(LineaFactura linea, ImpuestoLineaExoneracion exoneracionInline) {
        BigDecimal subtotal = nvl(linea.getSubtotal());
        BigDecimal porcentaje = nvl(linea.getPorcentajeImpuestoAplicado());
        BigDecimal bruto = subtotal.multiply(porcentaje)
                .divide(BigDecimal.valueOf(100), ESCALA_MONETARIA, RoundingMode.HALF_UP);

        BigDecimal exoneracion;
        FuenteExoneracion fuente;
        if (exoneracionInline != null) {
            exoneracion = nvl(exoneracionInline.getMontoExoneracion());
            fuente = FuenteExoneracion.INLINE;
        } else if (linea.getExoneracionId() != null && linea.getMontoExoneracionAplicado() != null) {
            exoneracion = linea.getMontoExoneracionAplicado();
            fuente = FuenteExoneracion.LEGACY;
        } else {
            exoneracion = BigDecimal.ZERO;
            fuente = FuenteExoneracion.NINGUNA;
        }

        return new ResultadoImpuestoLinea(bruto, exoneracion, bruto.subtract(exoneracion), fuente);
    }

    private static BigDecimal nvl(BigDecimal valor) {
        return valor != null ? valor : BigDecimal.ZERO;
    }

    private CalculadoraImpuestoLinea() {
    }
}
