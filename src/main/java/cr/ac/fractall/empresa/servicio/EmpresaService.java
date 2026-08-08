package cr.ac.fractall.empresa.servicio;

import java.io.ByteArrayInputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cr.ac.fractall.empresa.modelo.CertificadoHacienda;
import cr.ac.fractall.empresa.repositorio.CertificadoHaciendaRepository;
import cr.ac.fractall.empresa.modelo.CredencialHacienda;
import cr.ac.fractall.empresa.repositorio.CredencialHaciendaRepository;
import cr.ac.fractall.empresa.modelo.Empresa;
import cr.ac.fractall.empresa.repositorio.EmpresaRepository;
import cr.ac.fractall.empresa.modelo.EmpresaAmbienteHistorial;
import cr.ac.fractall.empresa.repositorio.EmpresaAmbienteHistorialRepository;
import cr.ac.fractall.empresa.dto.ActualizarDatosFiscalesRequest;
import cr.ac.fractall.empresa.dto.EmpresaResponse;
import cr.ac.fractall.catalogo.servicio.UbicacionValidator;
import cr.ac.fractall.secretos.EnvelopeCipher;
import cr.ac.fractall.secretos.SecretosKvService;
import cr.ac.fractall.secretos.TransitService;
import cr.ac.fractall.tenant.TenantContext;

/**
 * Fase 5 (sección 4.1, 4.2 y 6.4 de {@code arquitectura-facturacion-electronica-cr.md}):
 * configuración fiscal, certificado {@code .p12} y credenciales de Hacienda de la empresa
 * (tenant) actual.
 *
 * <p>Esta clase NUNCA asigna {@code empresa.status} -- esa máquina de estados ya vive
 * enteramente en la base de datos desde la Fase 0 ({@code fn_actualizar_status_empresa}/
 * {@code trg_actualizar_status_empresa}, disparado {@code BEFORE UPDATE ON empresa}). El
 * único trabajo de este servicio es escribir las columnas de las que depende ese trigger
 * (datos fiscales, filas de {@code certificado_hacienda}, filas de {@code credencial_hacienda})
 * y releer el resultado.
 *
 * <p>{@code empresaId} se resuelve SIEMPRE de {@link TenantContext#get()} -- nunca de un
 * parámetro de entrada -- porque los 3 endpoints que llaman a este servicio corren detrás de
 * un access token normal ya resuelto por {@code JwtTenantFilter} (a diferencia de los
 * endpoints {@code /auth/mfa/*}, que usan tokens de alcance mínimo que ese filtro
 * deliberadamente no resuelve).
 *
 * <p>{@code EntityManager#refresh} es obligatorio después de cada escritura que pueda haber
 * disparado el trigger: Hibernate no sabe que el trigger reescribe {@code status} y
 * {@code update_date} del lado del servidor, así que la instancia Java quedaría con esos dos
 * campos desactualizados si no se releen explícitamente -- mismo motivo por el que
 * {@code EntidadBase#id} necesita {@code @Generated}, pero aquí no hay forma declarativa
 * equivalente para una columna que cambia en cada UPDATE, no solo en el INSERT.
 */
@Service
public class EmpresaService {

    private static final String KEYSTORE_PKCS12 = "PKCS12";

    private final EmpresaRepository empresaRepository;
    private final CertificadoHaciendaRepository certificadoHaciendaRepository;
    private final CredencialHaciendaRepository credencialHaciendaRepository;
    private final EmpresaAmbienteHistorialRepository empresaAmbienteHistorialRepository;
    private final SecretosKvService secretosKvService;
    private final TransitService transitService;
    private final UbicacionValidator ubicacionValidator;

    @PersistenceContext
    private EntityManager entityManager;

