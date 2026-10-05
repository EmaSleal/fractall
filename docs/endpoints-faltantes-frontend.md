# Endpoints Faltantes — API FRACTALL (para habilitar Frontend)

> **Nota de restauración (2026-10-05):** este archivo no existía en el repositorio pese a estar citado por `arquitectura-facturacion-electronica-cr.md` y por los tres `plan-fases-release-*.md`. Se restaura desde la copia del usuario como **registro histórico**. Las decisiones vigentes derivadas están en la memoria del proyecto (`memoria-proyectos`): **ADR-050** (contrato de sesión, listados y entrega de documentos) y **ADR-049** (política de reintentos automáticos).
>
> **Divergencias conocidas entre este documento y el código actual — no asumir que el texto de abajo describe la implementación:**
> - **§5.1 Reenvío — CONFLICTO ABIERTO (pendiente P-001).** El código (`ComprobanteEmisionService.ESTADOS_REENVIABLES`) acepta `FIRMADO`/`RECHAZADO`/`ERROR`, no toma `SELECT ... FOR UPDATE` y **resetea** `intentos_envio` a 0 en lugar de incrementarlo. Contradice la decisión de negocio registrada abajo ("un rechazo funcional no se reenvía").
> - **§4 Descargas (pendiente P-012):** se entregan vía backend con desencriptado (cumple "sin presigned URL"), pero devuelven `byte[]`/`String` completo en memoria, no `StreamingResponseBody`. El PDF se genera al vuelo y no se persiste (`pdf_referencia` permanece null).
> - **§4.3 / §9 Endpoint de diagnóstico:** resuelto — `GET /facturas/diagnostico/...` ya no existe en `FacturaController`; `GET /facturas/{id}/xml/respuesta` es la ruta de producto.
> - **§6 Migración:** implementada como `V12__agregar_seguimiento_consulta_hacienda.sql`, ampliada en `V21` con `ERROR_CONFIGURACION`. La distinción comunicación vs. estado pendiente quedó resuelta en Release 3, Fase A.

> Documento complementario a `arquitectura-facturacion-electronica-cr.md` y `plan-fases-release-1.md`. Registra el análisis de brechas del API expuesto en `/v3/api-docs` frente a los requisitos de un frontend funcional, y las decisiones de diseño acordadas para cerrarlas. Referencias de sección (ej. "sección 4.9") apuntan al documento de arquitectura.

> **Estado: RESUELTO.** Los 15 endpoints listados en la sección 7 fueron verificados ítem por ítem contra la implementación real del frontend — todos están construidos. Este documento se conserva como registro histórico de las decisiones de diseño (envelope de respuesta, orden de construcción, migración de la sección 6), no como lista de trabajo pendiente.
>
> **Nota:** durante la construcción del frontend se agregaron endpoints adicionales no anticipados en este análisis original, por necesidades de diseño surgidas en el camino. Esos endpoints están documentados en el spec vivo (`/v3/api-docs`), no en este documento — no asumir que esta lista de 15 es exhaustiva del contrato actual entre frontend y backend.

---

## 0. Contexto del análisis

El API actual (verificado contra `https://frac-stag.astro-solutions.net/v3/api-docs`) cubre creación y edición en todos los dominios de negocio, pero **no expone un solo endpoint de listado** (productos, clientes, facturas), ni lectura de estado de factura, ni descarga de entregables (PDF/XML). Sin estos, un frontend puede crear registros a ciegas pero no puede mostrarlos, buscarlos, ni servir el criterio de aceptación de Release 1 (sección 8.3: "descarga el PDF junto con ambos XML").

---

## 1. Sesión y perfil

### 1.1 `GET /auth/mis-empresas`

**Propósito:** lista las empresas del usuario autenticado, con su rol en cada una.

**Por qué es bloqueante:** el flujo de selección de tenant (sección 3.2) emite un token de alcance mínimo y espera un `empresaId` en `/auth/seleccionar-tenant` — pero ningún endpoint existente devuelve la lista de empresas disponibles antes de ese paso. Sirve tanto la pantalla de selección inicial (login con 2+ empresas) como la de "cambiar de tenant en caliente".

**Response:**
```json
[
  { "empresaId": "uuid", "razonSocial": "string", "nombreComercial": "string", "rolCodigo": "ADMIN_EMPRESA", "status": "HABILITADA" }
]
```

### 1.2 `GET /auth/perfil`

**Propósito:** datos del usuario autenticado, empresa activa y permisos efectivos, para hidratar el estado del frontend tras un refresh de página.

**Decisión confirmada:** el endpoint expone datos del usuario (`nombre`, `email`, `mfaHabilitado`), no solo claims de autorización.

