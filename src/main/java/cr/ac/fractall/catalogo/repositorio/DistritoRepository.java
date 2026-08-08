package cr.ac.fractall.catalogo.repositorio;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import cr.ac.fractall.catalogo.modelo.Distrito;
import cr.ac.fractall.catalogo.modelo.DistritoId;

/**
 * {@link #existsByIdProvinciaCodigoAndIdCantonCodigoAndIdCodigo} es el método que usa
 * {@code cr.ac.fractall.catalogo.servicio.UbicacionValidator} para verificar existencia real de
 * la combinación provincia/cantón/distrito (no solo su forma).
 */
public interface DistritoRepository extends JpaRepository<Distrito, DistritoId> {

    List<Distrito> findByIdProvinciaCodigoAndIdCantonCodigoOrderByIdCodigoAsc(
            String provinciaCodigo, String cantonCodigo);

    boolean existsByIdProvinciaCodigoAndIdCantonCodigoAndIdCodigo(
            String provinciaCodigo, String cantonCodigo, String codigo);
}
