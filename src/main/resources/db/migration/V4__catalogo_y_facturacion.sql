-- Sección 4.9: consecutivos de comprobantes (separado por ambiente)
CREATE TABLE contador_consecutivo (
    empresa_id       UUID NOT NULL REFERENCES empresa(id),
    ambiente         VARCHAR(10) NOT NULL CHECK (ambiente IN ('SANDBOX', 'PRODUCCION')),
    tipo_comprobante VARCHAR(2) NOT NULL,
    valor_actual     BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (empresa_id, ambiente, tipo_comprobante)
);

-- Sección 4.10: producto
CREATE TABLE producto (
    id                  UUID PRIMARY KEY DEFAULT uuidv7(),
    empresa_id          UUID NOT NULL REFERENCES empresa(id),  -- @TenantId
    codigo              VARCHAR(50) NOT NULL,
    descripcion         VARCHAR(255) NOT NULL,
    codigo_cabys        VARCHAR(13) NOT NULL,          -- SIN DEFAULT: elimina el fallback genérico del proyecto original
    descripcion_cabys   VARCHAR(255),                   -- cacheado desde la respuesta de la API al momento de validar
    cabys_validado_en   TIMESTAMP NOT NULL,              -- prueba de que se validó contra api.hacienda.go.cr, no tecleado a mano
    codigo_unidad_fe    VARCHAR(20) NOT NULL DEFAULT 'Unid',
    precio_venta        NUMERIC(14,5) NOT NULL CHECK (precio_venta >= 0),
    gravado             BOOLEAN NOT NULL DEFAULT true,
    porcentaje_impuesto NUMERIC(5,2) NOT NULL DEFAULT 13.00,
    activo              BOOLEAN NOT NULL DEFAULT true,
    create_date         TIMESTAMP NOT NULL DEFAULT now(),
    update_date         TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (empresa_id, codigo)
);

-- Sección 4.11: cliente
CREATE TABLE cliente (
    id                        UUID PRIMARY KEY DEFAULT uuidv7(),
    empresa_id                UUID NOT NULL REFERENCES empresa(id),  -- @TenantId
    nombre                    VARCHAR(255) NOT NULL,
    tipo_identificacion       VARCHAR(2) NOT NULL,   -- '01' física, '02' jurídica, '03' DIMEX, '04' NITE
    numero_identificacion     VARCHAR(20) NOT NULL,
    codigo_actividad          VARCHAR(6),             -- opcional; solo relevante para créditos/gastos
    codigo_provincia          VARCHAR(1),
    canton                    VARCHAR(2),
    distrito                  VARCHAR(2),
    otras_senas               VARCHAR(300),
    telefono                  VARCHAR(20),
    email                     VARCHAR(255),
    requiere_factura_electronica BOOLEAN NOT NULL DEFAULT true,
    create_date               TIMESTAMP NOT NULL DEFAULT now(),
    update_date               TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (empresa_id, numero_identificacion),
    CHECK (
        (codigo_provincia IS NULL AND canton IS NULL AND distrito IS NULL AND otras_senas IS NULL)
        OR
        (codigo_provincia IS NOT NULL AND canton IS NOT NULL AND distrito IS NOT NULL
         AND otras_senas IS NOT NULL AND length(trim(otras_senas)) >= 5)
    )
);

-- Sección 4.12: factura -- la orden comercial
CREATE TABLE factura (
    id               UUID PRIMARY KEY DEFAULT uuidv7(),
    empresa_id       UUID NOT NULL REFERENCES empresa(id),  -- @TenantId
    cliente_id       UUID NOT NULL REFERENCES cliente(id),
    condicion_venta  VARCHAR(2) NOT NULL DEFAULT '01',
    plazo_credito    INT,
    medio_pago       VARCHAR(2) NOT NULL DEFAULT '01',
    moneda           VARCHAR(3) NOT NULL DEFAULT 'CRC',
    tipo_cambio      NUMERIC(10,5) NOT NULL DEFAULT 1.00000,
    subtotal         NUMERIC(14,5) NOT NULL,
    total_impuesto   NUMERIC(14,5) NOT NULL,
    total            NUMERIC(14,5) NOT NULL,
    creado_por       UUID NOT NULL REFERENCES usuario(id),
    create_date      TIMESTAMP NOT NULL DEFAULT now(),
    update_date      TIMESTAMP NOT NULL DEFAULT now(),
    CHECK (condicion_venta <> '02' OR plazo_credito IS NOT NULL)
);

