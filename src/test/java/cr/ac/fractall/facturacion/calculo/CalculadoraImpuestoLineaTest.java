package cr.ac.fractall.facturacion.calculo;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import cr.ac.fractall.facturacion.calculo.CalculadoraImpuestoLinea.FuenteExoneracion;
import cr.ac.fractall.facturacion.calculo.CalculadoraImpuestoLinea.ResultadoImpuestoLinea;
import cr.ac.fractall.facturacion.modelo.ImpuestoLineaExoneracion;
import cr.ac.fractall.facturacion.modelo.LineaFactura;

/**
 * Unit tests for {@link CalculadoraImpuestoLinea}. Plain JUnit, no Spring context —
 * the calculator is a pure static function over two entity snapshots.
 */
class CalculadoraImpuestoLineaTest {

    private static LineaFactura linea(BigDecimal subtotal, BigDecimal porcentaje) {
        LineaFactura linea = new LineaFactura();
        linea.setSubtotal(subtotal);
        linea.setPorcentajeImpuestoAplicado(porcentaje);
        return linea;
    }

    private static ImpuestoLineaExoneracion inline(BigDecimal monto) {
        ImpuestoLineaExoneracion exoneracion = new ImpuestoLineaExoneracion();
        exoneracion.setMontoExoneracion(monto);
        return exoneracion;
    }

    @Test
    void sinExoneracionDevuelveImpuestoBrutoSinCambios() {
        LineaFactura linea = linea(new BigDecimal("1000.00000"), new BigDecimal("13.00"));

        ResultadoImpuestoLinea resultado = CalculadoraImpuestoLinea.calcular(linea, null);

        assertThat(resultado.impuestoBruto()).isEqualByComparingTo("130.00000");
        assertThat(resultado.montoExoneracion()).isEqualByComparingTo("0");
        assertThat(resultado.impuestoNeto()).isEqualByComparingTo("130.00000");
        assertThat(resultado.fuente()).isEqualTo(FuenteExoneracion.NINGUNA);
    }

    @Test
    void conExoneracionLegacyRestaMontoLegacy() {
        LineaFactura linea = linea(new BigDecimal("1000.00000"), new BigDecimal("13.00"));
        linea.setExoneracionId(UUID.randomUUID());
        linea.setMontoExoneracionAplicado(new BigDecimal("50.00000"));

        ResultadoImpuestoLinea resultado = CalculadoraImpuestoLinea.calcular(linea, null);

        assertThat(resultado.impuestoBruto()).isEqualByComparingTo("130.00000");
        assertThat(resultado.montoExoneracion()).isEqualByComparingTo("50.00000");
        assertThat(resultado.impuestoNeto()).isEqualByComparingTo("80.00000");
        assertThat(resultado.fuente()).isEqualTo(FuenteExoneracion.LEGACY);
    }

    @Test
    void conExoneracionInlineRestaMontoInline() {
        LineaFactura linea = linea(new BigDecimal("1000.00000"), new BigDecimal("13.00"));
        ImpuestoLineaExoneracion exoneracionInline = inline(new BigDecimal("130.00000"));

        ResultadoImpuestoLinea resultado = CalculadoraImpuestoLinea.calcular(linea, exoneracionInline);

        assertThat(resultado.impuestoBruto()).isEqualByComparingTo("130.00000");
        assertThat(resultado.montoExoneracion()).isEqualByComparingTo("130.00000");
        assertThat(resultado.impuestoNeto()).isEqualByComparingTo("0.00000");
        assertThat(resultado.fuente()).isEqualTo(FuenteExoneracion.INLINE);
    }

    @Test
    void conAmbasFuentesPresentesInlineGanaSobreLegacy() {
        LineaFactura linea = linea(new BigDecimal("1000.00000"), new BigDecimal("13.00"));
        linea.setExoneracionId(UUID.randomUUID());
        linea.setMontoExoneracionAplicado(new BigDecimal("50.00000"));
        ImpuestoLineaExoneracion exoneracionInline = inline(new BigDecimal("30.00000"));

        ResultadoImpuestoLinea resultado = CalculadoraImpuestoLinea.calcular(linea, exoneracionInline);

        assertThat(resultado.montoExoneracion()).isEqualByComparingTo("30.00000");
        assertThat(resultado.impuestoNeto()).isEqualByComparingTo("100.00000");
        assertThat(resultado.fuente()).isEqualTo(FuenteExoneracion.INLINE);
    }

    @Test
    void exentoYGravadoAlCeroPorcientoSonDistinguibles() {
        LineaFactura exenta = linea(new BigDecimal("1000.00000"), BigDecimal.ZERO);
        exenta.setGravadoAplicado(false);

        LineaFactura gravadaCero = linea(new BigDecimal("1000.00000"), BigDecimal.ZERO);
        gravadaCero.setGravadoAplicado(true);

        ResultadoImpuestoLinea resultadoExenta = CalculadoraImpuestoLinea.calcular(exenta, null);
        ResultadoImpuestoLinea resultadoGravadaCero = CalculadoraImpuestoLinea.calcular(gravadaCero, null);

        assertThat(resultadoExenta.impuestoNeto()).isEqualByComparingTo("0.00000");
        assertThat(resultadoGravadaCero.impuestoNeto()).isEqualByComparingTo("0.00000");
        assertThat(exenta.isGravadoAplicado()).isFalse();
        assertThat(gravadaCero.isGravadoAplicado()).isTrue();
    }

    @Test
    void exoneracionMayorAlImpuestoBrutoNoAplicaPiso() {
        LineaFactura linea = linea(new BigDecimal("1000.00000"), new BigDecimal("13.00"));
        ImpuestoLineaExoneracion exoneracionInline = inline(new BigDecimal("200.00000"));

        ResultadoImpuestoLinea resultado = CalculadoraImpuestoLinea.calcular(linea, exoneracionInline);

        assertThat(resultado.impuestoNeto()).isEqualByComparingTo("-70.00000");
    }
}
