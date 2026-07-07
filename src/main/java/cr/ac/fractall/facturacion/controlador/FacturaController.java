package cr.ac.fractall.facturacion.controlador;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cr.ac.fractall.catalogo.servicio.ClienteExoneracionNoEncontradaException;
import cr.ac.fractall.catalogo.servicio.ClienteNoEncontradoException;
import cr.ac.fractall.catalogo.servicio.ProductoNoEncontradoException;
import cr.ac.fractall.facturacion.dto.CrearFacturaRequest;
import cr.ac.fractall.facturacion.servicio.CondicionVentaInvalidaException;
import cr.ac.fractall.facturacion.servicio.ContadorConsecutivoNoEncontradoException;
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
 */
@RestController
@RequestMapping("/facturas")
public class FacturaController {

    private final FacturaService facturaService;

    public FacturaController(FacturaService facturaService) {
        this.facturaService = facturaService;
    }

    @PostMapping
    public ResponseEntity<?> crear(@Valid @RequestBody CrearFacturaRequest request) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(facturaService.crear(request));
        } catch (ClienteNoEncontradoException | ProductoNoEncontradoException
                | ClienteExoneracionNoEncontradaException excepcion) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new MensajeResponse(excepcion.getMessage()));
        } catch (ExoneracionNoPerteneceAlClienteException
                | ExoneracionNoAplicableAFacturaElectronicaException
                | ExoneracionNoVigenteException
                | CondicionVentaInvalidaException excepcion) {
            return ResponseEntity.badRequest().body(new MensajeResponse(excepcion.getMessage()));
        } catch (ContadorConsecutivoNoEncontradoException excepcion) {
            // No debería ocurrir en operación normal -- ConsecutivoService crea la fila de
            // contador_consecutivo de forma perezosa si no existe (ver su javadoc). Si esta
            // excepción llega aquí de todos modos, es un fallo real de infraestructura (p. ej.
            // la creación perezosa también falló), no un error de datos del cliente -- 503, no
            // 400/404/409.
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new MensajeResponse(excepcion.getMessage()));
        }
    }
}
