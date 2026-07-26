-- V12: Add Hacienda query-tracking columns to comprobante_electronico.
-- These columns remain null/0 until the polling job populates them.

ALTER TABLE comprobante_electronico
    ADD COLUMN ultimo_resultado_consulta VARCHAR(20)
        CHECK (ultimo_resultado_consulta IN ('PENDIENTE','ACEPTADO','RECHAZADO','ERROR_COMUNICACION'));

ALTER TABLE comprobante_electronico
    ADD COLUMN fecha_ultima_consulta_hacienda TIMESTAMP;

ALTER TABLE comprobante_electronico
    ADD COLUMN intentos_consulta INT NOT NULL DEFAULT 0;
