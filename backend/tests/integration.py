"""Pruebas contra la API y MySQL locales. Crea únicamente registros QA con UUID."""
import hashlib
import json
import os
import subprocess
import urllib.error
import urllib.request
import uuid

URL = os.environ.get("VALUAM_TEST_URL", "http://127.0.0.1:8085/index.php")
MYSQL = os.environ.get("VALUAM_TEST_MYSQL", r"C:\wamp64\bin\mysql\mysql8.4.7\bin\mysql.exe")
token = ""
ids = []
operations = []


def api(action, expected=200, **values):
    data = json.dumps(dict(accion=action, **values)).encode()
    request = urllib.request.Request(URL, data, headers={"Content-Type": "application/json", "Authorization": "Bearer " + token})
    try:
        response = urllib.request.urlopen(request, timeout=50)
    except urllib.error.HTTPError as error:
        response = error
    body = json.load(response)
    assert response.status == expected, (action, response.status, body)
    return body


def sql(statement):
    subprocess.run([MYSQL, "-u", "root", "valuam_security", "-e", statement], check=True, capture_output=True)


def operation(kind, user, version=0, operation_id=None, expected=200):
    operation_id = operation_id or str(uuid.uuid4())
    operations.append(operation_id)
    return api("operacion", expected, operacion=dict(id=operation_id, tipo=kind, datos=user, version=version))


try:
    assert api("salud")["ok"]
    user_id = str(uuid.uuid4())
    ids.append(user_id)
    credential = hashlib.pbkdf2_hmac("sha256", b"Valuam$123", user_id.encode(), 120000).hex()
    user = dict(id=user_id, usuario="qa_" + user_id[:8], nombres="José María", apellidos="Muñoz Díaz",
                correo="qa_" + user_id + "@example.com", credencial=credential, esquema="pbkdf2-120000")
    first_id = str(uuid.uuid4())
    created = operation("crear", user, operation_id=first_id)
    assert operation("crear", user, operation_id=first_id) == created
    assert int(created["usuario"]["version"]) == 1
    api("login", 401, correo=user["correo"], credencial="incorrecta")
    identity = api("identificar", correo=user["correo"])
    assert identity["id"] == user_id
    token = api("login", correo=user["correo"], credencial=credential)["token"]
    assert any(u["id"] == user_id for u in api("listar")["usuarios"])
    duplicate = dict(user, id=str(uuid.uuid4()))
    ids.append(duplicate["id"])
    assert operation("crear", duplicate, expected=409)["error"] == "duplicado"
    invalid = dict(user, nombres="123")
    operation("editar", invalid, 1, expected=400)
    edited = operation("editar", dict(user, nombres="María José"), 1)
    assert int(edited["usuario"]["version"]) == 2
    conflict = operation("editar", user, 1, expected=409)
    assert int(conflict["remoto"]["version"]) == 2
    # Las pruebas del código usan un desafío conocido en la base QA. El envío real depende de Gmail.
    sql(f"UPDATE usuarios SET activo=0,codigo_hash=NULL,codigo_hasta=UNIX_TIMESTAMP()-1,recuperacion='qa_{user_id}' WHERE id='{user_id}'")
    api("login", 403, correo=user["correo"], credencial=credential)
    api("verificar", 400, recuperacion="qa_" + user_id, codigo="123456")
    # Hash bcrypt de 123456 producido por PHP, sin simular la lógica de verificación de la API.
    php = os.environ.get("VALUAM_TEST_PHP", r"C:\wamp64\bin\php\php8.3.28\php.exe")
    code_hash = subprocess.check_output([php, "-r", 'echo password_hash("123456",PASSWORD_DEFAULT);'], text=True)
    sql(f"UPDATE usuarios SET codigo_hash='{code_hash}',codigo_hasta=UNIX_TIMESTAMP()+60 WHERE id='{user_id}'")
    api("verificar", 400, recuperacion="qa_" + user_id, codigo="000000")
    reset = api("verificar", recuperacion="qa_" + user_id, codigo="123456")
    api("verificar", 400, recuperacion="qa_" + user_id, codigo="123456")
    new_credential = hashlib.pbkdf2_hmac("sha256", b"Nueva$123", user_id.encode(), 120000).hex()
    api("nueva_clave", token=reset["token"], credencial=new_credential, esquema="pbkdf2-120000")
    api("nueva_clave", 400, token=reset["token"], credencial=new_credential, esquema="pbkdf2-120000")
    api("login", 401, correo=user["correo"], credencial=credential)
    token = api("login", correo=user["correo"], credencial=new_credential)["token"]
    reading = dict(id=str(uuid.uuid4()), temperatura=22.5, humedad=60, obtenida=1770000000000)
    receipt = str(uuid.uuid4())
    operation("lectura", reading, operation_id=receipt)
    operation("lectura", reading, operation_id=receipt)
    current = next(u for u in api("listar")["usuarios"] if u["id"] == user_id)
    deleted = operation("eliminar", user, int(current["version"]))
    assert int(deleted["usuario"]["eliminado"]) == 1
    api("login", 401, correo=user["correo"], credencial=new_credential)
    print("OK: registro, duplicados, login, CRUD, conflictos, reintentos idempotentes, lecturas, códigos vencidos/incorrectos/únicos y reactivación.")
finally:
    for operation_id in operations:
        sql(f"DELETE FROM operaciones WHERE id='{operation_id}'")
    for user_id in ids:
        sql(f"DELETE FROM lecturas WHERE usuario_id='{user_id}'; DELETE FROM sesiones WHERE usuario_id='{user_id}'; DELETE FROM usuarios WHERE id='{user_id}'")
