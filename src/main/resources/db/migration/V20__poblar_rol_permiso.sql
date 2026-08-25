-- rol_permiso (V3__permisos_y_roles.sql) nunca tuvo un solo INSERT en ningún punto del historial
-- de migraciones. permisos_efectivos (V3) arma perfil.permisos uniendo EXCLUSIVAMENTE
-- rol_permiso + overrides de usuario_permiso -- sin filas acá, todo usuario_empresa resuelve
-- permisos: [] sin importar su rol, para los 4 roles por igual. Este es el fix: la matriz completa
-- rol -> permiso, no un parche puntual sobre el primer permiso que se encontró roto en el frontend
-- (factura.reenviar).
--
-- No se toca V1__esquema_base.sql (catálogo de rol/permiso): Flyway ya la aplicó, y una migración
-- ya corrida nunca se vuelve a ejecutar -- cualquier cambio ahí exigiría un baseline/repair manual
-- contra cada entorno.
--
-- Criterio de asignación (documentado acá porque es una decisión de negocio/seguridad, no un
-- detalle de implementación):
--   - SUPER_ADMIN: catálogo completo. Hoy es inerte para permisos_efectivos (esa vista arranca
--     desde usuario_empresa, y SUPER_ADMIN "no vive en usuario_empresa" per su descripción en
--     V1) -- se puebla igual por completitud del modelo de datos y para cuando exista un camino de
--     autorización cross-tenant que sí lo consulte.
--   - ADMIN_EMPRESA: catálogo completo. "Control total del tenant" (V1) incluye los 6 permisos
--     críticos (gestionar_certificado, gestionar_ambiente_hacienda, usuario.editar_rol,
--     usuario.suspender, permiso.personalizar, factura.anular) -- coherente con
--     fn_bloquear_autoescalamiento (V3), que solo bloquea otorgar un crítico por EXCEPCIÓN
--     puntual vía usuario_permiso, nunca por asignación de rol vía rol_permiso.
--   - EMPLEADO_FACTURACION: exactamente los 3 dominios de su descripción en V1 ("Crear/enviar
--     facturas, gestionar productos y clientes") -- producto.*, cliente.*, factura.crear/ver/
--     reenviar. Deliberadamente SIN factura.anular (crítico: reversión fiscal irreversible, queda
--     en ADMIN_EMPRESA) ni reporte.ver (el catálogo se lo asigna explícitamente a CONSULTA) ni
--     nada de empresa.*/usuario.* (fuera de sus 3 dominios).
--   - CONSULTA: exactamente "solo lectura sobre facturas y reportes" (V1, caso de uso: contador
--     externo) -- factura.ver + reporte.ver, nada más.
INSERT INTO rol_permiso (rol_id, permiso_id)
SELECT r.id, p.id
FROM (VALUES
    -- SUPER_ADMIN: catálogo completo (21 permisos)
    ('SUPER_ADMIN', 'empresa.ver_configuracion'),
    ('SUPER_ADMIN', 'empresa.editar_configuracion'),
    ('SUPER_ADMIN', 'empresa.gestionar_certificado'),
    ('SUPER_ADMIN', 'empresa.gestionar_ambiente_hacienda'),
    ('SUPER_ADMIN', 'usuario.invitar'),
    ('SUPER_ADMIN', 'usuario.ver'),
    ('SUPER_ADMIN', 'usuario.editar_rol'),
    ('SUPER_ADMIN', 'usuario.suspender'),
    ('SUPER_ADMIN', 'permiso.personalizar'),
    ('SUPER_ADMIN', 'producto.crear'),
    ('SUPER_ADMIN', 'producto.editar'),
    ('SUPER_ADMIN', 'producto.ver'),
    ('SUPER_ADMIN', 'producto.desactivar'),
    ('SUPER_ADMIN', 'cliente.crear'),
    ('SUPER_ADMIN', 'cliente.editar'),
    ('SUPER_ADMIN', 'cliente.ver'),
    ('SUPER_ADMIN', 'factura.crear'),
    ('SUPER_ADMIN', 'factura.ver'),
    ('SUPER_ADMIN', 'factura.anular'),
    ('SUPER_ADMIN', 'factura.reenviar'),
    ('SUPER_ADMIN', 'reporte.ver'),

    -- ADMIN_EMPRESA: catálogo completo (21 permisos)
    ('ADMIN_EMPRESA', 'empresa.ver_configuracion'),
    ('ADMIN_EMPRESA', 'empresa.editar_configuracion'),
    ('ADMIN_EMPRESA', 'empresa.gestionar_certificado'),
    ('ADMIN_EMPRESA', 'empresa.gestionar_ambiente_hacienda'),
    ('ADMIN_EMPRESA', 'usuario.invitar'),
    ('ADMIN_EMPRESA', 'usuario.ver'),
    ('ADMIN_EMPRESA', 'usuario.editar_rol'),
    ('ADMIN_EMPRESA', 'usuario.suspender'),
    ('ADMIN_EMPRESA', 'permiso.personalizar'),
    ('ADMIN_EMPRESA', 'producto.crear'),
    ('ADMIN_EMPRESA', 'producto.editar'),
    ('ADMIN_EMPRESA', 'producto.ver'),
    ('ADMIN_EMPRESA', 'producto.desactivar'),
    ('ADMIN_EMPRESA', 'cliente.crear'),
    ('ADMIN_EMPRESA', 'cliente.editar'),
    ('ADMIN_EMPRESA', 'cliente.ver'),
    ('ADMIN_EMPRESA', 'factura.crear'),
    ('ADMIN_EMPRESA', 'factura.ver'),
    ('ADMIN_EMPRESA', 'factura.anular'),
    ('ADMIN_EMPRESA', 'factura.reenviar'),
    ('ADMIN_EMPRESA', 'reporte.ver'),

    -- EMPLEADO_FACTURACION: producto.*, cliente.*, factura.crear/ver/reenviar (10 permisos)
    ('EMPLEADO_FACTURACION', 'producto.crear'),
    ('EMPLEADO_FACTURACION', 'producto.editar'),
    ('EMPLEADO_FACTURACION', 'producto.ver'),
    ('EMPLEADO_FACTURACION', 'producto.desactivar'),
    ('EMPLEADO_FACTURACION', 'cliente.crear'),
    ('EMPLEADO_FACTURACION', 'cliente.editar'),
    ('EMPLEADO_FACTURACION', 'cliente.ver'),
    ('EMPLEADO_FACTURACION', 'factura.crear'),
    ('EMPLEADO_FACTURACION', 'factura.ver'),
    ('EMPLEADO_FACTURACION', 'factura.reenviar'),

    -- CONSULTA: solo lectura sobre facturas y reportes (2 permisos)
    ('CONSULTA', 'factura.ver'),
    ('CONSULTA', 'reporte.ver')
) AS matriz(rol_codigo, permiso_codigo)
JOIN rol r ON r.codigo = matriz.rol_codigo
JOIN permiso p ON p.codigo = matriz.permiso_codigo;
