-- Fase C, Release 3: registro de cobros de ventas a plazo. factura registra condicion_venta y
-- plazo_credito, pero nada registra que el dinero entro -- ver docs/plan-fases-release-3.md,
-- Fase C. El saldo NO se persiste: se deriva en factura_estado_cobro (mismo principio de "no
-- almacenar lo que ya se puede derivar" del saldo de NC en Release 2).

-- =========================================================================
-- 1. cobro_factura -- append-only. Sin UPDATE ni DELETE en esta rebanada.
-- =========================================================================
CREATE TABLE cobro_factura (
    id              UUID PRIMARY KEY DEFAULT uuidv7(),
    empresa_id      UUID NOT NULL REFERENCES empresa(id),  -- @TenantId
    factura_id      UUID NOT NULL REFERENCES factura(id),
    monto_cobrado   NUMERIC(14,5) NOT NULL CHECK (monto_cobrado > 0),
    fecha_cobro     TIMESTAMP NOT NULL DEFAULT now(),
    medio_pago      VARCHAR(2) NOT NULL,
    referencia      VARCHAR(100),
    registrado_por  UUID NOT NULL REFERENCES usuario(id),
    create_date     TIMESTAMP NOT NULL DEFAULT now()
);

-- Sin CHECK de codigo sobre medio_pago, igual que factura_medio_pago.tipo_medio_pago (V11:128):
-- el catalogo de Hacienda cambia y un CHECK exigiria una migracion por cada revision. La
-- validacion vive en TipoMedioPago.fromCodigo, en la frontera del servicio (D2).
-- '99 Otros' no lleva columna propia: el detalle va en referencia (D2, resuelto).

-- Ruta caliente: el trigger de tope, el historial y la vista agregan los tres por factura_id.
CREATE INDEX ix_cobro_factura_factura ON cobro_factura (factura_id);

-- V18 agrego factura.factura_referencia_id sin indice. El neteo de NC (D5) lo consulta en CADA
-- insercion de cobro y en cada fila de la vista; sin indice eso es un seq scan de factura.
CREATE INDEX ix_factura_referencia ON factura (factura_referencia_id)
    WHERE factura_referencia_id IS NOT NULL;

-- =========================================================================
-- 2. fn_validar_mismo_tenant: cuerpo COMPLETO de V19 + rama cobro_factura.
-- =========================================================================
-- CREATE OR REPLACE reemplaza el cuerpo entero, no aplica un diff: el texto base DEBE ser el de
-- V19 (guard v_omitir_chequeo_cliente para Tiquete sin receptor), NO el de V18. Partir de V18
-- revertiria en silencio ese fix y romperia
-- AislamientoMultiTenantTest#facturaConClienteIdNuloNoDisparaElTriggerDeAislamientoTenant.
-- Los triggers existentes (trg_validar_tenant_factura, trg_validar_tenant_linea_factura,
-- V4:130-136) referencian la funcion por nombre y no se recrean.
--
-- La rama nueva solo accede a NEW.factura_id, que existe en cobro_factura -- respeta la nota de
-- implementacion de V19:39-46 sobre no tocar campos de NEW fuera de la rama de su tabla.
CREATE OR REPLACE FUNCTION fn_validar_mismo_tenant()
RETURNS TRIGGER AS $$
DECLARE
    v_empresa_referencia UUID;
    v_empresa_factura_referencia UUID;
    v_cliente_exoneracion UUID;
    v_cliente_factura UUID;
    v_omitir_chequeo_cliente BOOLEAN := FALSE;
BEGIN
    IF TG_TABLE_NAME = 'factura' THEN
        IF NEW.cliente_id IS NOT NULL THEN
            SELECT empresa_id INTO v_empresa_referencia FROM cliente WHERE id = NEW.cliente_id;
        ELSE
            -- Tiquete sin receptor identificado (regla de negocio Fase C) -- sin cliente_id no
            -- hay nada que validar contra empresa_id, y NO debe caer en el chequeo final
            -- generico (NULL IS DISTINCT FROM <uuid real> evaluaria verdadero por error).
            v_omitir_chequeo_cliente := TRUE;
        END IF;

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
    ELSIF TG_TABLE_NAME = 'cobro_factura' THEN
        -- Rama nueva (Release 3 / Fase C): la unica FK cruzable de cobro_factura es factura_id.
        -- registrado_por apunta a usuario, tabla GLOBAL sin empresa_id -- nada que cruzar.
        SELECT empresa_id INTO v_empresa_referencia FROM factura WHERE id = NEW.factura_id;
    END IF;

    IF v_omitir_chequeo_cliente THEN
        RETURN NEW;
    END IF;

    IF v_empresa_referencia IS DISTINCT FROM NEW.empresa_id THEN
        RAISE EXCEPTION 'Referencia cruzada entre tenants no permitida (empresa % intentando usar recurso de empresa %)',
            NEW.empresa_id, v_empresa_referencia;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_validar_tenant_cobro_factura
    BEFORE INSERT OR UPDATE ON cobro_factura
    FOR EACH ROW EXECUTE FUNCTION fn_validar_mismo_tenant();

-- =========================================================================
-- 3. Restriccion de alcance: solo credito (02), consignacion (03) y apartado (04).
-- =========================================================================
-- Lista de PERMITIDOS, no de excluidos: el catalogo CondicionVenta v4.4 tiene 14 codigos
-- (01,02,03,04,05,06,07,08,10,12,13,14,15,99) y crece; una lista negra quedaria incompleta el
-- dia que Hacienda agregue uno. Ver docs/plan-fases-release-3.md, Fase C, para el porque de
-- cada exclusion.
--
-- El guard IS NULL es obligatorio: "NULL NOT IN (...)" evalua NULL, no TRUE, asi que sin el una
-- factura inexistente pasaria el trigger. Misma trampa de tres valores que V19 corrigio.
CREATE OR REPLACE FUNCTION fn_validar_alcance_cobro_factura()
RETURNS TRIGGER AS $$
DECLARE
    v_condicion_venta VARCHAR(2);
BEGIN
    SELECT condicion_venta INTO v_condicion_venta FROM factura WHERE id = NEW.factura_id;

    IF v_condicion_venta IS NULL OR v_condicion_venta NOT IN ('02', '03', '04') THEN
        RAISE EXCEPTION 'La factura % tiene condicion_venta % -- el registro de cobros solo aplica a credito (02), consignacion (03) y apartado (04)',
            NEW.factura_id, COALESCE(v_condicion_venta, 'inexistente');
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_validar_alcance_cobro_factura
    BEFORE INSERT ON cobro_factura
    FOR EACH ROW EXECUTE FUNCTION fn_validar_alcance_cobro_factura();

-- =========================================================================
-- 4. Tope de sobre-cobro, neteado contra Notas de Credito ACEPTADAS (D5).
-- =========================================================================
-- A diferencia de fn_validar_tope_nota_credito (V18), que tuvo que engancharse en
-- comprobante_electronico porque tipo_comprobante solo vive ahi, este trigger va DIRECTO sobre
-- cobro_factura: cada fila es inequivoca en el momento en que se inserta.
--
-- ce.tipo_comprobante = '03' es OBLIGATORIO en el neteo: factura_referencia_id se puebla para NC
-- ('03') Y para ND ('02') (ver V18:11-12). Sin ese filtro, una Nota de DEBITO aceptada restaria
-- del saldo cobrable, que es exactamente al reves de lo que una ND significa.
--
-- Comparacion con > estricto, no >= : un cobro que lleva el acumulado exactamente al saldo neto
-- debe permitirse (si no, pagar una factura completa seria imposible). Mismo operador y mismo
-- criterio que V18:141, pinneado alli por topeNotaCreditoPermiteSumaExactamenteIgualAlTotal.
-- Ambos operandos son NUMERIC(14,5): decimal exacto, la igualdad es alcanzable de verdad.
--
-- Orden de disparo: Postgres ejecuta los triggers BEFORE ROW en orden alfabetico de nombre, asi
-- que alcance -> tenant -> tope. La regla de alcance gana siempre, incluso cuando el tope pasaria.
CREATE OR REPLACE FUNCTION fn_validar_tope_cobro_factura()
RETURNS TRIGGER AS $$
DECLARE
    v_total_factura  NUMERIC(14,5);
    v_total_nc       NUMERIC(14,5);
    v_saldo_neto     NUMERIC(14,5);
    v_cobros_previos NUMERIC(14,5);
BEGIN
    SELECT total INTO v_total_factura FROM factura WHERE id = NEW.factura_id;

    SELECT COALESCE(SUM(nc.total), 0) INTO v_total_nc
    FROM factura nc
    JOIN comprobante_electronico ce ON ce.factura_id = nc.id
    WHERE nc.factura_referencia_id = NEW.factura_id
      AND ce.tipo_comprobante = '03'
      AND ce.estado = 'ACEPTADO';

    v_saldo_neto := v_total_factura - v_total_nc;

    SELECT COALESCE(SUM(c.monto_cobrado), 0) INTO v_cobros_previos
    FROM cobro_factura c
    WHERE c.factura_id = NEW.factura_id;

    IF (v_cobros_previos + NEW.monto_cobrado) > v_saldo_neto THEN
        RAISE EXCEPTION 'El monto cobrado (% previos + % actual) excede el saldo neto de la factura % (total % - notas de credito aceptadas % = %)',
            v_cobros_previos, NEW.monto_cobrado, NEW.factura_id,
            v_total_factura, v_total_nc, v_saldo_neto;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_validar_tope_cobro_factura
    BEFORE INSERT ON cobro_factura
    FOR EACH ROW EXECUTE FUNCTION fn_validar_tope_cobro_factura();

-- =========================================================================
-- 5. Vista derivada. Sin columna de saldo persistida.
-- =========================================================================
-- Dos LATERAL en vez del LEFT JOIN + GROUP BY del plan: con DOS relaciones uno-a-muchos
-- (cobros Y notas de credito) en un solo GROUP BY, el producto cartesiano duplica filas y ambos
-- SUM cuentan de mas. Cada LATERAL agrega por separado y devuelve siempre exactamente una fila
-- (COALESCE(SUM(...), 0)), asi que CROSS JOIN no pierde facturas sin cobros ni sin NC.
--
-- Los CAST explicitos fijan los tipos de las columnas calculadas para que
-- hibernate ddl-auto=validate compare contra tipos deterministas (un CASE de literales produce
-- text, no varchar).
CREATE VIEW factura_estado_cobro AS
SELECT base.factura_id,
       base.empresa_id,
       base.total,
       base.total_nota_credito,
       base.total_neto,
       base.total_cobrado,
       CAST(base.total_neto - base.total_cobrado AS NUMERIC(14,5)) AS saldo_pendiente,
       CAST(
           CASE
               -- Factura anulada por completo con NC: no se debe nada. Sin esta rama la vista
               -- reportaria PENDIENTE para siempre mientras el trigger de tope rechaza cualquier
               -- cobro (0 + monto > 0), un estado irresoluble.
               WHEN base.total_neto <= 0 THEN 'COBRADO'
               WHEN base.total_cobrado = 0 THEN 'PENDIENTE'
               WHEN base.total_cobrado < base.total_neto THEN 'PARCIAL'
               ELSE 'COBRADO'
           END AS VARCHAR(20)) AS estado_cobro
FROM (
    SELECT f.id                                        AS factura_id,
           f.empresa_id                                AS empresa_id,
           f.total                                     AS total,
           nc.total_nc                                 AS total_nota_credito,
           CAST(f.total - nc.total_nc AS NUMERIC(14,5)) AS total_neto,
           c.total_cobrado                             AS total_cobrado
    FROM factura f
    CROSS JOIN LATERAL (
        SELECT CAST(COALESCE(SUM(nc2.total), 0) AS NUMERIC(14,5)) AS total_nc
        FROM factura nc2
        JOIN comprobante_electronico ce ON ce.factura_id = nc2.id
        WHERE nc2.factura_referencia_id = f.id
          AND ce.tipo_comprobante = '03'
          AND ce.estado = 'ACEPTADO'
    ) nc
    CROSS JOIN LATERAL (
        SELECT CAST(COALESCE(SUM(cf.monto_cobrado), 0) AS NUMERIC(14,5)) AS total_cobrado
        FROM cobro_factura cf
        WHERE cf.factura_id = f.id
    ) c
    WHERE f.condicion_venta IN ('02', '03', '04')
) base;
