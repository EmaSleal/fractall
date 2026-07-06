-- Sección 4.1: empresa y su historial de estado
CREATE TABLE empresa (
    id                     UUID PRIMARY KEY DEFAULT uuidv7(),
    razon_social           VARCHAR(255) NOT NULL,
    nombre_comercial       VARCHAR(255),
    numero_identificacion  VARCHAR(20) UNIQUE,
    tipo_identificacion    VARCHAR(2),
    codigo_actividad       VARCHAR(6),
    codigo_provincia       VARCHAR(1),
    canton                 VARCHAR(2),
    distrito               VARCHAR(2),
    barrio                 VARCHAR(100),
    otras_senas            VARCHAR(300),
    telefono               VARCHAR(20),
    email                  VARCHAR(255),
    ambiente_hacienda      VARCHAR(10) NOT NULL DEFAULT 'SANDBOX'
                               CHECK (ambiente_hacienda IN ('SANDBOX', 'PRODUCCION')),
    certificado_referencia VARCHAR(255),  -- ruta del secreto en Vault; NUNCA el .p12 ni el PIN en esta tabla
    status                 VARCHAR(35) NOT NULL DEFAULT 'REGISTRADA',
    creado_por             UUID NOT NULL REFERENCES usuario(id),
    create_date            TIMESTAMP NOT NULL DEFAULT now(),
    update_date            TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE empresa_status_historial (
    id              UUID PRIMARY KEY DEFAULT uuidv7(),
    empresa_id      UUID NOT NULL REFERENCES empresa(id),
    status_anterior VARCHAR(35),
    status_nuevo    VARCHAR(35) NOT NULL,
    tipo_cambio     VARCHAR(15) NOT NULL CHECK (tipo_cambio IN ('AUTOMATICO', 'ADMINISTRATIVO')),
    motivo          VARCHAR(255),
    ejecutado_por   UUID REFERENCES usuario(id),  -- NULL si es AUTOMATICO
    fecha           TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE empresa_ambiente_historial (
    id                UUID PRIMARY KEY DEFAULT uuidv7(),
    empresa_id        UUID NOT NULL REFERENCES empresa(id),
    ambiente_anterior VARCHAR(10) NOT NULL,
    ambiente_nuevo    VARCHAR(10) NOT NULL,
    activado_por      UUID NOT NULL REFERENCES usuario(id),
    fecha             TIMESTAMP NOT NULL DEFAULT now()
);

-- Cálculo automático del estado derivado
-- (REGISTRADA -> DATOS_FISCALES_INCOMPLETOS -> CERTIFICADO_PENDIENTE ->
--  CREDENCIALES_HACIENDA_PENDIENTES -> HABILITADA).
-- Los estados administrativos (SUSPENDIDA, BAJA) nunca se sobrescriben automáticamente.
-- Nota: referencia credencial_hacienda (definida más abajo en este mismo archivo);
-- PL/pgSQL no valida la existencia de tablas al CREATE, solo en la primera ejecución.
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
    ELSIF NEW.certificado_referencia IS NULL THEN
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

CREATE TRIGGER trg_actualizar_status_empresa
    BEFORE UPDATE ON empresa
    FOR EACH ROW EXECUTE FUNCTION fn_actualizar_status_empresa();

-- Validación dura de la transición sandbox -> producción
-- (la transición inversa queda sin bloqueo técnico, por decisión explícita, ver sección 2).
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
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_validar_transicion_ambiente
    BEFORE UPDATE ON empresa
    FOR EACH ROW EXECUTE FUNCTION fn_validar_transicion_ambiente();

-- Sección 4.2: credenciales de Hacienda por ambiente
CREATE TABLE credencial_hacienda (
    id                    UUID PRIMARY KEY DEFAULT uuidv7(),
    empresa_id            UUID NOT NULL REFERENCES empresa(id),
    ambiente              VARCHAR(10) NOT NULL CHECK (ambiente IN ('SANDBOX', 'PRODUCCION')),
    usuario_hacienda      VARCHAR(255) NOT NULL,
    credencial_referencia VARCHAR(255) NOT NULL,  -- ruta del secreto en Vault, nunca la contraseña
    configurada_en        TIMESTAMP NOT NULL DEFAULT now(),
    configurada_por       UUID NOT NULL REFERENCES usuario(id),
    UNIQUE (empresa_id, ambiente)
);
