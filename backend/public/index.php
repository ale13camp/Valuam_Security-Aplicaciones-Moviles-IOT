<?php
declare(strict_types=1);

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');
mysqli_report(MYSQLI_REPORT_ERROR | MYSQLI_REPORT_STRICT);

final class ApiError extends RuntimeException {
    public function __construct(public string $tipo, string $mensaje, public int $estado = 400, public array $extra = []) {
        parent::__construct($mensaje);
    }
}

function responder(array $datos, int $estado = 200): never {
    http_response_code($estado);
    echo json_encode($datos, JSON_UNESCAPED_UNICODE | JSON_THROW_ON_ERROR);
    exit;
}

function consulta(mysqli $db, string $sql, array $valores = []): mysqli_stmt {
    $sentencia = $db->prepare($sql);
    if ($valores) $sentencia->bind_param(str_repeat('s', count($valores)), ...$valores);
    $sentencia->execute();
    return $sentencia;
}

function fila(mysqli $db, string $sql, array $valores = []): ?array {
    return consulta($db, $sql, $valores)->get_result()->fetch_assoc();
}

function publico(array $u): array {
    return array_intersect_key($u, array_flip(['id','usuario','nombres','apellidos','correo','esquema','revision_clave','activo','eliminado','version']));
}

function texto(array $datos, string $campo): string {
    $valor = trim((string)($datos[$campo] ?? ''));
    if ($valor === '') throw new ApiError('validacion', "Completa el campo $campo.");
    return $valor;
}

function validarUsuario(array $u): void {
    if (!preg_match('/^[a-zA-Z0-9_.-]{3,80}$/D', texto($u, 'usuario')))
        throw new ApiError('validacion', 'El usuario debe tener entre 3 y 80 letras sin tildes, números, puntos, guiones o guiones bajos.');
    foreach (['nombres','apellidos'] as $campo) {
        if (mb_strlen(texto($u, $campo)) > 160 || !preg_match('/^[\p{L} ]+$/uD', $u[$campo]))
            throw new ApiError('validacion', 'Nombres y apellidos solo admiten letras y espacios.');
    }
    if (strlen(texto($u,'correo')) > 254 || !filter_var($u['correo'], FILTER_VALIDATE_EMAIL))
        throw new ApiError('validacion', 'El correo no tiene un formato válido.');
}

function validarCredencial(array $u): void {
    if (!preg_match('/^[a-f0-9]{64}$/D', texto($u, 'credencial')) ||
        !in_array($u['esquema'] ?? '', ['sha256-legacy','pbkdf2-120000'], true))
        throw new ApiError('validacion', 'Credencial inválida.');
}

function sesion(mysqli $db): array {
    $cabecera = $_SERVER['HTTP_AUTHORIZATION'] ?? '';
    $token = str_starts_with($cabecera, 'Bearer ') ? substr($cabecera, 7) : '';
    $u = fila($db, 'SELECT u.* FROM sesiones s JOIN usuarios u ON u.id=s.usuario_id WHERE s.token_hash=? AND s.vence>?',
        [hash('sha256',$token), time()]);
    if (!$u) throw new ApiError('sesion', 'Inicia sesión nuevamente con Internet.', 401);
    if (!$u['activo'] || $u['eliminado']) throw new ApiError('bloqueada', 'La cuenta fue bloqueada o eliminada.', 403);
    return $u;
}

function enviarCodigo(array $config, string $destino, string $codigo): void {
    if (empty($config['smtp_user']) || empty($config['smtp_password']))
        throw new ApiError('correo', 'Falta configurar el correo de recuperación en el servidor.', 503);
    require_once dirname(__DIR__) . '/vendor/autoload.php';
    $mail = new PHPMailer\PHPMailer\PHPMailer(true);
    $mail->isSMTP();
    $mail->Host = $config['smtp_host'];
    $mail->SMTPAuth = true;
    $mail->Username = $config['smtp_user'];
    $mail->Password = $config['smtp_password'];
    $mail->SMTPSecure = PHPMailer\PHPMailer\PHPMailer::ENCRYPTION_STARTTLS;
    $mail->Port = (int)$config['smtp_port'];
    $mail->CharSet = 'UTF-8';
    $mail->Timeout = 20;
    $mail->setFrom($config['smtp_user'], 'Valuam Security');
    $mail->addAddress($destino);
    $mail->Subject = 'Código de recuperación de Valuam Security';
    $mail->Body = "Tu código es $codigo. Vence un minuto después de su generación. Si solicitaste otro código, utiliza el más reciente.";
    $mail->send();
}

