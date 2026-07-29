-- 1. New per-environment certificate table (mirrors credencial_hacienda)
CREATE TABLE certificado_hacienda (
    id                      UUID PRIMARY KEY DEFAULT uuidv7(),
    empresa_id              UUID NOT NULL REFERENCES empresa(id),
    ambiente                VARCHAR(10) NOT NULL CHECK (ambiente IN ('SANDBOX', 'PRODUCCION')),
    certificado_referencia  VARCHAR(255) NOT NULL,
    certificado_p12_cifrado BYTEA NOT NULL,
    certificado_dek_cifrada BYTEA NOT NULL,
    UNIQUE (empresa_id, ambiente)
);

-- 2. Rewrite fn_actualizar_status_empresa: replace certificado_referencia IS NULL check
--    with EXISTS on the new table (SANDBOX cert required for HABILITADA)
CREATE OR REPLACE FUNCTION fn_actualizar_status_empresa()
RETURNS TRIGGER AS $$
DECLARE
    v_nuevo_status VARCHAR(35);
BEGIN
    IF NEW.status IN ('SUSPENDIDA', 'BAJA') THEN
        RETURN NEW;
    END IF;

    IF NEW.numero_identificacion IS NULL OR NEW.codigo_actividad IS NULL
       OR NEW.codigo_provincia IS NULL OR NEW.canton IS NULL OR NEW.distrito IS NULL
       OR NEW.otras_senas IS NULL OR length(trim(NEW.otras_senas)) < 5 THEN
        v_nuevo_status := 'DATOS_FISCALES_INCOMPLETOS';
    ELSIF NOT EXISTS (
        SELECT 1 FROM certificado_hacienda
        WHERE empresa_id = NEW.id AND ambiente = 'SANDBOX'
    ) THEN
        v_nuevo_status := 'CERTIFICADO_PENDIENTE';
    ELSIF NOT EXISTS (
        SELECT 1 FROM credencial_hacienda
        WHERE empresa_id = NEW.id AND ambiente = 'SANDBOX'
    ) THEN
        v_nuevo_status := 'CREDENCIALES_HACIENDA_PENDIENTES';
    ELSE
        v_nuevo_status := 'HABILITADA';
    END IF;

    IF v_nuevo_status IS DISTINCT FROM OLD.status THEN
        INSERT INTO empresa_status_historial (empresa_id, status_anterior, status_nuevo, tipo_cambio)
        VALUES (NEW.id, OLD.status, v_nuevo_status, 'AUTOMATICO');
    END IF;

    NEW.status      := v_nuevo_status;
    NEW.update_date := now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- 3. Rewrite fn_validar_transicion_ambiente: add PRODUCCION certificate check
CREATE OR REPLACE FUNCTION fn_validar_transicion_ambiente()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.ambiente_hacienda = 'PRODUCCION' AND OLD.ambiente_hacienda = 'SANDBOX' THEN
        IF NEW.status <> 'HABILITADA' THEN
            RAISE EXCEPTION 'No se puede activar producción: la empresa no está en estado HABILITADA';
        END IF;
        IF NOT EXISTS (
            SELECT 1 FROM credencial_hacienda
            WHERE empresa_id = NEW.id AND ambiente = 'PRODUCCION'
        ) THEN
            RAISE EXCEPTION 'No se puede activar producción: faltan credenciales de Hacienda para ese ambiente';
        END IF;
        IF NOT EXISTS (
            SELECT 1 FROM certificado_hacienda
            WHERE empresa_id = NEW.id AND ambiente = 'PRODUCCION'
        ) THEN
            RAISE EXCEPTION 'No se puede activar producción: falta el certificado .p12 para ese ambiente';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- 4. Drop the three certificate columns from empresa
ALTER TABLE empresa
    DROP COLUMN IF EXISTS certificado_referencia,
    DROP COLUMN IF EXISTS certificado_p12_cifrado,
    DROP COLUMN IF EXISTS certificado_dek_cifrada;
