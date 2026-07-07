package cr.ac.fractall.facturacion.servicio;

import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cr.ac.fractall.facturacion.modelo.ContadorConsecutivo;
import cr.ac.fractall.facturacion.modelo.ContadorConsecutivoId;
import cr.ac.fractall.facturacion.repositorio.ContadorConsecutivoRepository;

/**
 * Reclama el siguiente número de consecutivo de comprobante, con bloqueo pesimista de fila
 * (sección 4.9 de {@code arquitectura-facturacion-electronica-cr.md}).
 *
 * <p>Réplica literal del SQL de dos pasos del documento: lectura con {@code FOR UPDATE} y
 * después un {@code UPDATE} explícito, ambos en la MISMA transacción -- de modo que un
 * {@code ROLLBACK} posterior en esa transacción revierte también el incremento, sin dejar
 * huecos en la numeración. {@link #siguienteConsecutivo} devuelve el valor YA incrementado
 * (el número reclamado para el comprobante que se está generando), no el valor previo.
 *
 * <p>El {@code saveAndFlush} explícito después de incrementar es deliberado: no se confía en el
 * "dirty checking" automático de Hibernate para decidir CUÁNDO emite el {@code UPDATE} -- debe
 * ocurrir inmediatamente, dentro de esta misma transacción, no diferido hasta el final.
 *
 * <p><b>Alta perezosa de la fila (get-or-create):</b> ninguna fase anterior siembra
 * {@code contador_consecutivo} para una empresa real -- ni el registro (Fase 4) ni la
 * habilitación (Fase 5) crean esa fila, así que sin este mecanismo la primera factura de
 * cualquier tenant real fallaría con {@link ContadorConsecutivoNoEncontradoException} de forma
 * permanente. La creación perezosa vive en {@link ContadorConsecutivoInicializador}, un bean
 * DISTINTO -- no un método {@code protected} de esta misma clase -- porque su
 * {@code @Transactional(REQUIRES_NEW)} solo se respeta si Spring intercepta la llamada a través
 * del proxy AOP; una auto-invocación ({@code this.metodo(...)}) dentro de la misma clase nunca
 * pasa por el proxy y silenciosamente ignoraría la propagación (la auto-invocación de
 * {@code @Transactional} es una trampa conocida de Spring AOP basado en proxies).
 *
 * <p>{@code DataIntegrityViolationException} (otra petición concurrente ganó la carrera de
 * creación) se atrapa AQUÍ, no dentro de {@link ContadorConsecutivoInicializador#crearSiNoExiste}
 * -- ver el javadoc de esa clase sobre por qué atraparla dentro de su propia transacción
 * {@code REQUIRES_NEW} produciría un {@code UnexpectedRollbackException} en vez de un manejo
 * limpio. En este punto ya estamos de vuelta en la transacción de {@code siguienteConsecutivo},
 * que nunca tocó una sesión en mal estado.
 */
@Service
public class ConsecutivoService {

    private final ContadorConsecutivoRepository contadorConsecutivoRepository;
    private final ContadorConsecutivoInicializador contadorConsecutivoInicializador;

    public ConsecutivoService(
            ContadorConsecutivoRepository contadorConsecutivoRepository,
            ContadorConsecutivoInicializador contadorConsecutivoInicializador) {
        this.contadorConsecutivoRepository = contadorConsecutivoRepository;
        this.contadorConsecutivoInicializador = contadorConsecutivoInicializador;
    }

    @Transactional
    public long siguienteConsecutivo(UUID empresaId, String ambiente, String tipoComprobante) {
        ContadorConsecutivoId id = new ContadorConsecutivoId(empresaId, ambiente, tipoComprobante);

        ContadorConsecutivo contador = contadorConsecutivoRepository.findById(id).orElse(null);
        if (contador == null) {
            try {
                contadorConsecutivoInicializador.crearSiNoExiste(empresaId, ambiente, tipoComprobante);
            } catch (DataIntegrityViolationException otraPeticionYaLaCreo) {
                // Otra petición concurrente ganó la carrera de creación -- la fila ya existe,
                // se relee normalmente abajo.
            }
            contador = contadorConsecutivoRepository.findById(id)
                    .orElseThrow(() -> new ContadorConsecutivoNoEncontradoException(
                            empresaId, ambiente, tipoComprobante));
        }

        long siguienteValor = contador.getValorActual() + 1;
        contador.setValorActual(siguienteValor);
        contadorConsecutivoRepository.saveAndFlush(contador);

        return siguienteValor;
    }
}
