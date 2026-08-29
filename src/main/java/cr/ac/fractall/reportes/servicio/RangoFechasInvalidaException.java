package cr.ac.fractall.reportes.servicio;

/**
 * Rango {@code desde}/{@code hasta} inválido para {@code ReporteIvaService#generar} (Release 3 /
 * Fase D, ver el diseño): {@code hasta} anterior a {@code desde}, o un rango mayor a 366 días. El
 * tope de 366 días acota la memoria de un endpoint autenticado que ejecuta un theta-join de 3
 * entidades sin límite de página. Mapeada a HTTP 400 uniéndose al arreglo existente de
 * {@code GlobalExceptionHandler#manejarReglaDeNegocioInvalida} -- mismo criterio que las demás
 * excepciones de ese grupo: un dato de entrada del llamador inválido, no un id de recurso ni un
 * conflicto de estado.
 */
public class RangoFechasInvalidaException extends RuntimeException {

    public RangoFechasInvalidaException(String mensaje) {
        super(mensaje);
    }
}