**Response:**
```json
{
  "usuarioId": "uuid",
  "nombre": "string",
  "email": "string",
  "mfaHabilitado": true,
  "empresaActual": { "empresaId": "uuid", "razonSocial": "string", "rolCodigo": "ADMIN_EMPRESA" },
  "permisos": ["factura.crear", "factura.reenviar", "..."]
}
```

`permisos` se lee de la vista `permisos_efectivos` (sección 4.8), la misma que ya se congela en el JWT al emitirlo — este endpoint la expone en formato cómodo, no la recalcula con lógica distinta. La fuente de verdad para autorización real en cada request protegido sigue siendo la validación de firma del JWT en el backend, nunca este endpoint ni el estado del frontend.

### 1.3 `POST /auth/logout`

**Propósito:** revoca (`revocado=true`, `revocado_en=now()`) el refresh token de la sesión actual.

**Nota de diseño:** parámetro opcional `todasLasSesiones: boolean` para revocar todos los refresh tokens del usuario ante sospecha de robo de sesión.

### 1.4 `POST /auth/recuperar-password`

**Propósito:** genera token de un solo uso para restablecer contraseña.

**Request:** `{ "email": "string" }`

**Nota de diseño:** reutiliza el mismo servicio de token de un solo uso ya construido para `reenviar-verificacion` (sección 3.1) — guarda hash en `usuario_token` (`tipo='RECUPERACION_PASSWORD'`, ya contemplado en el esquema SQL de la sección 4.0 pero sin endpoint hasta ahora). Mismo límite de tasa por email/IP que ya rige la verificación de correo — no omitirlo aquí.

### 1.5 `POST /auth/restablecer-password`

**Propósito:** valida el token recibido y actualiza la contraseña.

**Request:** `{ "token": "string", "nuevaPassword": "string" }`

**Validación:** hash del token + expiración + `usado=false`, mismo patrón que `verificar-email` (sección 3.1).

**No negociable:** debe revocar todos los refresh tokens activos del usuario en la misma transacción. Un cambio de contraseña que no invalida sesiones existentes es una brecha de seguridad conocida.

---

## 2. Catálogo — productos y clientes

### 2.1 `GET /catalogo/productos?activo=&cursor=&limit=`

**Propósito:** listado paginado de productos, con filtro por estado activo/inactivo.

**Decisión de paginación:** keyset pagination sobre el UUID v7 como cursor, no `OFFSET/LIMIT`. Justificación: UUID v7 se eligió explícitamente por ser "ordenable a nivel de índice B-tree" (sección 2) — paginar con `OFFSET` ignora esa propiedad y además sufre corrimiento de página ante inserciones concurrentes.

### 2.2 `GET /catalogo/productos/{id}`

**Propósito:** detalle de un producto. Espejo de `CrearProductoRequest` + `id`, `createDate`, `updateDate`.

### 2.3 `GET /catalogo/clientes?q=&cursor=&limit=`

**Propósito:** listado/búsqueda de clientes por nombre o identificación. Necesario para la selección de cliente al armar una factura.

**Punto pendiente de medición, no asumido:** si el catálogo de un tenant crece, `ILIKE '%texto%'` sin índice de trigramas (`pg_trgm`) degrada linealmente. No descartar la necesidad solo por el volumen esperado de persona individual/pequeño negocio — medirlo.

### 2.4 `GET /catalogo/clientes/{id}`

**Propósito:** detalle de un cliente. Espejo de `CrearClienteRequest` + metadatos.

### 2.5 Envelope de respuesta — fijar antes de construir los tres listados

```json
{ "items": [ /* ProductoResponse[] | ClienteResponse[] | FacturaResponse[] */ ], "nextCursor": "uuid|null" }
```

Mismo formato para productos, clientes y facturas — no dejar que cada listado invente el suyo.

---

## 3. Facturación — lectura

### 3.1 `GET /facturas?estado=&clienteId=&desde=&hasta=&cursor=&limit=`

**Propósito:** listado paginado de facturas, con filtros de estado, cliente y rango de fecha. Mismo envelope de paginación de la sección 2.5.

### 3.2 `GET /facturas/{id}`

**Propósito:** detalle de factura, incluyendo el estado del `comprobante_electronico` asociado, no solo de `factura`.

**Response:**
```json
{
  "id": "uuid",
  "consecutivo": "string",
  "clienteNombre": "string",
  "total": 0,
  "estado": "GENERADO|FIRMADO|ENVIADO|ACEPTADO|RECHAZADO",
  "codigoRespuesta": "string",
  "mensajeRespuesta": "string",
  "ultimoResultadoConsulta": "PENDIENTE|ACEPTADO|RECHAZADO|ERROR_COMUNICACION",
  "fechaEmision": "date",
  "fechaRespuesta": "date",
  "lineas": [ /* detalle */ ]
}
```