try {
    $rutaConfig = getenv('VALUAM_CONFIG') ?: dirname(__DIR__) . '/config.local.php';
    $config = is_file($rutaConfig) ? require $rutaConfig : require dirname(__DIR__) . '/config.example.php';
    $db = new mysqli($config['db_host'], $config['db_user'], $config['db_password'], $config['db_name'], (int)$config['db_port']);
    $db->set_charset('utf8mb4');
    $db->query("SET time_zone='+00:00'");
    if ($_SERVER['REQUEST_METHOD'] !== 'POST') throw new ApiError('metodo', 'Utiliza POST con JSON.', 405);
    $datos = json_decode(file_get_contents('php://input'), true, 32, JSON_THROW_ON_ERROR);
    if (!is_array($datos)) throw new ApiError('validacion', 'Se esperaba un objeto JSON.');
    $accion = texto($datos, 'accion');

    if ($accion === 'salud') responder(['ok'=>true]);
    if ($accion === 'identificar') {
        $u = fila($db, 'SELECT id,esquema FROM usuarios WHERE correo=? AND eliminado=0', [texto($datos,'correo')]);
        if (!$u) throw new ApiError('credenciales','El correo o la contraseña son incorrectos.',401);
        responder(['ok'=>true,'id'=>$u['id'],'esquema'=>$u['esquema']]);
    }
    if ($accion === 'login') {
        $u = fila($db,'SELECT * FROM usuarios WHERE correo=? AND eliminado=0',[texto($datos,'correo')]);
        if (!$u || !password_verify((string)($datos['credencial'] ?? ''), $u['secreto']))
            throw new ApiError('credenciales','El correo o la contraseña son incorrectos.',401);
        if (!$u['activo']) throw new ApiError('bloqueada','Cuenta bloqueada durante la recuperación.',403);
        $token = bin2hex(random_bytes(32));
        consulta($db,'INSERT INTO sesiones VALUES (?,?,?)',[hash('sha256',$token),$u['id'],time()+604800]);
        responder(['ok'=>true,'usuario'=>publico($u),'token'=>$token]);
    }
    if ($accion === 'recuperar') {
        $db->begin_transaction();
        $u = fila($db,'SELECT * FROM usuarios WHERE correo=? AND eliminado=0 FOR UPDATE',[texto($datos,'correo')]);
        if (!$u) throw new ApiError('validacion','Ese correo no está registrado.');
        if ((int)$u['codigo_hasta'] > time()) throw new ApiError('espera','Espera a que venza el código actual para solicitar otro.',429);
        $codigo = (string)random_int(100000,999999);
        $vence = time()+60;
        $recuperacion = bin2hex(random_bytes(32));
        // La transacción conserva el bloqueo de la fila mientras SMTP confirma el envío.
        enviarCodigo($config,$u['correo'],$codigo);
        consulta($db,'UPDATE usuarios SET activo=0,codigo_hash=?,codigo_hasta=?,intentos=0,recuperacion=?,token_hash=NULL,token_hasta=NULL,version=version+1 WHERE id=?',
            [password_hash($codigo,PASSWORD_DEFAULT),$vence,$recuperacion,$u['id']]);
        consulta($db,'DELETE FROM sesiones WHERE usuario_id=?',[$u['id']]);
        $db->commit();
        responder(['ok'=>true,'recuperacion'=>$recuperacion,'vence'=>$vence,'ahora'=>time()]);
    }
    if ($accion === 'verificar') {
        $db->begin_transaction();
        $u = fila($db,'SELECT * FROM usuarios WHERE recuperacion=? AND eliminado=0 FOR UPDATE',[texto($datos,'recuperacion')]);
        if (!$u || !$u['codigo_hash'] || (int)$u['codigo_hasta'] <= time()) throw new ApiError('vencido','El código venció. Solicita uno nuevo.');
        if ((int)$u['intentos'] >= 5) throw new ApiError('codigo','Se agotaron los intentos. Solicita otro código cuando venza.');
        if (!password_verify((string)($datos['codigo'] ?? ''),$u['codigo_hash'])) {
            consulta($db,'UPDATE usuarios SET intentos=intentos+1 WHERE id=?',[$u['id']]);
            $db->commit();
            throw new ApiError('codigo','El código es incorrecto.');
        }
        $token = bin2hex(random_bytes(32));
        consulta($db,'UPDATE usuarios SET codigo_hash=NULL,token_hash=?,token_hasta=? WHERE id=?',[hash('sha256',$token),time()+300,$u['id']]);
        $db->commit();
        responder(['ok'=>true,'token'=>$token,'id'=>$u['id']]);
    }
    if ($accion === 'nueva_clave') {
        validarCredencial($datos);
        $db->begin_transaction();
        $u = fila($db,'SELECT * FROM usuarios WHERE token_hash=? AND token_hasta>? AND eliminado=0 FOR UPDATE',
            [hash('sha256',texto($datos,'token')),time()]);
        if (!$u) throw new ApiError('vencido','La autorización venció. Solicita otro código.');
        consulta($db,'UPDATE usuarios SET secreto=?,esquema=?,revision_clave=revision_clave+1,activo=1,codigo_hash=NULL,codigo_hasta=NULL,recuperacion=NULL,token_hash=NULL,token_hasta=NULL,version=version+1 WHERE id=?',
            [password_hash($datos['credencial'],PASSWORD_DEFAULT),$datos['esquema'],$u['id']]);
        consulta($db,'DELETE FROM sesiones WHERE usuario_id=?',[$u['id']]);
        $db->commit();
        responder(['ok'=>true]);
    }

    if ($accion === 'listar') {
        sesion($db);
        $filas = consulta($db,'SELECT * FROM usuarios ORDER BY nombres,apellidos')->get_result()->fetch_all(MYSQLI_ASSOC);
        responder(['ok'=>true,'usuarios'=>array_map('publico',$filas)]);
    }

    if ($accion === 'operacion') {
        $op = $datos['operacion'] ?? [];
        $tipo = texto($op,'tipo');
        $u = $op['datos'] ?? [];
        if ($tipo !== 'crear') $actor = sesion($db);
        if (!preg_match('/^[a-f0-9-]{36}$/D',texto($op,'id'))) throw new ApiError('validacion','Identificador de operación inválido.');
        $db->begin_transaction();
        $huella = hash('sha256',json_encode($op,JSON_THROW_ON_ERROR));
        consulta($db,'INSERT INTO operaciones (id,huella) VALUES (?,?) ON DUPLICATE KEY UPDATE id=id',[$op['id'],$huella]);
        $existente = fila($db,'SELECT * FROM operaciones WHERE id=? FOR UPDATE',[$op['id']]);
        if (!hash_equals($existente['huella'],$huella)) throw new ApiError('validacion','El identificador ya corresponde a otra operación.',409);
        if ($existente['resultado']) {
            $db->commit();
            responder(json_decode($existente['resultado'],true));
        }
        if ($tipo === 'lectura') {
            if (!is_numeric($u['temperatura'] ?? null) || !is_numeric($u['humedad'] ?? null) || $u['humedad']<0 || $u['humedad']>100)
                throw new ApiError('validacion','Lectura inválida.');
            consulta($db,'INSERT INTO lecturas VALUES (?,?,?,?,?)',[$u['id'],$u['temperatura'],$u['humedad'],$u['obtenida'],$actor['id']]);
            $resultado = ['ok'=>true];
        } else {
            if (!preg_match('/^[a-f0-9-]{36}$/D',texto($u,'id'))) throw new ApiError('validacion','Identificador de usuario inválido.');
            $actual = fila($db,'SELECT * FROM usuarios WHERE id=? FOR UPDATE',[$u['id']]);
            if ($tipo === 'crear') {
                validarUsuario($u);
                validarCredencial($u);
                if ($actual) throw new ApiError('conflicto','Ese registro ya existe.',409,['remoto'=>publico($actual)]);
                consulta($db,'INSERT INTO usuarios (id,usuario,nombres,apellidos,correo,secreto,esquema) VALUES (?,?,?,?,?,?,?)',
                    [$u['id'],$u['usuario'],$u['nombres'],$u['apellidos'],$u['correo'],password_hash($u['credencial'],PASSWORD_DEFAULT),$u['esquema']]);
            } else {
                if (!$actual || (int)$actual['version'] !== (int)($op['version'] ?? 0))
                    throw new ApiError('conflicto','El registro cambió en el servidor.',409,['remoto'=>$actual ? publico($actual) : null]);
                if ($tipo === 'editar') {
                    if ($actual['eliminado']) throw new ApiError('conflicto','El usuario fue eliminado.',409,['remoto'=>publico($actual)]);
                    validarUsuario($u);
                    consulta($db,'UPDATE usuarios SET usuario=?,nombres=?,apellidos=?,correo=?,version=version+1 WHERE id=?',
                        [$u['usuario'],$u['nombres'],$u['apellidos'],$u['correo'],$u['id']]);
                } elseif ($tipo === 'eliminar') {
                    consulta($db,'UPDATE usuarios SET eliminado=1,activo=0,version=version+1 WHERE id=?',[$u['id']]);
                    consulta($db,'DELETE FROM sesiones WHERE usuario_id=?',[$u['id']]);
                } else throw new ApiError('validacion','Tipo de operación inválido.');
            }
            $resultado = ['ok'=>true,'usuario'=>publico(fila($db,'SELECT * FROM usuarios WHERE id=?',[$u['id']]))];
        }
        consulta($db,'UPDATE operaciones SET resultado=? WHERE id=?',[json_encode($resultado,JSON_THROW_ON_ERROR),$op['id']]);
        $db->commit();
        responder($resultado);
    }
    throw new ApiError('accion','Acción desconocida.',404);
} catch (ApiError $e) {
    if (isset($db)) $db->rollback();
    responder(['ok'=>false,'error'=>$e->tipo,'mensaje'=>$e->getMessage()]+$e->extra,$e->estado);
} catch (mysqli_sql_exception $e) {
    if (isset($db)) $db->rollback();
    if ($e->getCode() === 1062) responder(['ok'=>false,'error'=>'duplicado','mensaje'=>'El nombre de usuario o correo ya está registrado.'],409);
    error_log((string)$e);
    responder(['ok'=>false,'error'=>'servidor','mensaje'=>'No se pudo completar la operación de almacenamiento.'],503);
} catch (Throwable $e) {
    if (isset($db)) $db->rollback();
    error_log((string)$e);
    responder(['ok'=>false,'error'=>'servidor','mensaje'=>'No se pudo completar la solicitud. Reintenta.'],503);
}
