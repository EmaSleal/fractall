package cr.ac.fractall.catalogo.repositorio;

import org.springframework.data.jpa.repository.JpaRepository;

import cr.ac.fractall.catalogo.modelo.Provincia;

public interface ProvinciaRepository extends JpaRepository<Provincia, String> {
}
