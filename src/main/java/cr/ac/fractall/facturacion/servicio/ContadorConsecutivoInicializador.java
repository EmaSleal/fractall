package cr.ac.fractall.facturacion.servicio;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import cr.ac.fractall.facturacion.modelo.ContadorConsecutivo;
import cr.ac.fractall.facturacion.repositorio.ContadorConsecutivoRepository;

/**
 * Alta perezosa de una fila de {@code contador_consecutivo} en {@code valorActual=0} -- ver el
 * javadoc de {@link ConsecutivoService} sobre por qué esto vive en un bean separado (la
 * propagación {@code REQUIRES_NEW} solo se respeta a través del proxy AOP de Spring, nunca en
 * una auto-invocación dentro de la misma clase).
 *
 * <p>{@code REQUIRES_NEW} es deliberado: si el INSERT choca contra la llave primaria (otra
 * petición concurrente ya creó la fila para esa misma empresa/ambiente/tipo), esta transacción
 * HIJA se aborta -- la transacción del llamador ({@code ConsecutivoService#siguienteConsecutivo}),
 * que todavía no escribió nada, sigue intacta y puede simplemente releer la fila que la otra
 * petición ya insertó. Sin {@code REQUIRES_NEW}, el mismo choque de restricción marcaría la
 * transacción del LLAMADOR como abortada, y ningún statement posterior en esa transacción (ni
 * siquiera el {@code findById} de reintento) podría ejecutarse.
 *
 * <p><b>La excepción NO se atrapa aquí adentro, a propósito:</b> una vez que {@code flush()}
 * dispara una violación de restricción, Hibernate marca la sesión/transacción actual como
 * inservible independientemente de que el código Java capture la excepción o no -- atraparla
 * DENTRO de este método (dentro de su propia transacción {@code REQUIRES_NEW}) todavía deja esa
 * transacción "doomed", y el intento posterior de Spring de confirmarla al salir del método
 * lanza {@code UnexpectedRollbackException}, no un commit silencioso. Dejar que la excepción se
 * propague permite que el proxy transaccional haga el rollback de ESTA transacción hija de forma
 * limpia; {@link ConsecutivoService#siguienteConsecutivo} la atrapa recién DESPUÉS de que este
 * método retorna (o lanza), ya en su propia transacción externa, que nunca tocó una sesión en
 * mal estado.
 */
@Component
public class ContadorConsecutivoInicializador {

    private final ContadorConsecutivoRepository contadorConsecutivoRepository;

    public ContadorConsecutivoInicializador(ContadorConsecutivoRepository contadorConsecutivoRepository) {
        this.contadorConsecutivoRepository = contadorConsecutivoRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void crearSiNoExiste(UUID empresaId, String ambiente, String tipoComprobante) {
        contadorConsecutivoRepository.saveAndFlush(
                new ContadorConsecutivo(empresaId, ambiente, tipoComprobante, 0L));
    }
}
