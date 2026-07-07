package cr.ac.fractall.hacienda;

import cr.ac.fractall.hacienda.dto.CabysBusquedaDTO;
import cr.ac.fractall.hacienda.dto.HaciendaConsultaDTO;

/**
 * Servicio para consultar la API pública de Hacienda Costa Rica.
 * Permite validar identificaciones, obtener datos tributarios y buscar códigos CABYS.
 *
 * <p>Portado como unidad atómica de
 * {@code docs/proyecto-referencia/erp_spring_manager/.../configuracion/service/HaciendaApiService.java}
 * (Categoría A) para la Fase 6 -- solo {@link #buscarCabys} tiene consumidor en esta fase
 * ({@code cr.ac.fractall.catalogo.servicio.ProductoService}); {@link #consultarContribuyente} y
 * {@link #validarIdentificacion} quedan listos para una fase futura (sección 10 de
 * {@code arquitectura-facturacion-electronica-cr.md}).
 */
public interface HaciendaApiService {

    /**
     * Consulta los datos de un contribuyente en la API de Hacienda.
     * Endpoint: https://api.hacienda.go.cr/fe/ae?identificacion={numero}
     *
     * @param numeroIdentificacion Número de identificación sin guiones ni espacios
     * @return DTO con los datos del contribuyente o error
     */
    HaciendaConsultaDTO consultarContribuyente(String numeroIdentificacion);

    /**
     * Valida si un número de identificación existe en Hacienda.
     *
     * @param numeroIdentificacion Número de identificación
     * @return true si existe y está inscrito, false en caso contrario
     */
    boolean validarIdentificacion(String numeroIdentificacion);

    /**
     * Busca códigos CABYS por descripción o palabra clave.
     * Endpoint: https://api.hacienda.go.cr/fe/cabys?q={busqueda}&amp;top={cantidad}
     *
     * @param busqueda Término de búsqueda (ej: "jugo de tomate")
     * @param top Cantidad máxima de resultados (por defecto 10)
     * @return DTO con lista de códigos CABYS encontrados
     */
    CabysBusquedaDTO buscarCabys(String busqueda, Integer top);
}