-- Sección 4.13: comprobante_electronico -- el envoltorio fiscal
CREATE TABLE comprobante_electronico (
    id                UUID PRIMARY KEY DEFAULT uuidv7(),
    empresa_id        UUID NOT NULL REFERENCES empresa(id),  -- @TenantId
    factura_id        UUID NOT NULL UNIQUE REFERENCES factura(id),
    ambiente_hacienda VARCHAR(10) NOT NULL,   -- SNAPSHOT: no se lee de empresa.ambiente_hacienda en el momento de la consulta
    tipo_comprobante  VARCHAR(2) NOT NULL DEFAULT '01',
    consecutivo       VARCHAR(20) NOT NULL,
    clave_numerica    VARCHAR(50) NOT NULL UNIQUE,
    estado            VARCHAR(20) NOT NULL DEFAULT 'GENERADO',
    xml_comprobante   TEXT,
    xml_respuesta     TEXT,
    codigo_respuesta  VARCHAR(10),
    mensaje_respuesta VARCHAR(500),
    intentos_envio    INT NOT NULL DEFAULT 0,
    fecha_emision     TIMESTAMP NOT NULL DEFAULT now(),
    fecha_respuesta   TIMESTAMP,
    UNIQUE (empresa_id, ambiente_hacienda, tipo_comprobante, consecutivo)
);

-- Sección 4.14: linea_factura
CREATE TABLE linea_factura (
    id                    UUID PRIMARY KEY DEFAULT uuidv7(),
    empresa_id            UUID NOT NULL REFERENCES empresa(id),  -- @TenantId
    factura_id            UUID NOT NULL REFERENCES factura(id),
    producto_id           UUID NOT NULL REFERENCES producto(id),
    numero_linea          INT NOT NULL,
    cantidad              NUMERIC(10,3) NOT NULL CHECK (cantidad > 0),
    precio_unitario       NUMERIC(14,5) NOT NULL CHECK (precio_unitario >= 0),
    subtotal              NUMERIC(14,5) NOT NULL,
    codigo_cabys_aplicado  VARCHAR(13) NOT NULL,   -- snapshot del producto al momento de la venta
    gravado_aplicado       BOOLEAN NOT NULL,        -- snapshot
    porcentaje_impuesto_aplicado NUMERIC(5,2) NOT NULL,  -- snapshot
    UNIQUE (factura_id, numero_linea)
);

-- Sección 4.15: integridad cruzada entre tenants
CREATE OR REPLACE FUNCTION fn_validar_mismo_tenant()
RETURNS TRIGGER AS $$
DECLARE
    v_empresa_referencia UUID;
BEGIN
    IF TG_TABLE_NAME = 'factura' THEN
        SELECT empresa_id INTO v_empresa_referencia FROM cliente WHERE id = NEW.cliente_id;
    ELSIF TG_TABLE_NAME = 'linea_factura' THEN
        SELECT empresa_id INTO v_empresa_referencia FROM producto WHERE id = NEW.producto_id;
    END IF;

    IF v_empresa_referencia IS DISTINCT FROM NEW.empresa_id THEN
        RAISE EXCEPTION 'Referencia cruzada entre tenants no permitida (empresa % intentando usar recurso de empresa %)',
            NEW.empresa_id, v_empresa_referencia;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_validar_tenant_factura
    BEFORE INSERT OR UPDATE ON factura
    FOR EACH ROW EXECUTE FUNCTION fn_validar_mismo_tenant();

CREATE TRIGGER trg_validar_tenant_linea_factura
    BEFORE INSERT OR UPDATE ON linea_factura
    FOR EACH ROW EXECUTE FUNCTION fn_validar_mismo_tenant();
