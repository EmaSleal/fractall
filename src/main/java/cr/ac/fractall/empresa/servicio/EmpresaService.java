package cr.ac.fractall.empresa.servicio;

import java.io.ByteArrayInputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.UUID;
import java.util.function.Consumer;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cr.ac.fractall.empresa.modelo.CredencialHacienda;
import cr.ac.fractall.empresa.repositorio.CredencialHaciendaRepository;
import cr.ac.fractall.empresa.modelo.Empresa;
import cr.ac.fractall.empresa.repositorio.EmpresaRepository;
import cr.ac.fractall.empresa.dto.ActualizarDatosFiscalesRequest;
import cr.ac.fractall.empresa.dto.EmpresaResponse;
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
 * (datos fiscales, {@code certificado_referencia}, filas de {@code credencial_hacienda}) y
 * releer el resultado.
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

    private static final String SUBRUTA_CERTIFICADO_PIN = "certificado/pin";
    private static final String SUBRUTA_HACIENDA_SANDBOX_PASSWORD = "hacienda/sandbox/password";
    private static final String AMBIENTE_SANDBOX = "SANDBOX";
    private static final String KEYSTORE_PKCS12 = "PKCS12";

    private final EmpresaRepository empresaRepository;
    private final CredencialHaciendaRepository credencialHaciendaRepository;
    private final SecretosKvService secretosKvService;
    private final TransitService transitService;

    @PersistenceContext
    private EntityManager entityManager;

    public EmpresaService(
            EmpresaRepository empresaRepository,
            CredencialHaciendaRepository credencialHaciendaRepository,
            SecretosKvService secretosKvService,
            TransitService transitService) {
        this.empresaRepository = empresaRepository;
        this.credencialHaciendaRepository = credencialHaciendaRepository;
        this.secretosKvService = secretosKvService;
        this.transitService = transitService;
    }

    /**
     * Actualización PARCIAL (estilo PATCH): un campo {@code null} en el request deja el valor
     * actual de {@code empresa} intacto -- nunca lo sobrescribe. La transición de status (ej.
     * {@code REGISTRADA -> DATOS_FISCALES_INCOMPLETOS}) es responsabilidad exclusiva del
     * trigger, disparado por el propio {@code UPDATE}.
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

        return guardarYReleer(empresa);
    }

    /**
     * Valida el PIN contra el propio {@code .p12} ANTES de tocar Vault o la base de datos
     * (sección 6.4): si el PIN es incorrecto, no debe quedar ningún rastro ni en Vault ni en
     * {@code empresa}. Al tener éxito: envelope encryption vía una DEK nueva de
     * {@link TransitService#generarDek()}, PIN en Vault KV, y las tres columnas puntero
     * ({@code certificado_referencia}, {@code certificado_p12_cifrado},
     * {@code certificado_dek_cifrada}) se escriben atómicamente en la misma transacción.
     */
    @Transactional
    public EmpresaResponse cargarCertificado(byte[] certificadoP12, String pin) {
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

        secretosKvService.guardarSecreto(empresaId, SUBRUTA_CERTIFICADO_PIN, pin);

        empresa.setCertificadoReferencia(referenciaCompleta(empresaId, SUBRUTA_CERTIFICADO_PIN));
        empresa.setCertificadoP12Cifrado(p12Cifrado);
        empresa.setCertificadoDekCifrada(dek.cifrado());
        empresa.setUpdateDate(LocalDateTime.now());

        return guardarYReleer(empresa);
    }

    /**
     * Ambiente {@code SANDBOX} únicamente (fuera de alcance de la Fase 5: {@code PRODUCCION},
     * ver {@code plan-fases-release-1.md}). Hace upsert de {@code credencial_hacienda} (la
     * tabla tiene {@code UNIQUE(empresa_id, ambiente)}): si ya existe una fila SANDBOX para
     * esta empresa -- por ejemplo, un admin corrigiendo un usuario/password tipeado mal -- se
     * actualiza en el lugar en vez de intentar un segundo INSERT, que violaría esa restricción
     * y dejaría el endpoint inutilizable tras la primera llamada exitosa. Inserta/actualiza
     * {@code credencial_hacienda} y solo DESPUÉS actualiza {@code empresa} -- el trigger de
     * status consulta {@code credencial_hacienda} en el momento del {@code UPDATE}, así que la
     * fila debe existir (aunque sea sin commit todavía, misma transacción) antes de ese paso.
     */
    @Transactional
    public EmpresaResponse configurarCredencialHacienda(String usuarioHacienda, String password, UUID configuradoPor) {
        Empresa empresa = obtenerEmpresaActual();
        UUID empresaId = empresa.getId();

        secretosKvService.guardarSecreto(empresaId, SUBRUTA_HACIENDA_SANDBOX_PASSWORD, password);

        CredencialHacienda credencial = credencialHaciendaRepository
                .findByEmpresaIdAndAmbiente(empresaId, AMBIENTE_SANDBOX)
                .orElseGet(() -> {
                    CredencialHacienda nueva = new CredencialHacienda();
                    nueva.setEmpresaId(empresaId);
                    nueva.setAmbiente(AMBIENTE_SANDBOX);
                    return nueva;
                });
        credencial.setUsuarioHacienda(usuarioHacienda);
        credencial.setCredencialReferencia(referenciaCompleta(empresaId, SUBRUTA_HACIENDA_SANDBOX_PASSWORD));
        credencial.setConfiguradaEn(LocalDateTime.now());
        credencial.setConfiguradaPor(configuradoPor);
        credencialHaciendaRepository.saveAndFlush(credencial);

        // Ningún campo propio de empresa cambia aquí -- se toca update_date únicamente para
        // forzar el UPDATE (y por lo tanto el trigger) que recalcula el status ahora que la
        // credencial SANDBOX ya existe.
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
        return EmpresaResponse.desde(empresa);
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
