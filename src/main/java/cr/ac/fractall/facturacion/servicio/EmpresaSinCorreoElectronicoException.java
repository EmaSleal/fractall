package cr.ac.fractall.facturacion.servicio;

import java.util.UUID;

/**
 * La {@code Empresa} no tiene {@code email} configurado -- mismo motivo/estilo que
 * {@code CredencialHaciendaNoEncontradaException}. {@code Emisor.CorreoElectronico} es un campo
 * obligatorio (sin {@code minOccurs="0"}, hasta 4 valores) en {@code FacturaElectronica_V4.4.xsd};
 * sin él, {@code XmlFacturaGeneratorServiceImpl#agregarEmisor} tendría que emitir un
 * {@code <CorreoElectronico>} vacío, que pasa la validación de esquema (no hay
 * {@code minLength}) pero que Hacienda puede rechazar en su validación de negocio -- mejor
 * fallar acá con un mensaje claro que dejar pasar un comprobante inválido.
 */
public class EmpresaSinCorreoElectronicoException extends RuntimeException {

    public EmpresaSinCorreoElectronicoException(UUID empresaId) {
        super("La empresa %s no tiene un correo electrónico configurado, requerido para emitir facturas electrónicas"
                .formatted(empresaId));
    }
}