`ultimoResultadoConsulta` (ver sección 6) le permite al frontend decidir si mostrar el botón de reenvío sin reimplementar la regla de negocio del lado del cliente.

---

## 4. Entrega de documentos

### 4.1 `GET /facturas/{id}/pdf`

**Propósito:** descarga del PDF de la factura.

**Restricción de diseño no negociable:** no puede implementarse como presigned URL directo a Oracle Object Storage. Cada documento se cifra con la KEK de Transit **antes** de subirse (sección 6.4) — un enlace directo al bucket solo entregaría el blob cifrado. El backend debe: (1) descargar el blob cifrado, (2) desencriptarlo vía Vault Transit, (3) transmitirlo en claro vía `StreamingResponseBody`, sin cargar el archivo completo en memoria.

### 4.2 `GET /facturas/{id}/xml/factura`

**Propósito:** descarga del XML del comprobante firmado. Mismo patrón de desencriptado en streaming que 4.1.

### 4.3 `GET /facturas/{id}/xml/respuesta`

**Propósito:** descarga del XML de aceptación/rechazo de Hacienda.

**Pendiente de decidir antes de construir:** ya existe `GET /facturas/diagnostico/{comprobanteId}/xml-respuesta`, con prefijo `diagnostico` que sugiere uso interno/soporte. Decidir si este endpoint nuevo lo reemplaza (promoviéndolo a ruta de producto) o si conviven — uno de soporte con detalle interno, uno de producto con la respuesta limpia. No construir ambos sin resolver esto: duplicaría lógica de desencriptado en dos controladores.

---

## 5. Reenvío ante rechazo de comunicación

### 5.1 `POST /facturas/{id}/reenviar`

**Propósito:** reintenta la transmisión de un comprobante ante una falla de **comunicación**, no ante un rechazo funcional de Hacienda.

**Decisión de negocio confirmada:** un rechazo funcional (`ind-estado: 'rechazado'` con `200 OK` de Hacienda) es un veredicto definitivo sobre el contenido del comprobante — reenviar el mismo XML firmado produce, con altísima probabilidad, el mismo rechazo. Este endpoint existe únicamente para el escenario de error de comunicación (timeout, `5xx`, falla al renovar el token OAuth2) donde Hacienda nunca emitió un veredicto.

**Precondición (validada en backend, no en frontend):**
```sql
estado = 'ENVIADO'
AND ultimo_resultado_consulta = 'ERROR_COMUNICACION'
AND intentos_consulta >= 1
```
Cualquier otro estado → `409 Conflict` con mensaje explícito.

**Reglas de implementación no negociables:**
- No genera nuevo consecutivo. Reutiliza `clave_numerica`/`consecutivo` existentes — el bloqueo pesimista de la sección 4.9 no se vuelve a ejecutar.
- Bloqueo pesimista de fila (`SELECT ... FOR UPDATE`) sobre `comprobante_electronico` durante la operación, para no chocar con una corrida concurrente del job de consulta de estado.
- Incrementa `intentos_envio` en la misma transacción que actualiza `estado` a `ENVIADO`.

**Response:**
```json
{ "facturaId": "uuid", "comprobanteId": "uuid", "estado": "ENVIADO", "intentosEnvio": 2 }
```

---

## 6. Migración de esquema requerida (previa al endpoint 5.1)

**Motivación:** `fecha_emision`/`fecha_respuesta` no bastan para distinguir "el job aún no consultó" de "el job consultó y hubo un veredicto" de "el job intentó consultar y falló por comunicación". Basar la regla de reenvío en tiempo transcurrido desde la creación es una heurística, no una garantía — el job usa `fixedDelayString` (confirmado en `${application.hacienda.polling-delay-minutes:5}`), que cuenta desde que termina la ejecución anterior, no desde un reloj fijo, y el valor puede cambiarse en cualquier momento vía configuración.

```sql
-- V{n}__agregar_seguimiento_consulta_hacienda.sql
ALTER TABLE comprobante_electronico
    ADD COLUMN fecha_ultima_consulta_hacienda TIMESTAMP,
    ADD COLUMN intentos_consulta            INT NOT NULL DEFAULT 0,
    ADD COLUMN ultimo_resultado_consulta     VARCHAR(20)
        CHECK (ultimo_resultado_consulta IN ('PENDIENTE', 'ACEPTADO', 'RECHAZADO', 'ERROR_COMUNICACION'));
```

**Comportamiento esperado del job (`HaciendaComprobanteApiServiceImpl`)** — actualiza las tres columnas en cada corrida, para cada comprobante consultado, sin excepción:

