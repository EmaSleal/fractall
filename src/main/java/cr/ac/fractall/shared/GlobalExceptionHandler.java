package cr.ac.fractall.shared;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import cr.ac.fractall.seguridad.dto.MensajeResponse;

/**
 * Backstop de defensa en profundidad para el patrón "check-then-act" de los pre-chequeos
 * explícitos de duplicados ({@code ClienteService}/{@code ProductoService}/
 * {@code ClienteExoneracionService}): dos solicitudes concurrentes con la misma clave única
 * pueden ambas pasar el {@code isPresent()} antes de que cualquiera de las dos haga commit -- en
 * ese caso el motor rechaza el segundo {@code INSERT} con una
 * {@code DataIntegrityViolationException} que, sin este advice, escalaría como un 500 crudo. NO
 * reemplaza los pre-chequeos explícitos (que dan un mensaje de dominio preciso en el camino no
 * concurrente); solo asegura que la ruta de carrera también responda con un 409 limpio.
 *
 * <p>Primer {@code @RestControllerAdvice} de la aplicación -- no existía ningún manejador global
 * de excepciones antes de esta clase.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<MensajeResponse> manejarViolacionDeIntegridad(DataIntegrityViolationException excepcion) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new MensajeResponse("El recurso ya existe o viola una restricción de unicidad."));
    }
}
