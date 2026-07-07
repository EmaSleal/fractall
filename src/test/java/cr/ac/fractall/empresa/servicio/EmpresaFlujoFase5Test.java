package cr.ac.fractall.empresa.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.vault.VaultContainer;

import cr.ac.fractall.empresa.modelo.CredencialHacienda;
import cr.ac.fractall.empresa.repositorio.CredencialHaciendaRepository;
import cr.ac.fractall.empresa.modelo.Empresa;
import cr.ac.fractall.empresa.repositorio.EmpresaRepository;
import cr.ac.fractall.empresa.dto.ActualizarDatosFiscalesRequest;
import cr.ac.fractall.secretos.SecretosKvService;
import cr.ac.fractall.seguridad.modelo.Usuario;
import cr.ac.fractall.seguridad.repositorio.UsuarioRepository;
import cr.ac.fractall.tenant.TenantContext;
import cr.ac.fractall.tenant.TenantContextDescartable;

/**
 * Prueba de integración de punta a punta de la Fase 5 (sección 4.1 y 6.4 de
 * {@code arquitectura-facturacion-electronica-cr.md}): conecta la capa de servicio a la
 * máquina de estados que YA vive en la base de datos desde la Fase 0
 * ({@code fn_actualizar_status_empresa}/{@code trg_actualizar_status_empresa}) -- esta prueba
 * NUNCA asigna {@code empresa.status} manualmente, solo llama métodos de
 * {@link EmpresaService} y observa el status resultante después de cada paso, exactamente
 * como pide el criterio de salida de la Fase 5 en {@code plan-fases-release-1.md}.
 *
 * <p>Mismo bootstrap de Postgres + Vault vía Testcontainers que
 * {@code SecretosVaultClienteTest}/{@code AuthFlujoMfaTest} -- ver el javadoc de esas clases
 * para el porqué.
 */
@Testcontainers
@SpringBootTest
class EmpresaFlujoFase5Test {

    private static final String ROOT_TOKEN = "test-root-token";
    private static final String POLICY_NAME = "empresa-secretos";
    private static final String TRANSIT_KEY = "empresa-datos-kek";
    private static final String APPROLE_NAME = "fractall-backend";
    private static final String PIN_VALIDO = "pin-de-prueba-1234";

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.1");

    @Container
    static VaultContainer<?> VAULT = new VaultContainer<>("hashicorp/vault:latest")
            .withVaultToken(ROOT_TOKEN);

