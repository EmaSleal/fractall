-- Fase A, Release 2 (Nota de Crédito/Débito/Tiquete): primera pieza de infraestructura de datos
-- para NC/ND -- el vínculo interno real hacia la factura origen y las reglas de negocio que
-- dependen de él. Ver docs/plan-fases-release-2.md, secciones "Reglas de negocio confirmadas"
-- (reglas 3, 4 y 7) y "Fase A". La regla 5 (motivo obligatorio si codigo='99') ya estaba cubierta
-- desde V11 -- ver la nota al final de este archivo.

-- =========================================================================
-- 1. factura.factura_referencia_id -- FK real hacia la factura origen de una NC/ND (regla 4:
--    vínculo interno vía FK real, no solo el campo de texto libre que exige el XML).
-- =========================================================================
-- Nullable: solo se puebla cuando tipo_comprobante IN ('02','03') (NC/ND). tipo_comprobante vive
-- en comprobante_electronico, no en factura (relación 1:1, ver V4), así que este ALTER no puede
-- condicionar el NOT NULL a un tipo de comprobante -- esa validación es responsabilidad de la
-- capa de servicio (Fase B), no del motor.
ALTER TABLE factura
    ADD COLUMN factura_referencia_id UUID REFERENCES factura(id);

-- =========================================================================
-- 2. Extiende fn_validar_mismo_tenant (V4, ya extendida en V10) para cubrir la nueva FK.
-- =========================================================================
-- CREATE OR REPLACE es seguro: trg_validar_tenant_factura y trg_validar_tenant_linea_factura
-- (ambos de V4) referencian esta función por nombre y no necesitan recrearse.
CREATE OR REPLACE FUNCTION fn_validar_mismo_tenant()
RETURNS TRIGGER AS $$
DECLARE
    v_empresa_referencia UUID;
    v_empresa_factura_referencia UUID;
    v_cliente_exoneracion UUID;
    v_cliente_factura UUID;
BEGIN
    IF TG_TABLE_NAME = 'factura' THEN
        SELECT empresa_id INTO v_empresa_referencia FROM cliente WHERE id = NEW.cliente_id;

        IF NEW.factura_referencia_id IS NOT NULL THEN
            SELECT empresa_id INTO v_empresa_factura_referencia
            FROM factura WHERE id = NEW.factura_referencia_id;

            IF v_empresa_factura_referencia IS DISTINCT FROM NEW.empresa_id THEN
                RAISE EXCEPTION 'Referencia cruzada entre tenants no permitida (empresa % intentando usar recurso de empresa %)',
                    NEW.empresa_id, v_empresa_factura_referencia;
            END IF;
        END IF;
    ELSIF TG_TABLE_NAME = 'linea_factura' THEN
        SELECT empresa_id INTO v_empresa_referencia FROM producto WHERE id = NEW.producto_id;

        IF NEW.exoneracion_id IS NOT NULL THEN
            SELECT ce.cliente_id, f.cliente_id INTO v_cliente_exoneracion, v_cliente_factura
            FROM cliente_exoneracion ce, factura f
            WHERE ce.id = NEW.exoneracion_id AND f.id = NEW.factura_id;

            IF v_cliente_exoneracion IS DISTINCT FROM v_cliente_factura THEN
                RAISE EXCEPTION 'La exoneración referenciada no pertenece al cliente de esta factura';
            END IF;
        END IF;
    END IF;

    IF v_empresa_referencia IS DISTINCT FROM NEW.empresa_id THEN
        RAISE EXCEPTION 'Referencia cruzada entre tenants no permitida (empresa % intentando usar recurso de empresa %)',
            NEW.empresa_id, v_empresa_referencia;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- =========================================================================
-- 3. La factura de referencia debe ser Factura Electrónica, no otra NC/ND (regla 7).
-- =========================================================================
-- A diferencia del trigger de tope (bloque siguiente), esta validación SÍ puede vivir en
-- factura BEFORE INSERT OR UPDATE sin problema de orden: lo que se consulta es el tipo del
-- documento ORIGEN (NEW.factura_referencia_id), no el de la fila que se está insertando. El
-- origen tuvo que llegar a estado ACEPTADO (regla 1) en una transacción previa ya committeada
-- antes de que exista una NC/ND que lo referencie, así que su comprobante_electronico siempre
-- existe para cuando este trigger corre. El catálogo TipoDocumentoIR permite técnicamente que
-- una NC/ND referencie otra NC/ND, pero se restringe deliberadamente por alcance (regla 7): la
-- FK es autorreferencial y ya soporta cadenas sin cambio de esquema, así que esto es una
-- restricción de validación reversible, no una limitación del modelo de datos.
CREATE OR REPLACE FUNCTION fn_validar_referencia_es_factura_electronica()
RETURNS TRIGGER AS $$
DECLARE
    v_tipo_comprobante_origen VARCHAR(2);
