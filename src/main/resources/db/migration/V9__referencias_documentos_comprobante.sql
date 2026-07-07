-- Sección 6.4: XML de comprobante, XML de respuesta y PDF viven en Oracle Object Storage,
-- nunca embebidos en PostgreSQL -- V4 los creó como TEXT antes de que esta decisión se cerrara;
-- esta migración los reemplaza por punteros VARCHAR, mismo patrón ya usado por
-- empresa.certificado_referencia y credencial_hacienda.credencial_referencia.
ALTER TABLE comprobante_electronico
    DROP COLUMN xml_comprobante,
    DROP COLUMN xml_respuesta,
    ADD COLUMN xml_comprobante_referencia VARCHAR(255),
    ADD COLUMN xml_respuesta_referencia VARCHAR(255),
    ADD COLUMN pdf_referencia VARCHAR(255);
