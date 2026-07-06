-- Fase 5, sección 6.4: certificado .p12 como blob cifrado en PostgreSQL, mismo patrón de
-- envelope encryption vía datakey de Transit ya usado para el secreto MFA (sección 3.3).
--
-- certificado_referencia (ya existente desde V2__empresa_y_credenciales.sql) sigue siendo
-- la ruta del PIN en Vault KV v2 (sección 6.2) -- nunca el PIN ni el .p12 en texto plano.
-- Estas dos columnas nuevas son el blob del .p12 cifrado y su DEK cifrada; las tres columnas
-- (certificado_referencia, certificado_p12_cifrado, certificado_dek_cifrada) se escriben
-- siempre atómicamente en la misma transacción -- ver EmpresaService#cargarCertificado.
--
-- No se toca fn_actualizar_status_empresa/trg_actualizar_status_empresa: el trigger sigue
-- decidiendo CERTIFICADO_PENDIENTE únicamente en base a certificado_referencia IS NULL, lo
-- cual sigue siendo válido porque esa columna nunca se escribe sin estas dos también.
ALTER TABLE empresa
    ADD COLUMN certificado_p12_cifrado BYTEA,
    ADD COLUMN certificado_dek_cifrada BYTEA;
