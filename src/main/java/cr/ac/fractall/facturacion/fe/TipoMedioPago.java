package cr.ac.fractall.facturacion.fe;

import lombok.Getter;

// Medios de pago según catálogo FE v4.4 (Anexo 1).
@Getter
public enum TipoMedioPago {

    // 01
    EFECTIVO("01", "Efectivo"),
    // 02
    TARJETA("02", "Tarjeta"),
    // 03
    CHEQUE("03", "Cheque"),
    // 04
    TRANSFERENCIA_DEPOSITO("04", "Transferencia - depósito bancario"),
    // 05
    RECAUDADO_TERCEROS("05", "Recaudado por terceros"),
    // 06
    SINPE_MOVIL("06", "SINPE Móvil"),
    // 07
    PLATAFORMA_DIGITAL("07", "Plataforma digital"),
    // 99
    OTROS("99", "Otros");

    private final String codigo;
    private final String descripcion;

    TipoMedioPago(String codigo, String descripcion) {
        this.codigo = codigo;
        this.descripcion = descripcion;
    }

    public static TipoMedioPago fromCodigo(String codigo) {
        for (TipoMedioPago item : values()) {
            if (item.codigo.equals(codigo)) {
                return item;
            }
        }
        throw new IllegalArgumentException("Código de medio de pago inválido: " + codigo);
    }
}
