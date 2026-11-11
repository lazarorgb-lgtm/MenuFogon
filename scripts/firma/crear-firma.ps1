# Crea la firma de MenuCal (una sola vez). Windows.
# Uso: powershell -ExecutionPolicy Bypass -File scripts\firma\crear-firma.ps1
$ErrorActionPreference = 'Stop'

$Dir = Join-Path $HOME 'MenuCal-firma'
$Keystore = Join-Path $Dir 'menucal.jks'
$Notes = Join-Path $Dir 'LEER-ANTES.txt'
$Props = Join-Path $Dir 'keystore.properties'
$Alias = 'menucal'
$RepoDir = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path

# 1. keytool
if (-not (Get-Command keytool -ErrorAction SilentlyContinue)) {
    Write-Host "No se encuentra 'keytool'. Viene con el JDK (Java Development Kit)."
    Write-Host "Instalalo con:  winget install EclipseAdoptium.Temurin.17.JDK"
    Write-Host "Cierra y abre de nuevo la terminal, y vuelve a ejecutar este script."
    exit 1
}

# Nunca sobrescribir una firma existente: perderla impide actualizar la app.
if (Test-Path $Keystore) {
    Write-Host "Ya existe una firma en $Keystore. No se toca."
    Write-Host "Si de verdad quieres crear otra, mueve antes esa carpeta a otro sitio."
    exit 1
}

# 2. Keystore fuera del repositorio, con contrasena aleatoria
New-Item -ItemType Directory -Force $Dir | Out-Null

function New-RandomPassword {
    $chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789'
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $bytes = New-Object byte[] 1
    $sb = New-Object System.Text.StringBuilder
    while ($sb.Length -lt 32) {
        $rng.GetBytes($bytes)
        # Descarta valores que sesgarian el reparto (248 = 62 * 4).
        if ($bytes[0] -lt 248) { [void]$sb.Append($chars[$bytes[0] % 62]) }
    }
    $rng.Dispose()
    $sb.ToString()
}

$StorePassword = New-RandomPassword
# El formato PKCS12 exige la misma contrasena para el almacen y para la clave.
$KeyPassword = $StorePassword

$env:MENUCAL_STORE_PASSWORD = $StorePassword
try {
    & keytool -genkeypair `
        -keystore $Keystore -storetype PKCS12 `
        -alias $Alias -keyalg RSA -keysize 4096 -validity 10000 `
        '-storepass:env' MENUCAL_STORE_PASSWORD '-keypass:env' MENUCAL_STORE_PASSWORD `
        -dname 'CN=MenuCal, O=MenuCal, C=ES' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "keytool ha fallado (codigo $LASTEXITCODE)." }
}
finally {
    Remove-Item Env:MENUCAL_STORE_PASSWORD -ErrorAction SilentlyContinue
}

# 3. Notas con las contrasenas
$utf8 = New-Object System.Text.UTF8Encoding($false)
$notesText = @"
MenuCal - FIRMA DE LA APP
=========================

ADVERTENCIA: guarda copias del keystore (menucal.jks) y de estas contraseñas
en DOS sitios seguros (por ejemplo, tu Drive personal y un gestor de
contraseñas). Si se pierden, no se pueden publicar actualizaciones que se
instalen sobre la app actual.

No subas nunca esta carpeta ni su contenido al repositorio.

Keystore:                $Keystore
Alias:                   $Alias
Contraseña del keystore: $StorePassword
Contraseña de la clave:  $KeyPassword

Secretos de GitHub (Settings > Secrets and variables > Actions):
  ANDROID_KEYSTORE_BASE64    contenido de menucal.jks en base64
  ANDROID_KEYSTORE_PASSWORD  contraseña del keystore
  ANDROID_KEY_ALIAS          $Alias
  ANDROID_KEY_PASSWORD       contraseña de la clave

Para firmar en este ordenador: copia keystore.properties (de esta carpeta)
a la raíz del proyecto. Git lo ignora.
"@
[System.IO.File]::WriteAllText($Notes, $notesText, $utf8)

$storeFile = $Keystore -replace '\\', '/'
$propsText = "storeFile=$storeFile`nstorePassword=$StorePassword`nkeyAlias=$Alias`nkeyPassword=$KeyPassword`n"
[System.IO.File]::WriteAllText($Props, $propsText, $utf8)

Write-Host "Firma creada en $Dir"
Write-Host "Lee $Notes y haz ahora las dos copias de seguridad."

# 4. Secretos de GitHub
$KeystoreBase64 = [Convert]::ToBase64String([System.IO.File]::ReadAllBytes($Keystore))
$ghReady = $false
if (Get-Command gh -ErrorAction SilentlyContinue) {
    Push-Location $RepoDir
    try {
        & gh auth status *> $null
        $ghReady = ($LASTEXITCODE -eq 0)
    }
    finally { Pop-Location }
}

if ($ghReady) {
    Write-Host 'Subiendo los cuatro secretos al repositorio de GitHub...'
    Push-Location $RepoDir
    try {
        $secrets = [ordered]@{
            ANDROID_KEYSTORE_BASE64   = $KeystoreBase64
            ANDROID_KEYSTORE_PASSWORD = $StorePassword
            ANDROID_KEY_ALIAS         = $Alias
            ANDROID_KEY_PASSWORD      = $KeyPassword
        }
        foreach ($name in $secrets.Keys) {
            & gh secret set $name --body $secrets[$name]
            if ($LASTEXITCODE -ne 0) { throw "No se pudo subir el secreto $name." }
        }
    }
    finally { Pop-Location }
    Write-Host 'Secretos subidos.'
}
else {
    $b64File = Join-Path $Dir 'keystore-base64.txt'
    [System.IO.File]::WriteAllText($b64File, $KeystoreBase64, $utf8)
    Write-Host ''
    Write-Host 'GitHub CLI (gh) no esta instalado o no has iniciado sesion.'
    Write-Host 'Anade los secretos a mano:'
    Write-Host '  1. Abre el repositorio en github.com.'
    Write-Host '  2. Settings > Secrets and variables > Actions > New repository secret.'
    Write-Host "  3. Crea estos cuatro secretos (los valores estan en $Notes):"
    Write-Host "       ANDROID_KEYSTORE_BASE64    (pega el contenido de $b64File)"
    Write-Host '       ANDROID_KEYSTORE_PASSWORD'
    Write-Host '       ANDROID_KEY_ALIAS'
    Write-Host '       ANDROID_KEY_PASSWORD'
}
