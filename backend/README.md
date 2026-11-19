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

Configurar la URL con `-PvaluamApiUrl=https://SERVIDOR/valuam/index.php`. Sin esa propiedad, la app muestra que falta configurar el servidor y conserva el trabajo local.

Para pruebas por USB se utiliza `adb reverse tcp:8085 tcp:8085` y `-PvaluamApiUrl=http://127.0.0.1:8085/index.php`. Las excepciones de HTTP están limitadas a loopback y al emulador. La entrega se verifica en teléfono físico.

`python backend/tests/integration.py` prueba las API en MySQL local usando registros QA identificados por UUID. Sus datos se eliminan al terminar. Permite configurar `VALUAM_TEST_URL`, `VALUAM_TEST_MYSQL` y `VALUAM_TEST_PHP`. La prueba de códigos inserta un desafío conocido exclusivamente para validar la verificación y reactivación. El envío real por Gmail requiere una prueba adicional después de configurar SMTP.

Las pruebas instrumentadas verifican migración a SQLite, persistencia al reabrir, rollback de cambios parciales, derivación de credenciales y el flujo con servidor local. La versión de evaluación debe demostrar además pérdida y recuperación de Wi-Fi/datos móviles, conflicto entre dos teléfonos y linterna real.
