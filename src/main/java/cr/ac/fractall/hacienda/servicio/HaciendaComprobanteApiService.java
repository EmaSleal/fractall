package cr.ac.fractall.hacienda.servicio;

import java.util.List;
import java.util.UUID;

import cr.ac.fractall.hacienda.dto.MensajeHaciendaDTO;
import cr.ac.fractall.hacienda.dto.RespuestaHaciendaDTO;
import cr.ac.fractall.hacienda.dto.TokenHaciendaDTO;

/**
 * Cliente OAuth2 + recepción de comprobantes electrónicos de Hacienda Costa Rica (Fase 8,
 * sección 4.10/8 de {@code arquitectura-facturacion-electronica-cr.md}).
 *
 * <p>Portado (Categoría A) de
 * {@code docs/proyecto-referencia/erp_spring_manager/.../facturacion/electronica/service/HaciendaApiService.java}.
 * Deliberadamente NO se llama {@code HaciendaApiService} pese a que así se llama en el ERP de
 * referencia -- ese nombre ya lo tiene {@link HaciendaApiService} en este mismo paquete desde la
 * Fase 6, portado de un archivo DISTINTO del ERP de referencia
 * ({@code modules/configuracion/service/HaciendaApiService.java}, consulta pública de CABYS y
 * contribuyente) que, en el proyecto original, vive en un paquete Java diferente y por eso no
 * colisiona allá. Aquí ambos comparten paquete, así que este servicio se nombra explícitamente
 * por lo que hace: autenticar y enviar/consultar comprobantes -- nunca modificar
 * {@link HaciendaApiService} para acomodar esta clase.
 *
 * <p>Todos los métodos reciben el {@code UUID} de una fila de
 * {@code cr.ac.fractall.empresa.modelo.CredencialHacienda} (nunca un {@code empresaId} directo):
 * esa entidad ya guarda una credencial por {@code (empresa, ambiente)}, así que su propio id
 * identifica sin ambigüedad qué empresa y qué ambiente (sandbox/producción) usar en cada llamada
 * -- igual que {@code Long configuracionId} en la interfaz original, solo que apuntando a la
 * entidad real de este proyecto en vez de {@code ConfiguracionHacienda}.
 *
 * <p>Solo se porta el cliente OAuth2 + envío/consulta de comprobantes: el generador de XML v4.4 y
 * la firma XML-DSig/XAdES-BES quedan reservados para una fase futura (ver el package-info de
 * {@code cr.ac.fractall.hacienda}). Esta interfaz tampoco se conecta todavía a
 * {@code FacturaService} ni a ningún controlador -- eso es un paso posterior separado.
 */
public interface HaciendaComprobanteApiService {

    /**
     * Autentica y obtiene token OAuth2 de Hacienda para la credencial indicada. Cacheado por
     * {@code credencialId} (ver {@code HaciendaComprobanteApiServiceImpl}) para no reautenticar
     * en cada envío/consulta.
     */
    TokenHaciendaDTO autenticar(UUID credencialId);

    /**
     * Renueva token OAuth2 usando refresh token; si la renovación falla, reautentica desde cero.
     */
    TokenHaciendaDTO renovarToken(String refreshToken, UUID credencialId);

    /**
     * Envía comprobante a Hacienda.
     */
    RespuestaHaciendaDTO enviarComprobante(String xml, String claveNumerica, UUID credencialId);

    /**
     * Consulta estado de un comprobante en Hacienda.
     */
    RespuestaHaciendaDTO consultarComprobante(String claveNumerica, UUID credencialId);

    /**
     * Consulta si Hacienda tiene mensajes de respuesta pendientes.
     */
    List<MensajeHaciendaDTO> consultarMensajes(UUID credencialId);
}
