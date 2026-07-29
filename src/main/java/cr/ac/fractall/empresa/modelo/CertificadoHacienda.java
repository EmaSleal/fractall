package cr.ac.fractall.empresa.modelo;

import java.util.UUID;

import cr.ac.fractall.shared.EntidadBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "certificado_hacienda", uniqueConstraints = @UniqueConstraint(columnNames = {"empresa_id", "ambiente"}))
@Getter
@Setter
@NoArgsConstructor
public class CertificadoHacienda extends EntidadBase {

    @Column(name = "empresa_id", nullable = false)
    private UUID empresaId;

    @Column(name = "ambiente", nullable = false, length = 10)
    private String ambiente;

    @Column(name = "certificado_referencia", nullable = false, length = 255)
    private String certificadoReferencia;

    @Column(name = "certificado_p12_cifrado", nullable = false)
    private byte[] certificadoP12Cifrado;

    @Column(name = "certificado_dek_cifrada", nullable = false)
    private byte[] certificadoDekCifrada;
}
