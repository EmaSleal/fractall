package cr.ac.fractall.empresa.modelo;

import java.time.LocalDateTime;
import java.util.UUID;

import cr.ac.fractall.shared.EntidadBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "empresa")
@Getter
@Setter
@NoArgsConstructor
public class Empresa extends EntidadBase {

    @Column(name = "razon_social", nullable = false, length = 255)
    private String razonSocial;

    @Column(name = "nombre_comercial", length = 255)
    private String nombreComercial;

    @Column(name = "numero_identificacion", unique = true, length = 20)
    private String numeroIdentificacion;

    @Column(name = "tipo_identificacion", length = 2)
    private String tipoIdentificacion;

    @Column(name = "codigo_actividad", length = 6)
    private String codigoActividad;

    @Column(name = "codigo_provincia", length = 1)
    private String codigoProvincia;

    @Column(name = "canton", length = 2)
    private String canton;

    @Column(name = "distrito", length = 2)
    private String distrito;

    @Column(name = "barrio", length = 100)
    private String barrio;

    @Column(name = "otras_senas", length = 300)
    private String otrasSenas;

    @Column(name = "telefono", length = 20)
    private String telefono;

    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "ambiente_hacienda", nullable = false, length = 10)
    private String ambienteHacienda;

    @Column(name = "certificado_referencia", length = 255)
    private String certificadoReferencia;

    /**
     * Blob del certificado {@code .p12} cifrado vía envelope encryption (sección 6.4 de
     * {@code arquitectura-facturacion-electronica-cr.md}) -- NUNCA el {@code .p12} en claro.
     * Se escribe siempre junto con {@link #certificadoDekCifrada} y
     * {@link #certificadoReferencia} en la misma transacción (ver
     * {@code EmpresaService#cargarCertificado}).
     */
    @Column(name = "certificado_p12_cifrado")
    private byte[] certificadoP12Cifrado;

    /**
     * DEK que cifró {@link #certificadoP12Cifrado}, ya cifrada por la KEK de Transit (sección
     * 6.1) -- el texto plano de esta DEK se descarta inmediatamente después de usarse, nunca
     * se persiste.
     */
    @Column(name = "certificado_dek_cifrada")
    private byte[] certificadoDekCifrada;

    @Column(name = "status", nullable = false, length = 35)
    private String status;

    @Column(name = "creado_por", nullable = false)
    private UUID creadoPor;

    @Column(name = "create_date", nullable = false)
    private LocalDateTime createDate;

    @Column(name = "update_date", nullable = false)
    private LocalDateTime updateDate;
}