    public EmpresaService(
            EmpresaRepository empresaRepository,
            CertificadoHaciendaRepository certificadoHaciendaRepository,
            CredencialHaciendaRepository credencialHaciendaRepository,
            EmpresaAmbienteHistorialRepository empresaAmbienteHistorialRepository,
            SecretosKvService secretosKvService,
            TransitService transitService,
            UbicacionValidator ubicacionValidator) {
        this.empresaRepository = empresaRepository;
        this.certificadoHaciendaRepository = certificadoHaciendaRepository;
        this.credencialHaciendaRepository = credencialHaciendaRepository;
        this.empresaAmbienteHistorialRepository = empresaAmbienteHistorialRepository;
        this.secretosKvService = secretosKvService;
        this.transitService = transitService;
        this.ubicacionValidator = ubicacionValidator;
    }

    /**
     * Actualización PARCIAL (estilo PATCH): un campo {@code null} en el request deja el valor
     * actual de {@code empresa} intacto -- nunca lo sobrescribe. La transición de status (ej.
     * {@code REGISTRADA -> DATOS_FISCALES_INCOMPLETOS}) es responsabilidad exclusiva del
     * trigger, disparado por el propio {@code UPDATE}.
     *
     * <p><strong>Comportamiento nuevo (V15__catalogo_ubicacion_cr.sql):</strong> el bloque de
     * ubicación (codigoProvincia/canton/distrito/otrasSenas) resultante se valida con
     * {@link UbicacionValidator} ANTES de {@link #guardarYReleer} -- todo-o-nada más existencia
     * real contra el catálogo. Antes de este cambio {@code Empresa} no validaba nada de esto,
     * a diferencia de {@code ClienteService}, que ya aplicaba la misma regla todo-o-nada (sin la
     * verificación de existencia, que también es nueva para ambos servicios).
     */
    @Transactional
    public EmpresaResponse actualizarDatosFiscales(ActualizarDatosFiscalesRequest request) {
        Empresa empresa = obtenerEmpresaActual();

        aplicarSiNoEsNulo(request.razonSocial(), empresa::setRazonSocial);
        aplicarSiNoEsNulo(request.nombreComercial(), empresa::setNombreComercial);
        aplicarSiNoEsNulo(request.numeroIdentificacion(), empresa::setNumeroIdentificacion);
        aplicarSiNoEsNulo(request.tipoIdentificacion(), empresa::setTipoIdentificacion);
        aplicarSiNoEsNulo(request.codigoActividad(), empresa::setCodigoActividad);
        aplicarSiNoEsNulo(request.codigoProvincia(), empresa::setCodigoProvincia);
        aplicarSiNoEsNulo(request.canton(), empresa::setCanton);
        aplicarSiNoEsNulo(request.distrito(), empresa::setDistrito);
        aplicarSiNoEsNulo(request.barrio(), empresa::setBarrio);
        aplicarSiNoEsNulo(request.otrasSenas(), empresa::setOtrasSenas);
        aplicarSiNoEsNulo(request.telefono(), empresa::setTelefono);
        aplicarSiNoEsNulo(request.email(), empresa::setEmail);
        empresa.setUpdateDate(LocalDateTime.now());

        ubicacionValidator.validar(
                empresa.getCodigoProvincia(), empresa.getCanton(), empresa.getDistrito(), empresa.getOtrasSenas());

        return guardarYReleer(empresa);
    }

