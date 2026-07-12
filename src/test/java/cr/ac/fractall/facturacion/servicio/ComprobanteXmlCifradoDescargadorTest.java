package cr.ac.fractall.facturacion.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.security.SecureRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import cr.ac.fractall.almacenamiento.ObjectStorageService;
import cr.ac.fractall.secretos.EnvelopeCipher;
import cr.ac.fractall.secretos.TransitService;

@ExtendWith(MockitoExtension.class)
class ComprobanteXmlCifradoDescargadorTest {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String RUTA_OBJETO = "empresas/uuid-empresa/comprobantes/50601.xml.enc";

    @Mock
    private TransitService transitService;

    @Mock
    private ObjectStorageService objectStorageService;

    private ComprobanteXmlCifradoDescargador descargador;

    @BeforeEach
    void setUp() {
        descargador = new ComprobanteXmlCifradoDescargador(transitService, objectStorageService);
    }

    @Test
    void descargarYDescifrarDevuelveElContenidoOriginalEnClaro() {
        byte[] dekPlano = dekAleatoria();
        byte[] contenidoOriginal = "<?xml version=\"1.0\"?><FacturaElectronica/>".getBytes();

        byte[] xmlCifrado = EnvelopeCipher.cifrar(dekPlano, contenidoOriginal);
        byte[] dekEnvuelta = "vault:v1:dek-envuelta-simulada".getBytes();
        byte[] blob = ComprobanteXmlBlobFormat.serializar(dekEnvuelta, xmlCifrado);

        when(objectStorageService.descargar(RUTA_OBJETO)).thenReturn(blob);
        when(transitService.descifrarDek(dekEnvuelta)).thenReturn(dekPlano);

        byte[] resultado = descargador.descargarYDescifrar(RUTA_OBJETO);

        assertThat(resultado).isEqualTo(contenidoOriginal);
        verify(transitService, times(1)).descifrarDek(dekEnvuelta);
    }

    @Test
    void descargarYDescifrarConContenidoVacioNoLanza() {
        byte[] dekPlano = dekAleatoria();
        byte[] contenidoVacio = new byte[0];

        byte[] xmlCifrado = EnvelopeCipher.cifrar(dekPlano, contenidoVacio);
        byte[] dekEnvuelta = "vault:v1:dek-envuelta-vacio".getBytes();
        byte[] blob = ComprobanteXmlBlobFormat.serializar(dekEnvuelta, xmlCifrado);

        when(objectStorageService.descargar(RUTA_OBJETO)).thenReturn(blob);
        when(transitService.descifrarDek(dekEnvuelta)).thenReturn(dekPlano);

        byte[] resultado = descargador.descargarYDescifrar(RUTA_OBJETO);

        assertThat(resultado).isEmpty();
    }

    @Test
    void descargarYDescifrarConBlobCorruptoLanzaIllegalStateException() {
        byte[] dekPlano = dekAleatoria();
        byte[] dekEnvuelta = "vault:v1:dek-envuelta-corrupcion".getBytes();
        byte[] xmlCifradoCorrupto = bytesAleatorios(48);
        byte[] blob = ComprobanteXmlBlobFormat.serializar(dekEnvuelta, xmlCifradoCorrupto);

        when(objectStorageService.descargar(RUTA_OBJETO)).thenReturn(blob);
        when(transitService.descifrarDek(dekEnvuelta)).thenReturn(dekPlano);

        assertThatThrownBy(() -> descargador.descargarYDescifrar(RUTA_OBJETO))
                .isInstanceOf(IllegalStateException.class);
    }

    private static byte[] dekAleatoria() {
        byte[] dek = new byte[32];
        RANDOM.nextBytes(dek);
        return dek;
    }

    private static byte[] bytesAleatorios(int longitud) {
        byte[] bytes = new byte[longitud];
        RANDOM.nextBytes(bytes);
        return bytes;
    }
}
