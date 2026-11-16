-- T-004 (ADR-055): la descripción sembrada en V1 decía "ante rechazo", pero un comprobante
-- RECHAZADO ya no es reenviable (un veredicto funcional de Hacienda no se reenvía; se emite un
-- comprobante nuevo). V1 no se edita: Flyway no permite modificar una migración ya aplicada.
UPDATE permiso
SET descripcion = 'Reenviar a Hacienda un comprobante firmado sin enviar o en error de envío'
WHERE codigo = 'factura.reenviar';
