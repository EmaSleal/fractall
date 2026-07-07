-- Fase 7, sección 4.15.2 y 4.16: consumo de cliente_exoneracion (Fase 6) dentro de la creación
-- de linea_factura. Los 3 campos de exoneración quedaron deliberadamente fuera de
-- V8__cliente_exoneracion.sql (ver su comentario de cabecera) hasta que existiera un consumidor
-- real -- FacturaService, en esta fase.
ALTER TABLE linea_factura
    ADD COLUMN exoneracion_id UUID REFERENCES cliente_exoneracion(id),
    ADD COLUMN porcentaje_exoneracion_aplicado NUMERIC(5,2),
    ADD COLUMN monto_exoneracion_aplicado NUMERIC(14,5),
    ADD CONSTRAINT chk_exoneracion_todo_o_nada CHECK (
        (exoneracion_id IS NULL AND porcentaje_exoneracion_aplicado IS NULL AND monto_exoneracion_aplicado IS NULL)
        OR
        (exoneracion_id IS NOT NULL AND porcentaje_exoneracion_aplicado IS NOT NULL AND monto_exoneracion_aplicado IS NOT NULL)
    );

-- Nota de defensa en profundidad (ver javadoc de FacturaService): un RAISE EXCEPTION de estos
-- triggers NO se traduce a DataIntegrityViolationException (SQLSTATE de clase distinta), así que
-- GlobalExceptionHandler NO lo captura como 409 limpio -- escalaría como error crudo. Por eso
-- FacturaService hace estas mismas validaciones explícitamente en Java ANTES de intentar
-- persistir; estos triggers son la última línea de defensa a nivel de motor, no el mecanismo
-- primario de reporte de error de dominio.
CREATE OR REPLACE FUNCTION fn_validar_exoneracion_vigente()
RETURNS TRIGGER AS $$
DECLARE
    v_vencimiento TIMESTAMP;
    v_activo BOOLEAN;
    v_tipo_documento VARCHAR(2);
BEGIN
    IF NEW.exoneracion_id IS NOT NULL THEN
        SELECT fecha_vencimiento, activo, tipo_documento
        INTO v_vencimiento, v_activo, v_tipo_documento
        FROM cliente_exoneracion WHERE id = NEW.exoneracion_id;

        IF NOT v_activo THEN
            RAISE EXCEPTION 'La exoneración referenciada está inactiva';
        END IF;

        IF v_vencimiento IS NOT NULL AND v_vencimiento < now() THEN
            RAISE EXCEPTION 'La exoneración referenciada venció el %', v_vencimiento;
        END IF;

        IF v_tipo_documento IN ('01', '05', '06', '07') THEN
            RAISE EXCEPTION 'El tipo de exoneración % es exclusivo de Nota de Crédito/Débito, no aplica a Factura Electrónica', v_tipo_documento;
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_validar_exoneracion_vigente
    BEFORE INSERT OR UPDATE ON linea_factura
    FOR EACH ROW EXECUTE FUNCTION fn_validar_exoneracion_vigente();

-- Reemplaza el fn_validar_mismo_tenant de V4 con la versión extendida que también cruza la
-- exoneración de la línea contra el cliente de la factura. CREATE OR REPLACE es seguro: los
-- triggers ya existentes (trg_validar_tenant_factura, trg_validar_tenant_linea_factura, ambos de
-- V4) referencian esta función por nombre y no necesitan recrearse.
CREATE OR REPLACE FUNCTION fn_validar_mismo_tenant()
RETURNS TRIGGER AS $$
DECLARE
    v_empresa_referencia UUID;
    v_cliente_exoneracion UUID;
    v_cliente_factura UUID;
BEGIN
    IF TG_TABLE_NAME = 'factura' THEN
        SELECT empresa_id INTO v_empresa_referencia FROM cliente WHERE id = NEW.cliente_id;
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
