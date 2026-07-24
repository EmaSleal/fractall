package cr.ac.fractall.facturacion.controlador;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cr.ac.fractall.catalogo.servicio.ClienteExoneracionNoEncontradaException;
import cr.ac.fractall.facturacion.servicio.ComprobanteElectronicoNoEncontradoException;
import cr.ac.fractall.catalogo.servicio.ClienteNoEncontradoException;
import cr.ac.fractall.catalogo.servicio.ProductoNoEncontradoException;
import cr.ac.fractall.facturacion.dto.CrearFacturaRequest;
import cr.ac.fractall.facturacion.dto.FacturaResponse;
import cr.ac.fractall.facturacion.servicio.CondicionVentaInvalidaException;
import cr.ac.fractall.facturacion.servicio.ComprobanteXmlPersistenceService;
import cr.ac.fractall.facturacion.servicio.ContadorConsecutivoNoEncontradoException;
import cr.ac.fractall.facturacion.servicio.CredencialHaciendaNoEncontradaException;
import cr.ac.fractall.facturacion.servicio.ExoneracionNoAplicableAFacturaElectronicaException;
import cr.ac.fractall.facturacion.servicio.ExoneracionNoPerteneceAlClienteException;
import cr.ac.fractall.facturacion.servicio.ExoneracionNoVigenteException;
import cr.ac.fractall.facturacion.servicio.FacturaService;
import cr.ac.fractall.seguridad.dto.MensajeResponse;
import jakarta.validation.Valid;

/**
 * {@code POST /facturas} (Fase 7, secciones 4.9 y 4.12-4.15 de
 * {@code arquitectura-facturacion-electronica-cr.md}). Corre detrás de un access token normal ya
 * autenticado por {@code JwtAuthenticationFilter}/{@code JwtTenantFilter} -- {@code empresaId}
 * nunca llega por path variable ni cuerpo de la solicitud, mismo patrón de
 * {@code ProductoController}/{@code ClienteController} (ver su javadoc).
 *
 * <p>Fase 8: DESPUÉS de que {@link FacturaService#crear} ya hizo commit de su transacción, este
 * controlador invoca {@link ComprobanteXmlPersistenceService#generarYPersistirXml} como una
 * SEGUNDA llamada separada -- nunca dentro de la transacción de {@code crear()} (ver el javadoc
 * de esa clase para el porqué). Si esa segunda llamada falla, la excepción se deja propagar sin
 * capturar (ninguno de los catch de abajo la reconoce): la factura y el comprobante ya quedaron
 * persistidos, pero el cliente HTTP recibe un error en vez de un 201 -- riesgo de fallo parcial
 * documentado y aceptado, ver el javadoc de {@code ComprobanteXmlPersistenceService}.
 */
@RestController
@RequestMapping("/facturas")
public class FacturaController {

    private final FacturaService facturaService;
    private final ComprobanteXmlPersistenceService comprobanteXmlPersistenceService;

    public FacturaController(
            FacturaService facturaService, ComprobanteXmlPersistenceService comprobanteXmlPersistenceService) {
        this.facturaService = facturaService;
        this.comprobanteXmlPersistenceService = comprobanteXmlPersistenceService;
    }

    @GetMapping(path = "/diagnostico/{comprobanteId}/xml-respuesta", produces = MediaType.TEXT_XML_VALUE)
    public ResponseEntity<?> xmlRespuestaHacienda(@PathVariable UUID comprobanteId) {
        try {
            return ResponseEntity.ok(comprobanteXmlPersistenceService.obtenerXmlRespuesta(comprobanteId));
        } catch (ComprobanteElectronicoNoEncontradoException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping
    public ResponseEntity<?> crear(@Valid @RequestBody CrearFacturaRequest request) {
        try {
            FacturaResponse response = facturaService.crear(request);
            comprobanteXmlPersistenceService.generarYPersistirXml(response.comprobanteId());
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (ClienteNoEncontradoException | ProductoNoEncontradoException
                | ClienteExoneracionNoEncontradaException excepcion) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new MensajeResponse(excepcion.getMessage()));
        } catch (ExoneracionNoPerteneceAlClienteException
                | ExoneracionNoAplicableAFacturaElectronicaException
                | ExoneracionNoVigenteException
                | CondicionVentaInvalidaException excepcion) {
            return ResponseEntity.badRequest().body(new MensajeResponse(excepcion.getMessage()));
        } catch (ContadorConsecutivoNoEncontradoException | CredencialHaciendaNoEncontradaException excepcion) {
            // No debería ocurrir en operación normal -- ConsecutivoService crea la fila de
            // contador_consecutivo de forma perezosa si no existe (ver su javadoc), y toda empresa
            // debería tener su CredencialHacienda configurada antes de facturar. Si cualquiera de
            // las dos llega aquí de todos modos, es un fallo real de infraestructura/configuración,
            // no un error de datos del cliente -- 503, nunca un 500 crudo ni 400/404/409. Para
            // CredencialHaciendaNoEncontradaException específicamente: la factura y el comprobante
            // ya quedaron persistidos (en FIRMADO) para este punto -- ver el javadoc de
            // ComprobanteXmlPersistenceService sobre por qué ese estado parcial es un riesgo
            // aceptado y no algo que este catch intente revertir.
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new MensajeResponse(excepcion.getMessage()));
        }
    }
}
