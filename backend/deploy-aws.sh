#!/usr/bin/env bash
set -euo pipefail
# Ejecutar con sudo desde la carpeta backend copiada a Amazon Linux 2023.
cd "$(dirname "$0")"
dnf install -y httpd php php-mysqlnd php-mbstring php-xml mariadb105-server unzip
systemctl enable --now httpd mariadb
mysql < schema.sql
install -d -m 750 /opt/valuam-security
cp -R public vendor config.example.php correo.php /opt/valuam-security/
if [ ! -f /opt/valuam-security/config.local.php ]; then
    db_password=$(openssl rand -hex 24)
    mysql -e "CREATE USER IF NOT EXISTS 'valuam_app'@'127.0.0.1' IDENTIFIED BY '$db_password'; GRANT SELECT,INSERT,UPDATE,DELETE ON valuam_security.* TO 'valuam_app'@'127.0.0.1';"
    DB_PASSWORD="$db_password" php -r '
        $config = require "config.example.php";
        $config["db_password"] = getenv("DB_PASSWORD");
        $config["smtp_user"] = "trabajotens423@gmail.com";
        file_put_contents("/opt/valuam-security/config.local.php", "<?php\nreturn " . var_export($config, true) . ";\n");
    '
fi
cat > /etc/httpd/conf.d/valuam-security.conf <<'APACHE'
Alias /valuam /opt/valuam-security/public
<Directory /opt/valuam-security/public>
    Require all granted
    Options -Indexes
    AllowOverride None
</Directory>
APACHE
chown -R root:apache /opt/valuam-security
chmod 640 /opt/valuam-security/config.local.php
chmod -R g+rX /opt/valuam-security
systemctl restart httpd php-fpm
echo 'API instalada en /valuam/index.php. Configurar SMTP en /opt/valuam-security/config.local.php.'
