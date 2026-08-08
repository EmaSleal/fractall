package cr.ac.fractall.catalogo.repositorio;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import cr.ac.fractall.catalogo.modelo.Canton;
import cr.ac.fractall.catalogo.modelo.CantonId;

public interface CantonRepository extends JpaRepository<Canton, CantonId> {

    List<Canton> findByIdProvinciaCodigoOrderByIdCodigoAsc(String provinciaCodigo);
}
