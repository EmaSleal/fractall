-- Cola de reintento para el correo de verificación de email (Fase 4, sección 3.1 del
-- documento de arquitectura): "si el envío falla por el tope diario del proveedor, se
-- encola y reintenta automáticamente". Alcance deliberadamente acotado a este único caso
-- de uso -- NO es una cola de correo de propósito general, y no sustituye a
-- ComprobanteReintentosJob (fuera de alcance de Release 1, ver sección 8.2).
--
-- cuerpo_html se persiste completo (en lugar de solo datos para reconstruirlo) porque el
-- contenido del correo de verificación ya está resuelto en el momento del primer intento
-- fallido (el token ya fue generado y su hash ya vive en usuario_token) -- reintentar solo
-- exige reenviar el mismo HTML, no reconstruir nada a partir de una plantilla.
--
-- Columnas de fecha en TIMESTAMP (sin zona horaria), no TIMESTAMPTZ, para mantener la
-- misma convención que el resto de las tablas de este esquema (usuario.create_date,
-- usuario_token.expira_en, etc.), todas TIMESTAMP.
CREATE TABLE cola_reintento_email (
    id              UUID PRIMARY KEY DEFAULT uuidv7(),
    destinatario    VARCHAR(255) NOT NULL,
    asunto          VARCHAR(255) NOT NULL,
    cuerpo_html     TEXT NOT NULL,
    intentos        INT NOT NULL DEFAULT 0,
    proximo_intento TIMESTAMP NOT NULL DEFAULT now(),
    estado          VARCHAR(10) NOT NULL DEFAULT 'PENDIENTE'
                        CHECK (estado IN ('PENDIENTE', 'ENVIADO', 'AGOTADO')),
    create_date     TIMESTAMP NOT NULL DEFAULT now()
);

-- El job programado solo consulta filas PENDIENTE cuyo turno ya llegó; un índice parcial
-- evita escanear filas ya resueltas (ENVIADO/AGOTADO), que con el tiempo serán la mayoría.
CREATE INDEX ix_cola_reintento_email_pendientes
    ON cola_reintento_email (proximo_intento)
    WHERE estado = 'PENDIENTE';
