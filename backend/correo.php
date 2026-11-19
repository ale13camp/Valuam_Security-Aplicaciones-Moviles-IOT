<?php
declare(strict_types=1);

function cuerpoCodigo(string $codigo): string {
    if (!preg_match('/^[0-9]{6}$/D', $codigo)) {
        throw new InvalidArgumentException('El código debe contener seis dígitos.');
    }
    return <<<HTML
<!DOCTYPE html>
<html lang="es">
<head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1"></head>
<body style="margin:0;padding:20px;background-color:#ffffff;font-family:Arial,Helvetica,sans-serif;">
    <table role="presentation" style="width:100%;max-width:560px;margin:0 auto;border:1px solid #dddddd;border-radius:14px;border-spacing:0;">
        <tr>
            <td style="padding:40px 30px;text-align:center;">
                <h2 style="margin:20px 0 28px;color:#333333;font-size:28px;line-height:1.3;">Código de verificación</h2>
                <p style="margin:0 0 22px;color:#555555;font-size:18px;line-height:1.5;">Hemos recibido una solicitud de verificación.</p>
                <p style="margin:0 0 30px;color:#555555;font-size:18px;line-height:1.5;">Tu código de verificación es:</p>
                <div style="margin:0 0 30px;padding:25px 10px 25px 20px;border-radius:10px;background-color:#f3f3f3;color:#222222;font-size:36px;line-height:1.5;font-weight:bold;letter-spacing:10px;white-space:nowrap;">{$codigo}</div>
                <p style="margin:0 0 12px;color:#777777;font-size:16px;line-height:1.5;">Este código es de un solo uso.</p>
                <p style="margin:0 0 18px;color:#777777;font-size:16px;line-height:1.5;">Vence en un minuto desde su generación.</p>
                <p style="margin:0;color:#999999;font-size:15px;line-height:1.5;">Si tú no solicitaste este código, puedes ignorar este correo.</p>
            </td>
        </tr>
    </table>
</body>
</html>
HTML;
}
