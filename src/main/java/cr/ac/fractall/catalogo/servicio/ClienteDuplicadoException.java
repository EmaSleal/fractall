package cr.ac.fractall.catalogo.servicio;

/**
 * Ya existe un {@code Cliente} con este {@code numeroIdentificacion} para el tenant actual --
 * {@code UNIQUE(empresa_id, numero_identificacion)}. A diferencia del patrón de
 * {@code credencial_hacienda} (Fase 5, upsert de un slot de configuración), {@code cliente}
 * representa a uno entre muchos clientes distintos -- una segunda alta con la misma
 * identificación es un error genuino de registro duplicado, no una corrección a sobrescribir:
 * se rechaza (409), no se actualiza en el lugar. Editar un cliente existente es
 * {@code PATCH /catalogo/clientes/{id}}, una ruta separada.
 */
public class ClienteDuplicadoException extends RuntimeException {

    public ClienteDuplicadoException(String numeroIdentificacion) {
        super("Ya existe un cliente con el número de identificación '" + numeroIdentificacion + "' para esta empresa");
    }
}