    /**
     * Valida el PIN contra el propio {@code .p12} ANTES de tocar Vault o la base de datos
     * (sección 6.4): si el PIN es incorrecto, no debe quedar ningún rastro ni en Vault ni en
     * {@code certificado_hacienda}. Al tener éxito: envelope encryption vía una DEK nueva de
     * {@link TransitService#generarDek()}, PIN en Vault KV, y upsert de
     * {@code certificado_hacienda} para el ambiente indicado, todo atómicamente en la misma
     * transacción.
     */
    @Transactional
    public EmpresaResponse cargarCertificado(byte[] certificadoP12, String pin, String ambiente) {
        validarPin(certificadoP12, pin);

        Empresa empresa = obtenerEmpresaActual();
        UUID empresaId = empresa.getId();

        TransitService.Dek dek = transitService.generarDek();
        byte[] p12Cifrado;
        try {
            p12Cifrado = EnvelopeCipher.cifrar(dek.plaintext(), certificadoP12);
        } finally {
            // Descarte inmediato de la DEK en texto plano (sección 6.1) -- solo su versión
            // cifrada (dek.cifrado()) se persiste.
            Arrays.fill(dek.plaintext(), (byte) 0);
        }

        String subruta = "certificado/" + ambiente.toLowerCase(Locale.ROOT) + "/pin";
        secretosKvService.guardarSecreto(empresaId, subruta, pin);

        CertificadoHacienda cert = certificadoHaciendaRepository
                .findByEmpresaIdAndAmbiente(empresaId, ambiente)
                .orElseGet(() -> {
                    CertificadoHacienda nuevo = new CertificadoHacienda();
                    nuevo.setEmpresaId(empresaId);
                    nuevo.setAmbiente(ambiente);
                    return nuevo;
                });
        cert.setCertificadoReferencia(referenciaCompleta(empresaId, subruta));
        cert.setCertificadoP12Cifrado(p12Cifrado);
        cert.setCertificadoDekCifrada(dek.cifrado());
        certificadoHaciendaRepository.saveAndFlush(cert);

        empresa.setUpdateDate(LocalDateTime.now());

        return guardarYReleer(empresa);
    }

    /**
     * Hace upsert de {@code credencial_hacienda} para el ambiente indicado (la tabla tiene
     * {@code UNIQUE(empresa_id, ambiente)}): si ya existe una fila para este ambiente -- por
     * ejemplo, un admin corrigiendo un usuario/password tipeado mal -- se actualiza en el
     * lugar en vez de intentar un segundo INSERT. Inserta/actualiza {@code credencial_hacienda}
     * y solo DESPUÉS actualiza {@code empresa} -- el trigger de status consulta
     * {@code credencial_hacienda} en el momento del {@code UPDATE}, así que la fila debe
     * existir antes de ese paso.
     */
    @Transactional
    public EmpresaResponse configurarCredencialHacienda(String usuarioHacienda, String password, String ambiente, UUID configuradoPor) {
        Empresa empresa = obtenerEmpresaActual();
        UUID empresaId = empresa.getId();

        String subruta = subrutaHacienda(ambiente);
        secretosKvService.guardarSecreto(empresaId, subruta, password);

        CredencialHacienda credencial = credencialHaciendaRepository
                .findByEmpresaIdAndAmbiente(empresaId, ambiente)
                .orElseGet(() -> {
                    CredencialHacienda nueva = new CredencialHacienda();
                    nueva.setEmpresaId(empresaId);
                    nueva.setAmbiente(ambiente);
                    return nueva;
                });
        credencial.setUsuarioHacienda(usuarioHacienda);
        credencial.setCredencialReferencia(referenciaCompleta(empresaId, subruta));
        credencial.setConfiguradaEn(LocalDateTime.now());
        credencial.setConfiguradaPor(configuradoPor);
        credencialHaciendaRepository.saveAndFlush(credencial);

        // Ningún campo propio de empresa cambia aquí -- se toca update_date únicamente para
        // forzar el UPDATE (y por lo tanto el trigger) que recalcula el status ahora que la
        // credencial ya existe.
        empresa.setUpdateDate(LocalDateTime.now());

        return guardarYReleer(empresa);
    }

