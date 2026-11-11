#!/usr/bin/env bash
# Crea la firma de MenuCal (una sola vez). macOS y Linux.
# Uso: bash scripts/firma/crear-firma.sh
set -euo pipefail

DIR="$HOME/MenuCal-firma"
KEYSTORE="$DIR/menucal.jks"
NOTES="$DIR/LEER-ANTES.txt"
PROPS="$DIR/keystore.properties"
ALIAS="menucal"
REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

# 1. keytool
if ! command -v keytool >/dev/null 2>&1; then
  echo "No se encuentra 'keytool'. Viene con el JDK (Java Development Kit)."
  echo "  macOS:          brew install --cask temurin@17"
  echo "  Debian/Ubuntu:  sudo apt install openjdk-17-jdk"
  echo "  Fedora:         sudo dnf install java-17-openjdk-devel"
  echo "Instálalo y vuelve a ejecutar este script."
  exit 1
fi

# Nunca sobrescribir una firma existente: perderla impide actualizar la app.
if [ -e "$KEYSTORE" ]; then
  echo "Ya existe una firma en $KEYSTORE. No se toca."
  echo "Si de verdad quieres crear otra, mueve antes esa carpeta a otro sitio."
  exit 1
fi

# 2. Keystore fuera del repositorio, con contraseña aleatoria
mkdir -p "$DIR"
chmod 700 "$DIR"
random_password() {
  LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 32 || true
}
STORE_PASSWORD="$(random_password)"
# El formato PKCS12 exige la misma contraseña para el almacén y para la clave.
KEY_PASSWORD="$STORE_PASSWORD"
if [ "${#STORE_PASSWORD}" -ne 32 ]; then
  echo "No se pudo generar una contraseña aleatoria."
  exit 1
fi

export MENUCAL_STORE_PASSWORD="$STORE_PASSWORD"
keytool -genkeypair \
  -keystore "$KEYSTORE" -storetype PKCS12 \
  -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass:env MENUCAL_STORE_PASSWORD -keypass:env MENUCAL_STORE_PASSWORD \
  -dname "CN=MenuCal, O=MenuCal, C=ES" >/dev/null
unset MENUCAL_STORE_PASSWORD
chmod 600 "$KEYSTORE"

# 3. Notas con las contraseñas
umask 077
cat >"$NOTES" <<EOF
MenuCal - FIRMA DE LA APP
=========================

ADVERTENCIA: guarda copias del keystore (menucal.jks) y de estas contraseñas
en DOS sitios seguros (por ejemplo, tu Drive personal y un gestor de
contraseñas). Si se pierden, no se pueden publicar actualizaciones que se
instalen sobre la app actual.

No subas nunca esta carpeta ni su contenido al repositorio.

Keystore:                $KEYSTORE
Alias:                   $ALIAS
Contraseña del keystore: $STORE_PASSWORD
Contraseña de la clave:  $KEY_PASSWORD

Secretos de GitHub (Settings > Secrets and variables > Actions):
  ANDROID_KEYSTORE_BASE64    contenido de menucal.jks en base64
  ANDROID_KEYSTORE_PASSWORD  contraseña del keystore
  ANDROID_KEY_ALIAS          $ALIAS
  ANDROID_KEY_PASSWORD       contraseña de la clave

Para firmar en este ordenador: copia keystore.properties (de esta carpeta)
a la raíz del proyecto. Git lo ignora.
EOF

cat >"$PROPS" <<EOF
storeFile=$KEYSTORE
storePassword=$STORE_PASSWORD
keyAlias=$ALIAS
keyPassword=$KEY_PASSWORD
EOF

echo "Firma creada en $DIR"
echo "Lee $NOTES y haz ahora las dos copias de seguridad."

# 4. Secretos de GitHub
KEYSTORE_BASE64="$(base64 <"$KEYSTORE" | tr -d '\n')"
if command -v gh >/dev/null 2>&1 && (cd "$REPO_DIR" && gh auth status >/dev/null 2>&1); then
  echo "Subiendo los cuatro secretos al repositorio de GitHub..."
  (
    cd "$REPO_DIR"
    printf '%s' "$KEYSTORE_BASE64" | gh secret set ANDROID_KEYSTORE_BASE64
    printf '%s' "$STORE_PASSWORD" | gh secret set ANDROID_KEYSTORE_PASSWORD
    printf '%s' "$ALIAS" | gh secret set ANDROID_KEY_ALIAS
    printf '%s' "$KEY_PASSWORD" | gh secret set ANDROID_KEY_PASSWORD
  )
  echo "Secretos subidos."
else
  B64_FILE="$DIR/keystore-base64.txt"
  printf '%s' "$KEYSTORE_BASE64" >"$B64_FILE"
  echo
  echo "GitHub CLI (gh) no está instalado o no has iniciado sesión."
  echo "Añade los secretos a mano:"
  echo "  1. Abre el repositorio en github.com."
  echo "  2. Settings > Secrets and variables > Actions > New repository secret."
  echo "  3. Crea estos cuatro secretos (los valores están en $NOTES):"
  echo "       ANDROID_KEYSTORE_BASE64    (pega el contenido de $B64_FILE)"
  echo "       ANDROID_KEYSTORE_PASSWORD"
  echo "       ANDROID_KEY_ALIAS"
  echo "       ANDROID_KEY_PASSWORD"
fi