    private static String roleId;
    private static String secretId;
    private static byte[] p12ValidoDePrueba;

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registry) throws Exception {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        bootstrapAppRole();

        registry.add("application.vault.addr", VAULT::getHttpHostAddress);
        registry.add("application.vault.role-id", () -> roleId);
        registry.add("application.vault.secret-id", () -> secretId);
    }

    @BeforeAll
    static void generarCertificadoDePrueba() throws Exception {
        p12ValidoDePrueba = generarP12(PIN_VALIDO);
    }

    private static void bootstrapAppRole() throws Exception {
        ejecutarVault("auth", "enable", "approle");
        ejecutarVault("secrets", "enable", "transit");
        ejecutarVault("write", "-f", "transit/keys/" + TRANSIT_KEY);

        String politicaHcl = """
                path "secret/data/empresas/*" {
                  capabilities = ["read", "create", "update"]
                }

                path "transit/keys/%s" {
                  capabilities = ["read", "create", "update"]
                }

                path "transit/datakey/plaintext/%s" {
                  capabilities = ["create", "update"]
                }

                path "transit/decrypt/%s" {
                  capabilities = ["create", "update"]
                }

                path "transit/encrypt/%s" {
                  capabilities = ["create", "update"]
                }
                """.formatted(TRANSIT_KEY, TRANSIT_KEY, TRANSIT_KEY, TRANSIT_KEY);
        ExecResult resultadoPolitica = VAULT.execInContainer(
                "sh", "-c",
                "cat <<'EOF' | vault policy write " + POLICY_NAME + " -\n" + politicaHcl + "EOF");
        assertThat(resultadoPolitica.getExitCode()).as(resultadoPolitica.getStderr()).isZero();

        ejecutarVault("write", "auth/approle/role/" + APPROLE_NAME,
                "token_policies=" + POLICY_NAME,
                "token_ttl=1h",
                "token_max_ttl=4h",
                "secret_id_ttl=0",
                "token_num_uses=0");

        roleId = ejecutarVault("read", "-field=role_id", "auth/approle/role/" + APPROLE_NAME + "/role-id")
                .getStdout().trim();
        secretId = ejecutarVault("write", "-field=secret_id", "-f", "auth/approle/role/" + APPROLE_NAME + "/secret-id")
                .getStdout().trim();
    }

    private static ExecResult ejecutarVault(String... comandoVault) throws Exception {
        String[] comandoCompleto = new String[comandoVault.length + 1];
        comandoCompleto[0] = "vault";
        System.arraycopy(comandoVault, 0, comandoCompleto, 1, comandoVault.length);

        ExecResult resultado = VAULT.execInContainer(comandoCompleto);
        assertThat(resultado.getExitCode()).as(resultado.getStderr()).isZero();
        return resultado;
    }

    /** Genera un {@code .p12} autofirmado real vía {@code keytool} del propio JDK que corre la prueba. */
    private static byte[] generarP12(String pin) throws Exception {
        Path archivo = Files.createTempFile("fractall-test-cert", ".p12");
        Files.deleteIfExists(archivo);
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    rutaKeytool(), "-genkeypair",
                    "-alias", "fractall-test",
                    "-keyalg", "RSA", "-keysize", "2048",
                    "-validity", "365",
                    "-storetype", "PKCS12",
                    "-keystore", archivo.toString(),
                    "-storepass", pin,
                    "-keypass", pin,
                    "-dname", "CN=Fractall Test, OU=QA, O=Fractall, L=San Jose, ST=SJ, C=CR");
            builder.redirectErrorStream(true);
            Process proceso = builder.start();
            String salida = new String(proceso.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int codigoSalida = proceso.waitFor();
            assertThat(codigoSalida).as("keytool -genkeypair: " + salida).isZero();
            return Files.readAllBytes(archivo);
        } finally {
            Files.deleteIfExists(archivo);
        }
    }

    private static String rutaKeytool() {
        String ruta = System.getProperty("java.home") + File.separator + "bin" + File.separator + "keytool";
        return new File(ruta + ".exe").exists() ? ruta + ".exe" : ruta;
    }

    @Autowired
    private EmpresaService empresaService;

    @Autowired
    private EmpresaRepository empresaRepository;

    @Autowired
    private CredencialHaciendaRepository credencialHaciendaRepository;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private SecretosKvService secretosKvService;

    /** Crea usuario + empresa en {@code REGISTRADA}, replicando el mínimo de {@code RegistroService}. */
    private UUID[] crearUsuarioYEmpresaRegistrada() {
        return TenantContextDescartable.ejecutar(() -> {
            LocalDateTime ahora = LocalDateTime.now();

            Usuario usuario = new Usuario();
            usuario.setNombre("Persona de prueba Fase 5");
            usuario.setEmail("fase5-" + UUID.randomUUID() + "@fractall.test");
            usuario.setPasswordHash("hash-no-relevante-para-esta-prueba");
            usuario.setEmailVerificado(true);
            usuario.setEstado("ACTIVA");
            usuario.setMfaHabilitado(false);
            usuario.setIntentosFallidos(0);
            usuario.setCreateDate(ahora);
            usuario.setUpdateDate(ahora);
            usuario = usuarioRepository.save(usuario);

            Empresa empresa = new Empresa();
            empresa.setRazonSocial("Empresa de Prueba Fase 5 S.A.");
            empresa.setAmbienteHacienda("SANDBOX");
            empresa.setStatus("REGISTRADA");
            empresa.setCreadoPor(usuario.getId());
            empresa.setCreateDate(ahora);
            empresa.setUpdateDate(ahora);
            empresa = empresaRepository.save(empresa);

            return new UUID[] {usuario.getId(), empresa.getId()};
        });
    }

    /** Puebla {@link TenantContext} con {@code empresaId} -- mismo efecto que {@code JwtTenantFilter} por request. */
    private <T> T comoTenant(UUID empresaId, Supplier<T> operacion) {
        TenantContext.set(empresaId);
        try {
            return operacion.get();
        } finally {
            TenantContext.clear();
        }
    }

    private String statusActual(UUID empresaId) {
        return TenantContextDescartable.ejecutar(
                () -> empresaRepository.findById(empresaId).orElseThrow().getStatus());
    }

    @Test
    void empresaTransitaDeRegistradaHastaHabilitadaAMedidaQueSeCompletanLosPasosDeLaFase5() {
        UUID[] datos = crearUsuarioYEmpresaRegistrada();
        UUID usuarioId = datos[0];
        UUID empresaId = datos[1];

        assertThat(statusActual(empresaId)).isEqualTo("REGISTRADA");

        // Paso 1: actualización parcial que deja datos fiscales incompletos a propósito
        // (solo nombreComercial) -- REGISTRADA -> DATOS_FISCALES_INCOMPLETOS.
        comoTenant(empresaId, () -> empresaService.actualizarDatosFiscales(new ActualizarDatosFiscalesRequest(
                null, "Nombre Comercial de Prueba", null, null, null, null, null, null, null, null, null, null)));
        assertThat(statusActual(empresaId)).isEqualTo("DATOS_FISCALES_INCOMPLETOS");

        // Paso 2: se completan todos los campos fiscales exigidos por el trigger ->
        // DATOS_FISCALES_INCOMPLETOS -> CERTIFICADO_PENDIENTE.
        var respuestaFiscal = comoTenant(empresaId, () -> empresaService.actualizarDatosFiscales(
                new ActualizarDatosFiscalesRequest(
                        null, null, "3101123456", "02", "620000", "1", "01", "01",
                        "Barrio Centro", "Del parque 200m norte", "22334455", "empresa@fractall.test")));
        assertThat(respuestaFiscal.status()).isEqualTo("CERTIFICADO_PENDIENTE");
        assertThat(statusActual(empresaId)).isEqualTo("CERTIFICADO_PENDIENTE");

        // Paso 3: certificado .p12 + PIN válidos -> CERTIFICADO_PENDIENTE -> CREDENCIALES_HACIENDA_PENDIENTES.
        var respuestaCertificado = comoTenant(empresaId,
                () -> empresaService.cargarCertificado(p12ValidoDePrueba, PIN_VALIDO));
        assertThat(respuestaCertificado.status()).isEqualTo("CREDENCIALES_HACIENDA_PENDIENTES");
        assertThat(statusActual(empresaId)).isEqualTo("CREDENCIALES_HACIENDA_PENDIENTES");

        TenantContextDescartable.ejecutar(() -> {
            Empresa empresa = empresaRepository.findById(empresaId).orElseThrow();
            assertThat(empresa.getCertificadoReferencia()).isNotBlank();
            assertThat(empresa.getCertificadoP12Cifrado()).isNotEmpty();
            assertThat(empresa.getCertificadoDekCifrada()).isNotEmpty();
            // Nunca el .p12 en claro en la columna cifrada.
            assertThat(empresa.getCertificadoP12Cifrado()).isNotEqualTo(p12ValidoDePrueba);
            return null;
        });
        assertThat(secretosKvService.leerSecreto(empresaId, "certificado/pin")).contains(PIN_VALIDO);

        // Paso 4: credenciales de Hacienda SANDBOX -> CREDENCIALES_HACIENDA_PENDIENTES -> HABILITADA.
        var respuestaCredencial = comoTenant(empresaId, () -> empresaService.configurarCredencialHacienda(
                "usuario.hacienda@fractall.test", "clave-hacienda-super-secreta", usuarioId));
        assertThat(respuestaCredencial.status()).isEqualTo("HABILITADA");
        assertThat(statusActual(empresaId)).isEqualTo("HABILITADA");

        TenantContextDescartable.ejecutar(() -> {
            CredencialHacienda credencial = credencialHaciendaRepository.findAll().stream()
                    .filter(c -> c.getEmpresaId().equals(empresaId))
                    .findFirst().orElseThrow();
            assertThat(credencial.getAmbiente()).isEqualTo("SANDBOX");
            assertThat(credencial.getUsuarioHacienda()).isEqualTo("usuario.hacienda@fractall.test");
            assertThat(credencial.getCredencialReferencia()).isNotBlank();
            assertThat(credencial.getConfiguradaPor()).isEqualTo(usuarioId);
            return null;
        });
        assertThat(secretosKvService.leerSecreto(empresaId, "hacienda/sandbox/password"))
                .contains("clave-hacienda-super-secreta");

        // Paso 5: se llama una SEGUNDA vez (ej. un admin corrigiendo un usuario/password
        // tipeado mal) -- debe actualizar la fila SANDBOX existente en vez de intentar un
        // segundo INSERT, que violaría UNIQUE(empresa_id, ambiente).
        var respuestaCredencialActualizada = comoTenant(empresaId, () -> empresaService.configurarCredencialHacienda(
                "usuario.hacienda.nuevo@fractall.test", "clave-hacienda-corregida", usuarioId));
        assertThat(respuestaCredencialActualizada.status()).isEqualTo("HABILITADA");

        TenantContextDescartable.ejecutar(() -> {
            var credencialesDeLaEmpresa = credencialHaciendaRepository.findAll().stream()
                    .filter(c -> c.getEmpresaId().equals(empresaId))
                    .toList();
            assertThat(credencialesDeLaEmpresa).hasSize(1);
            CredencialHacienda credencial = credencialesDeLaEmpresa.get(0);
            assertThat(credencial.getUsuarioHacienda()).isEqualTo("usuario.hacienda.nuevo@fractall.test");
            return null;
        });
        assertThat(secretosKvService.leerSecreto(empresaId, "hacienda/sandbox/password"))
                .contains("clave-hacienda-corregida");
    }

    @Test
    void cargarCertificadoConPinIncorrectoEsRechazadoYNoEscribeNadaEnDbNiEnVault() {
        UUID[] datos = crearUsuarioYEmpresaRegistrada();
        UUID empresaId = datos[1];

        assertThatThrownBy(() -> comoTenant(empresaId,
                () -> empresaService.cargarCertificado(p12ValidoDePrueba, "pin-completamente-incorrecto")))
                .isInstanceOf(CertificadoInvalidoException.class);

        TenantContextDescartable.ejecutar(() -> {
            Empresa empresa = empresaRepository.findById(empresaId).orElseThrow();
            assertThat(empresa.getCertificadoReferencia()).isNull();
            assertThat(empresa.getCertificadoP12Cifrado()).isNull();
            assertThat(empresa.getCertificadoDekCifrada()).isNull();
            // El status nunca debió avanzar más allá de REGISTRADA -- ningún campo fiscal se tocó.
            assertThat(empresa.getStatus()).isEqualTo("REGISTRADA");
            return null;
        });
        assertThat(secretosKvService.leerSecreto(empresaId, "certificado/pin")).isEmpty();
    }
}
