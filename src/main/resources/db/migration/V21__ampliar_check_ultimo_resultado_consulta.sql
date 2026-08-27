-- V21: Extend the ultimo_resultado_consulta CHECK constraint to accept ERROR_CONFIGURACION.
-- V12 added the CHECK inline on ALTER TABLE ADD COLUMN, so Postgres auto-named it
-- comprobante_electronico_ultimo_resultado_consulta_check. Drop it by that real name and
-- re-add it explicitly named, widened with the new configuration-cause outcome.
-- No new columns/counters -- see design decision "cap semantics" (Option B rejected).

ALTER TABLE comprobante_electronico
    DROP CONSTRAINT IF EXISTS comprobante_electronico_ultimo_resultado_consulta_check;

ALTER TABLE comprobante_electronico
    ADD CONSTRAINT comprobante_electronico_ultimo_resultado_consulta_check
        CHECK (ultimo_resultado_consulta IN
            ('PENDIENTE', 'ACEPTADO', 'RECHAZADO', 'ERROR_COMUNICACION', 'ERROR_CONFIGURACION'));
