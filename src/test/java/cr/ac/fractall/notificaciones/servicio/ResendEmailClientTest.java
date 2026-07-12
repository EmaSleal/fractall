package cr.ac.fractall.notificaciones.servicio;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Verifica la forma EXACTA de la solicitud saliente de {@link ResendEmailClient} (método,
 * ruta, header de autorización, cuerpo JSON) contra un {@link HttpServer} embebido del JDK
 * -- sin gastar cuota real de la API de Resend y sin mockear el cliente de una forma que
 * oculte si arma la solicitud correctamente.
 */
class ResendEmailClientTest {

    private HttpServer servidor;
    private final BlockingQueue<SolicitudCapturada> solicitudes = new LinkedBlockingQueue<>();

    @BeforeEach
    void iniciarStub() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress(0), 0);
        servidor.createContext("/emails", this::capturarYResponder);
        servidor.start();
    }

    @AfterEach
    void detenerStub() {
        servidor.stop(0);
    }

    private void capturarYResponder(HttpExchange exchange) throws IOException {
        String cuerpo = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        solicitudes.offer(new SolicitudCapturada(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("Content-Type"),
                cuerpo));

        byte[] respuesta = "{\"id\":\"stub-email-id\"}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, respuesta.length);
        try (OutputStream salida = exchange.getResponseBody()) {
            salida.write(respuesta);
        }
    }

    private ResendEmailClient clienteApuntandoAlStub(String apiKey, String remitente) {
        return new ResendEmailClient(apiKey, remitente, "http://localhost:" + servidor.getAddress().getPort() + "/emails");
    }

    @Test
    void enviaUnaSolicitudPostConLaFormaExactaQueEsperaLaApiDeResend() throws Exception {
        ResendEmailClient cliente = clienteApuntandoAlStub("re_test_api_key", "onboarding@resend.dev");

        boolean resultado = cliente.enviar("destino@fractall.test", "Asunto de prueba", "<p>hola</p>");

        assertThat(resultado).isTrue();
        SolicitudCapturada solicitud = solicitudes.poll(5, TimeUnit.SECONDS);
        assertThat(solicitud).isNotNull();
        assertThat(solicitud.metodo()).isEqualTo("POST");
        assertThat(solicitud.ruta()).isEqualTo("/emails");
        assertThat(solicitud.authorization()).isEqualTo("Bearer re_test_api_key");
        assertThat(solicitud.contentType()).contains("application/json");
        assertThat(solicitud.cuerpo())
                .contains("\"from\":\"onboarding@resend.dev\"")
                .contains("\"to\":[\"destino@fractall.test\"]")
                .contains("\"subject\":\"Asunto de prueba\"")
                .contains("\"html\":\"<p>hola</p>\"");
    }

    @Test
    void devuelveFalseSinLanzarCuandoElProveedorResponde4xx() {
        servidor.removeContext("/emails");
        servidor.createContext("/emails", exchange -> {
            byte[] respuesta = "{\"message\":\"tope diario alcanzado\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(429, respuesta.length);
            try (OutputStream salida = exchange.getResponseBody()) {
                salida.write(respuesta);
            }
        });

        ResendEmailClient cliente = clienteApuntandoAlStub("re_test_api_key", "onboarding@resend.dev");

        boolean resultado = cliente.enviar("destino@fractall.test", "Asunto", "<p>hola</p>");

        assertThat(resultado).isFalse();
    }

    // --- Tests para enviar(4-arg) con adjuntos (T-04, Fase 9) ---

    @Test
    void enviarConUnAdjuntoIncluyeAttachmentsEnElJson() throws Exception {
        ResendEmailClient cliente = clienteApuntandoAlStub("re_test_api_key", "onboarding@resend.dev");
        byte[] contenidoAdjunto = new byte[]{1, 2, 3};

        boolean resultado = cliente.enviar(
                "destino@fractall.test",
                "Factura Electrónica 00100001010000000001",
                "<p>Adjunto su factura</p>",
                List.of(new Adjunto("factura.pdf", contenidoAdjunto)));

        assertThat(resultado).isTrue();
        SolicitudCapturada solicitud = solicitudes.poll(5, TimeUnit.SECONDS);
        assertThat(solicitud).isNotNull();
        assertThat(solicitud.cuerpo()).contains("\"attachments\"");
        assertThat(solicitud.cuerpo()).contains("\"filename\":\"factura.pdf\"");
        String base64Esperado = Base64.getEncoder().encodeToString(contenidoAdjunto);
        assertThat(solicitud.cuerpo()).contains("\"content\":\"" + base64Esperado + "\"");
    }

    @Test
    void enviarConTresAdjuntosIncluyeLosTresEnElJson() throws Exception {
        ResendEmailClient cliente = clienteApuntandoAlStub("re_test_api_key", "onboarding@resend.dev");

        boolean resultado = cliente.enviar(
                "destino@fractall.test",
                "Factura con tres adjuntos",
                "<p>Adjuntos</p>",
                List.of(
                        new Adjunto("factura.pdf", new byte[]{10, 20}),
                        new Adjunto("factura.xml", new byte[]{30, 40}),
                        new Adjunto("respuesta.xml", new byte[]{50, 60})));

        assertThat(resultado).isTrue();
        SolicitudCapturada solicitud = solicitudes.poll(5, TimeUnit.SECONDS);
        assertThat(solicitud).isNotNull();
        assertThat(solicitud.cuerpo())
                .contains("\"filename\":\"factura.pdf\"")
                .contains("\"filename\":\"factura.xml\"")
                .contains("\"filename\":\"respuesta.xml\"");
    }

    @Test
    void enviarConAdjuntosDevuelveFalseSinLanzarCuandoElProveedorResponde4xx() {
        servidor.removeContext("/emails");
        servidor.createContext("/emails", exchange -> {
            byte[] respuesta = "{\"message\":\"error\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(422, respuesta.length);
            try (OutputStream salida = exchange.getResponseBody()) {
                salida.write(respuesta);
            }
        });

        ResendEmailClient cliente = clienteApuntandoAlStub("re_test_api_key", "onboarding@resend.dev");

        boolean resultado = cliente.enviar(
                "destino@fractall.test",
                "Asunto",
                "<p>html</p>",
                List.of(new Adjunto("factura.pdf", new byte[]{1})));

        assertThat(resultado).isFalse();
    }

    @Test
    void enviarSinAdjuntosSigueFuncionandoSinCampoAttachmentsEnElCuerpo() throws Exception {
        ResendEmailClient cliente = clienteApuntandoAlStub("re_test_api_key", "onboarding@resend.dev");

        boolean resultado = cliente.enviar("destino@fractall.test", "Asunto original", "<p>sin adjuntos</p>");

        assertThat(resultado).isTrue();
        SolicitudCapturada solicitud = solicitudes.poll(5, TimeUnit.SECONDS);
        assertThat(solicitud).isNotNull();
        assertThat(solicitud.cuerpo()).doesNotContain("attachments");
    }

    private record SolicitudCapturada(String metodo, String ruta, String authorization, String contentType, String cuerpo) {
    }
}
