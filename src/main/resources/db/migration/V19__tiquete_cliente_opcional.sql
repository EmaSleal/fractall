-- Fase C, Release 2 (Tiquete Electrónico): un Tiquete puede emitirse sin receptor identificado
-- (venta de mostrador) -- el XSD de Hacienda ya modela esto (Receptor minOccurs="0" en los 4
-- tipos de comprobante v4.4), y TipoComprobantePerfil.TIQUETE (Fase B) ya tiene
-- receptorObligatorio=false. Pero factura.cliente_id era NOT NULL, bloqueando el caso de uso
-- principal de Tiquete. Se descarta deliberadamente un cliente sintético "Consumidor Final": la
-- UI muestra esa etiqueta como presentación cuando cliente == null, sin persistir una fila falsa
-- (evita protección contra edición/borrado, exclusión del buscador de clientes, auto-creación por
-- empresa). Ver docs/plan-fases-release-2.md, sección "Fase C".

-- =========================================================================
-- 1. factura.cliente_id pasa de NOT NULL a nullable.
-- =========================================================================
ALTER TABLE factura
    ALTER COLUMN cliente_id DROP NOT NULL;

-- =========================================================================
-- 2. Riesgo crítico corregido en el mismo cambio: fn_validar_mismo_tenant() (V4, extendida en
--    V10 y V18) hacía, para la rama 'factura':
--
--        SELECT empresa_id INTO v_empresa_referencia FROM cliente WHERE id = NEW.cliente_id;
--
--    Con NEW.cliente_id NULL, "id = NULL" nunca es verdadero en SQL -- el SELECT no matchea
--    ninguna fila, v_empresa_referencia queda NULL, y la comparación final
--    "v_empresa_referencia IS DISTINCT FROM NEW.empresa_id" evalúa VERDADERO (NULL is distinct
--    from cualquier UUID real) -- el trigger rechazaría TODOS los Tiquetes sin cliente con un
--    error de aislamiento multi-tenant que no delata la causa real. Confirmado exactamente así
--    por AislamientoMultiTenantTest#facturaConClienteIdNuloNoDisparaElTriggerDeAislamientoTenant
--    ANTES de este fix (el trigger BEFORE INSERT dispara antes de que el motor evalúe el propio
--    NOT NULL de la columna, así que el error observado era del trigger, no de la constraint).
--
--    Fix: guard explícito AND NEW.cliente_id IS NOT NULL antes de resolver v_empresa_referencia
--    para la rama 'factura'. El resto de la función -- incluida la validación de
--    factura_referencia_id (regla 4/7, V18) -- queda igual: Tiquete tampoco usa esa FK, así que
--    ese bloque simplemente no se activa (NEW.factura_referencia_id también es NULL para Tiquete).
--    CREATE OR REPLACE es seguro: los triggers existentes (trg_validar_tenant_factura,
--    trg_validar_tenant_linea_factura) referencian esta función por nombre y no necesitan
--    recrearse.
-- =========================================================================
-- Nota de implementación: NEW es un RECORD genérico compartido por las dos tablas que usan esta
-- función (factura, linea_factura, ver los dos triggers que la referencian). Acceder a
-- NEW.cliente_id en una expresión evaluada también para invocaciones sobre linea_factura revienta
-- en tiempo de ejecución ("record new has no field cliente_id") aunque esa rama nunca debería
-- "lógicamente" alcanzarse -- el AND de PL/pgSQL no da la garantía de cortocircuito que evitaría
-- ese acceso. Por eso el guard de Tiquete se resuelve en una variable booleana DENTRO del bloque
-- IF TG_TABLE_NAME = 'factura', nunca en una expresión que mezcle TG_TABLE_NAME con NEW.cliente_id
-- fuera de ese bloque.
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
            -- genérico (NULL IS DISTINCT FROM <uuid real> evaluaría verdadero por error).
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
