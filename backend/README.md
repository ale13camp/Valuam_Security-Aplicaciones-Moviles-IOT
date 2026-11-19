# Backend de la segunda evaluación

La API usa PHP, MySQLi, MySQL/MariaDB y PHPMailer. El directorio publicado por Apache debe ser exclusivamente `public`. Configuración y dependencias quedan fuera del acceso web.

## Configuración

1. Importar `schema.sql` en MySQL con una cuenta administradora.
2. Instalar dependencias con `composer install --no-dev`.
3. Copiar `config.example.php` a `config.local.php` y configurar la conexión. Para despliegue, usar una cuenta de MySQL con permisos SELECT, INSERT, UPDATE y DELETE sobre `valuam_security`.
4. Configurar `smtp_user` como `trabajotens423@gmail.com` y `smtp_password` con su contraseña de aplicación. La configuración real está excluida de Git. También se puede indicar su ruta con la variable `VALUAM_CONFIG`.
5. En Amazon Linux 2023, copiar esta carpeta incluyendo `vendor` y ejecutar `sudo bash deploy-aws.sh`. El script instala Apache, PHP y MariaDB, importa el esquema sin borrar tablas y crea la configuración privada si falta.

En Windows, las pruebas usan PHP de Wampserver64 con el servidor local `php -S 127.0.0.1:8085 -t backend/public`. MySQL escucha en 3306. El servicio de prueba se limita a loopback.

## Contrato y almacenamiento

Todas las peticiones son POST JSON a `public/index.php`, con `accion` y los datos de la operación. Las acciones son `salud`, `identificar`, `login`, `listar`, `operacion`, `recuperar`, `verificar` y `nueva_clave`. Consulta, edición, eliminación y envío de lecturas requieren `Authorization: Bearer <token>` de una cuenta activa.

Los usuarios tienen UUID, nombre de usuario, nombres, apellidos, correo, versión y revisión de credencial. La app deriva una credencial PBKDF2-HMAC-SHA256 de 256 bits con 120000 iteraciones y el UUID como sal. PHP guarda `password_hash` de esa credencial. Las cuentas migradas conservan temporalmente su derivación SHA256 anterior. La credencial enviada equivale a una contraseña y requiere transporte HTTPS para el servidor remoto.

Una operación incluye `id` (UUID de reintento), `tipo` (`crear`, `editar`, `eliminar` o `lectura`), `version` base y `datos`. La tabla `operaciones` conserva la huella y respuesta del sobre procesado dentro de la transacción de MySQL. La repetición exacta devuelve el mismo resultado. Reutilizar el ID con otro contenido genera error. El esquema conserva eliminaciones para distribuirlas a las copias locales.

La API devuelve `ok`, los datos resultantes o un `error` y `mensaje`. Un conflicto de versión devuelve también `remoto`. Las cuentas eliminadas conservan sus identificadores únicos para evitar reutilización accidental.

La recuperación genera seis dígitos con `random_int`, guarda su hash, bloquea la cuenta tras confirmar el envío SMTP y exige verificación dentro de 60 segundos desde la generación. Se permiten cinco intentos por código. Verificarlo consume el código y devuelve una autorización de cinco minutos para cambiar contraseña. Guardar la nueva contraseña reactiva la cuenta y revoca sesiones anteriores.

## Android y pruebas

La URL predeterminada es `https://13.216.100.242/valuam/index.php`. Se puede reemplazar con `-PvaluamApiUrl=https://SERVIDOR/valuam/index.php`.

## Despliegue AWS verificado el 19 de noviembre de 2026

Instancia `valuamsecurity`, ID `i-0fd9437d4eef39d5d`, región `us-east-1`, Amazon Linux 2023, tipo `t3.micro`. IP elástica `13.216.100.242`, asignación `eipalloc-0deb8d124bb5215ea`.

Apache publica `/valuam/index.php` por HTTPS. HTTP redirige a HTTPS y mantiene accesible la ruta ACME. El certificado de Let's Encrypt cubre la IP y se renueva con `/opt/certbot/bin/certbot renew`; el temporizador `valuam-certbot.timer` comprueba la renovación cada 12 horas y recarga Apache al renovar. Estos certificados duran aproximadamente seis días; el laboratorio debe estar activo para renovarlos. Referencia oficial: https://letsencrypt.org/2026/03/11/shorter-certs-certbot

La configuración privada está en `/opt/valuam-security/config.local.php`, con permisos `640`, propietario `root:apache`. MariaDB escucha exclusivamente en `127.0.0.1:3306`. El grupo `sg-068013b332b651897` permite HTTP/HTTPS y SSH desde la IP pública del equipo de desarrollo; si esta cambia, actualizar únicamente el origen de SSH. La clave privada queda fuera del repositorio.

Se migraron los datos locales existentes y se verificaron registro, login, CRUD, duplicados, conflictos, idempotencia, lecturas y verificación/reactivación de contraseña con `tests/integration.py` contra HTTPS y MariaDB de AWS. Los datos QA se eliminan al terminar. La autenticación SMTP de Gmail se comprobó desde la instancia sin mostrar credenciales.

La APK con el endpoint HTTPS fue instalada conservando los datos del Redmi Note 8 Pro. Se verificó el inicio de sesión contra AWS y el estado «Sincronización completada», con cero operaciones pendientes y cero conflictos, sin `adb reverse`. La renovación simulada del certificado también terminó correctamente.

Para pruebas por USB se utiliza `adb reverse tcp:8085 tcp:8085` y `-PvaluamApiUrl=http://127.0.0.1:8085/index.php`. Las excepciones de HTTP están limitadas a loopback y al emulador. La entrega se verifica en teléfono físico.

`python backend/tests/integration.py` prueba las API en MySQL local usando registros QA identificados por UUID. Sus datos se eliminan al terminar. Permite configurar `VALUAM_TEST_URL`, `VALUAM_TEST_MYSQL` y `VALUAM_TEST_PHP`. La prueba de códigos inserta un desafío conocido exclusivamente para validar la verificación y reactivación. El envío real por Gmail requiere una prueba adicional después de configurar SMTP.

Las pruebas instrumentadas verifican migración a SQLite, persistencia al reabrir, rollback de cambios parciales, derivación de credenciales y el flujo con servidor local. La versión de evaluación debe demostrar además pérdida y recuperación de Wi-Fi/datos móviles, conflicto entre dos teléfonos y linterna real.