    /**
     * Cambia el ambiente activo de Hacienda para la empresa actual. Si el ambiente solicitado
     * ya es el activo, devuelve el estado actual sin modificar nada. Al cambiar a PRODUCCION
     * desde SANDBOX se validan las precondiciones: la empresa debe estar HABILITADA, deben
     * existir credenciales de PRODUCCION y un certificado {@code .p12} de PRODUCCION
     * previamente configurados. Registra el cambio en {@code empresa_ambiente_historial}.
     */
    @Transactional
    public EmpresaResponse activarAmbiente(String ambiente, UUID activadoPor) {
        Empresa empresa = obtenerEmpresaActual();

        if (ambiente.equals(empresa.getAmbienteHacienda())) {
            boolean tieneCertificado = certificadoHaciendaRepository
                    .findByEmpresaIdAndAmbiente(empresa.getId(), empresa.getAmbienteHacienda())
                    .isPresent();
            return EmpresaResponse.desde(empresa, tieneCertificado);
        }

        if ("PRODUCCION".equals(ambiente)) {
            if (!"HABILITADA".equals(empresa.getStatus())) {
                throw new AmbienteNoDisponibleException(
                        "La empresa debe estar en estado HABILITADA para activar el ambiente PRODUCCION");
            }
            if (credencialHaciendaRepository.findByEmpresaIdAndAmbiente(empresa.getId(), "PRODUCCION").isEmpty()) {
                throw new AmbienteNoDisponibleException(
                        "No existen credenciales de PRODUCCION configuradas para esta empresa");
            }
            if (certificadoHaciendaRepository.findByEmpresaIdAndAmbiente(empresa.getId(), "PRODUCCION").isEmpty()) {
                throw new AmbienteNoDisponibleException(
                        "No existe un certificado .p12 de PRODUCCION configurado para esta empresa");
            }
        }

        EmpresaAmbienteHistorial historial = new EmpresaAmbienteHistorial();
        historial.setEmpresaId(empresa.getId());
        historial.setAmbienteAnterior(empresa.getAmbienteHacienda());
        historial.setAmbienteNuevo(ambiente);
        historial.setActivadoPor(activadoPor);
        historial.setFecha(LocalDateTime.now());
        empresaAmbienteHistorialRepository.save(historial);

        empresa.setAmbienteHacienda(ambiente);
        empresa.setUpdateDate(LocalDateTime.now());

        return guardarYReleer(empresa);
    }

    private void validarPin(byte[] certificadoP12, String pin) {
        try {
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE_PKCS12);
            keyStore.load(new ByteArrayInputStream(certificadoP12), pin.toCharArray());
        } catch (GeneralSecurityException | java.io.IOException excepcion) {
            throw new CertificadoInvalidoException(
                    "El archivo .p12 o el PIN proporcionado no son válidos", excepcion);
        }
    }

    @Transactional(readOnly = true)
    public EmpresaResponse consultar() {
        Empresa empresa = obtenerEmpresaActual();
        boolean tieneCertificado = certificadoHaciendaRepository
                .findByEmpresaIdAndAmbiente(empresa.getId(), empresa.getAmbienteHacienda())
                .isPresent();
        return EmpresaResponse.desde(empresa, tieneCertificado);
    }

    private Empresa obtenerEmpresaActual() {
        UUID empresaId = TenantContext.get();
        return empresaRepository.findById(empresaId)
                .orElseThrow(() -> new IllegalStateException(
                        "TenantContext resuelto a un empresa_id inexistente: " + empresaId));
    }

    private EmpresaResponse guardarYReleer(Empresa empresa) {
        empresaRepository.saveAndFlush(empresa);
        // Ver el javadoc de la clase: status/update_date pueden haber sido reescritos por el
        // trigger del lado del servidor -- refresh obligatorio para no devolver valores
        // obsoletos en la respuesta.
        entityManager.refresh(empresa);
        boolean tieneCertificado = certificadoHaciendaRepository
                .findByEmpresaIdAndAmbiente(empresa.getId(), empresa.getAmbienteHacienda())
                .isPresent();
        return EmpresaResponse.desde(empresa, tieneCertificado);
    }

    private static String subrutaHacienda(String ambiente) {
        return "hacienda/" + ambiente.toLowerCase(Locale.ROOT) + "/password";
    }

    private static String referenciaCompleta(UUID empresaId, String subruta) {
        return "secret/data/empresas/" + empresaId + "/" + subruta;
    }

    private static <T> void aplicarSiNoEsNulo(T valor, Consumer<T> setter) {
        if (valor != null) {
            setter.accept(valor);
        }
    }
}