BEGIN
    IF NEW.factura_referencia_id IS NOT NULL THEN
        SELECT tipo_comprobante INTO v_tipo_comprobante_origen
        FROM comprobante_electronico WHERE factura_id = NEW.factura_referencia_id;

        IF v_tipo_comprobante_origen IS DISTINCT FROM '01' THEN
            RAISE EXCEPTION 'La factura de referencia debe ser una Factura Electrónica (tipo 01), no otra Nota de Crédito/Débito (tipo %)',
                v_tipo_comprobante_origen;
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_validar_referencia_es_factura_electronica
    BEFORE INSERT OR UPDATE ON factura
    FOR EACH ROW EXECUTE FUNCTION fn_validar_referencia_es_factura_electronica();

-- =========================================================================
-- 4. Tope de monto de NC (regla 3): la suma de NC previas emitidas contra la misma factura
--    origen, más la NC que se está insertando, no puede exceder el total de la factura origen.
--    No aplica a ND -- agrega monto (cargo olvidado, interés), no lo resta.
-- =========================================================================
-- Engancha en comprobante_electronico, NO en factura, aunque el dato que se está topando
-- (factura.total) vive en factura: tipo_comprobante existe únicamente en
-- comprobante_electronico (relación 1:1 con factura vía factura_id UNIQUE, ver V4), así que un
-- trigger BEFORE INSERT en factura no puede saber todavía si la fila que se está insertando es
-- una NC. En FacturaService.crear() (ver su javadoc), la fila factura se persiste con
-- saveAndFlush() ANTES que su comprobante_electronico, en la misma transacción @Transactional --
-- por eso, para cuando este trigger corre, la fila factura de NEW.factura_id ya existe y se
-- puede leer vía join sin denormalizar tipo_comprobante en factura.
--
-- El WHEN de la cláusula del trigger filtra tipo_comprobante = '03' antes de que este cuerpo
-- corra, así que Nota de Débito ('02') nunca lo ejecuta.
CREATE OR REPLACE FUNCTION fn_validar_tope_nota_credito()
RETURNS TRIGGER AS $$
DECLARE
    v_factura_origen_id UUID;
    v_total_nc_actual NUMERIC(14,5);
    v_total_origen NUMERIC(14,5);
    v_suma_nc_previas NUMERIC(14,5);
BEGIN
    SELECT factura_referencia_id, total INTO v_factura_origen_id, v_total_nc_actual
    FROM factura WHERE id = NEW.factura_id;

    -- Sin factura_referencia_id no hay factura origen contra la cual topar -- la capa de
    -- servicio (Fase B) es responsable de exigir esa referencia antes de llegar aquí; el motor
    -- no la fuerza porque factura_referencia_id es nullable también para Factura Electrónica.
    IF v_factura_origen_id IS NULL THEN
        RETURN NEW;
    END IF;

    SELECT total INTO v_total_origen FROM factura WHERE id = v_factura_origen_id;

    SELECT COALESCE(SUM(f.total), 0) INTO v_suma_nc_previas
    FROM comprobante_electronico ce
    JOIN factura f ON f.id = ce.factura_id
    WHERE ce.tipo_comprobante = '03'
      AND f.factura_referencia_id = v_factura_origen_id;

    IF (v_suma_nc_previas + v_total_nc_actual) > v_total_origen THEN
        RAISE EXCEPTION 'El monto de las Notas de Crédito (% previas + % actual) excede el total de la factura origen (%)',
            v_suma_nc_previas, v_total_nc_actual, v_total_origen;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_validar_tope_nota_credito
    BEFORE INSERT ON comprobante_electronico
    FOR EACH ROW WHEN (NEW.tipo_comprobante = '03')
    EXECUTE FUNCTION fn_validar_tope_nota_credito();

-- Nota: la regla 5 (motivo obligatorio si codigo='99') ya está cubierta desde V11 -- el campo
-- que Hacienda exige condicionalmente ahí es codigo_referencia_otro, no razon (verificado contra
-- la documentación de CodigoReferenciaOTRO/Razon en FacturaElectronica_V4.4.xsd /
-- NotaCreditoElectronica_V4.4.xsd / NotaDebitoElectronica_V4.4.xsd: solo CodigoReferenciaOTRO
-- dice "Será obligatorio en caso de utilizar el código 99"; Razon es libre siempre, en los tres
-- tipos de documento). No hace falta un CHECK nuevo acá.