| Resultado real de la llamada | `ultimo_resultado_consulta` | `estado` de `comprobante_electronico` |
|---|---|---|
| `200 OK`, `ind-estado: 'aceptado'` | `ACEPTADO` | `ACEPTADO` |
| `200 OK`, `ind-estado: 'rechazado'` | `RECHAZADO` | `RECHAZADO` |
| `200 OK`, `ind-estado` pendiente/procesando | `PENDIENTE` | sin cambio (`ENVIADO`) |
| Excepción de red, timeout, `5xx`, o falla al renovar token OAuth2 | `ERROR_COMUNICACION` | sin cambio (`ENVIADO`) |

**Pendiente explícito, no resuelto todavía:** la distinción en código entre excepción de red y `ind-estado` de cualquier valor dentro de `HaciendaComprobanteApiServiceImpl` — necesaria para que el job escriba `ultimo_resultado_consulta` correctamente. Queda para una revisión posterior del código del job.

---

## 7. Tabla consolidada de endpoints faltantes — RESUELTA

Todos los ítems fueron verificados como implementados en el frontend. Prioridad original conservada por valor histórico; columna de estado agregada como cierre.

| # | Endpoint | Dominio | Prioridad original | Estado |
|---|---|---|---|---|
| 1 | `GET /auth/mis-empresas` | Sesión | Bloqueante | ✅ Resuelto |
| 2 | `GET /auth/perfil` | Sesión | Alta | ✅ Resuelto |
| 3 | `POST /auth/logout` | Sesión | Alta | ✅ Resuelto |
| 4 | `POST /auth/recuperar-password` | Sesión | Alta | ✅ Resuelto |
| 5 | `POST /auth/restablecer-password` | Sesión | Alta | ✅ Resuelto |
| 6 | `GET /catalogo/productos` | Catálogo | Bloqueante | ✅ Resuelto |
| 7 | `GET /catalogo/productos/{id}` | Catálogo | Bloqueante | ✅ Resuelto |
| 8 | `GET /catalogo/clientes` | Catálogo | Bloqueante | ✅ Resuelto |
| 9 | `GET /catalogo/clientes/{id}` | Catálogo | Bloqueante | ✅ Resuelto |
| 10 | `GET /facturas` | Facturación | Bloqueante | ✅ Resuelto |
| 11 | `GET /facturas/{id}` | Facturación | Bloqueante | ✅ Resuelto |
| 12 | `GET /facturas/{id}/pdf` | Entrega de documentos | Bloqueante — condiciona sección 8.3 | ✅ Resuelto |
| 13 | `GET /facturas/{id}/xml/factura` | Entrega de documentos | Bloqueante — condiciona sección 8.3 | ✅ Resuelto |
| 14 | `GET /facturas/{id}/xml/respuesta` | Entrega de documentos | Bloqueante — condiciona sección 8.3, pendiente de decidir convivencia con endpoint de diagnóstico existente | ✅ Resuelto |
| 15 | `POST /facturas/{id}/reenviar` | Facturación | Media — depende de la migración de la sección 6 | ✅ Resuelto |

**Endpoints adicionales agregados post-Release 1 por necesidad de diseño de frontend, no anticipados en este análisis:** pendiente de listar — ver spec vivo en `/v3/api-docs` como fuente de verdad hasta que se documenten aquí.

---

## 8. Orden de construcción

No arbitrario — sigue la misma lógica de dependencia técnica que ya rige las Fases 0-10 del plan de release (`plan-fases-release-1.md`): primero lo que desbloquea flujos ya diseñados, después lo nuevo.

1. **Ítems 1-5** (sesión/perfil) — desbloquean pantallas de autenticación ya diseñadas que hoy no tienen de dónde leer datos.
2. **Ítems 6-9** (listados de catálogo) — desbloquean selección de cliente/producto al facturar.
3. **Ítems 10-11** (listado/detalle de factura) — desbloquean el dashboard de facturación.
4. **Ítems 12-14** (descarga de documentos) — condicionan el criterio de aceptación de la sección 8.3; no se puede cerrar Release 1 sin esto.
5. **Migración de la sección 6, luego ítem 15** (reenvío) — en ese orden estricto, nunca en paralelo: el endpoint depende de las columnas y de la corrección pendiente del job.

---

## 9. Pendientes abiertos que no deben perderse de vista

- [ ] Decidir convivencia entre `GET /facturas/{id}/xml/respuesta` (ítem 14) y `GET /facturas/diagnostico/{comprobanteId}/xml-respuesta` (existente).
- [ ] Revisar `HaciendaComprobanteApiServiceImpl` para distinguir en código excepción de red vs. `ind-estado` de cualquier valor, y escribir `ultimo_resultado_consulta` en consecuencia.
- [ ] Evaluar `pg_trgm` para `GET /catalogo/clientes?q=` si el volumen de registros por tenant lo justifica.
