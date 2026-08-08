package cr.ac.fractall.catalogo.servicio;

import org.springframework.stereotype.Component;

import cr.ac.fractall.catalogo.repositorio.DistritoRepository;

/**
 * Validación compartida del bloque de ubicación (provincia/cantón/distrito/otras señas), usada
 * tanto por {@code ClienteService} como por {@code EmpresaService} -- antes de esta clase,
 * {@code ClienteService} tenía su propio {@code validarUbicacion} privado (solo forma) y
 * {@code EmpresaService} no validaba nada de esto, inconsistencia cerrada en
 * V15__catalogo_ubicacion_cr.sql.
 *
 * <p>Dos niveles de validación, en este orden:
 * <ol>
 *   <li>Todo-o-nada: los 4 campos deben estar todos presentes o todos ausentes (mismo espíritu
 *       que el {@code CHECK} de motor de {@code cliente}, sección 4.11 de
 *       {@code arquitectura-facturacion-electronica-cr.md}).</li>
 *   <li>Si están todos presentes, existencia real de la combinación provincia/cantón/distrito
 *       contra el catálogo (V15__catalogo_ubicacion_cr.sql) vía {@link DistritoRepository} --
 *       antes de esta clase, un valor con la forma correcta pero inexistente en el catálogo
 *       (ej. una provincia '9' que no existe) pasaba sin error.</li>
 * </ol>
 *
 * <p>Misma excepción ({@link UbicacionInvalidaException}), mismo momento (antes de
 * {@code saveAndFlush}/{@code guardarYReleer}), mismo mapeo a 400 en el controller para ambos
 * llamadores -- no cambia el contrato de la API de {@code ClienteController}, y es el único
 * comportamiento nuevo que gana {@code EmpresaService.actualizarDatosFiscales}.
 */
@Component
public class UbicacionValidator {

    private static final int LONGITUD_MINIMA_OTRAS_SENAS = 5;

    private final DistritoRepository distritoRepository;

    public UbicacionValidator(DistritoRepository distritoRepository) {
        this.distritoRepository = distritoRepository;
    }

    public void validar(String codigoProvincia, String canton, String distrito, String otrasSenas) {
        boolean todosPresentes = codigoProvincia != null && canton != null && distrito != null && otrasSenas != null;
        boolean todosAusentes = codigoProvincia == null && canton == null && distrito == null && otrasSenas == null;

        if (!todosPresentes && !todosAusentes) {
            throw new UbicacionInvalidaException(
                    "El bloque de ubicación (provincia/cantón/distrito/otras señas) debe estar completo o ausente por completo");
        }
        if (!todosPresentes) {
            return;
        }
        if (otrasSenas.trim().length() < LONGITUD_MINIMA_OTRAS_SENAS) {
            throw new UbicacionInvalidaException(
                    "otrasSenas debe tener al menos " + LONGITUD_MINIMA_OTRAS_SENAS + " caracteres");
        }
        if (!distritoRepository.existsByIdProvinciaCodigoAndIdCantonCodigoAndIdCodigo(codigoProvincia, canton, distrito)) {
            throw new UbicacionInvalidaException(
                    "La combinación de provincia/cantón/distrito no existe en el catálogo");
        }
    }
}
