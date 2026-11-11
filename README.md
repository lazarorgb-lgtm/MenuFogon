# MenuFogon

App Android para controlar los menús de un servicio de comidas: carga los PDF mensuales de cada tipo de menú (Normal, Diabético, EEI), registra cuántos menús se sirven cada día, indica cuántos primeros y segundos preparar y genera estadísticas. Funciona sin conexión y sin cuentas.

> En el móvil la app se llama **MenuFogon**. El archivo de instalación (`MenuCal.apk`), las copias de seguridad (`MenuCal-copia-AAAAMMDD.json`) y la carpeta de firma (`~/MenuCal-firma`) usan el nombre MenuCal.

Para instalarla en un móvil, ver [INSTALAR_EN_MOVIL.md](INSTALAR_EN_MOVIL.md).

## Módulos

| Módulo | Qué contiene |
|---|---|
| `core` | Kotlin/JVM puro: modelos, `TextItem`, parser de menús, cálculo de preparación, estadísticas, validaciones y formato de la copia de seguridad. No depende de Android. |
| `app` | Android: Compose + Material 3, Room, PdfBox-Android y ML Kit. Convierte el PDF en `TextItem`, guarda los datos y muestra la interfaz. |
| `tools` | JVM: genera con PdfBox de escritorio los JSON de posiciones de texto de `samples/*.pdf` en `core/src/test/resources/fixtures/`. |

El módulo `app` solo se incluye en la build si hay Android SDK (`ANDROID_HOME`, `ANDROID_SDK_ROOT` o `sdk.dir` en `local.properties`). Así `:core:test` funciona en una máquina sin SDK.

## Cómo ejecutar los tests

Hace falta JDK 17.

```bash
./gradlew :core:test
```

```bash
./gradlew :app:testDebugUnitTest
```

```bash
./gradlew :app:assembleDebug
```

- `:core:test` no necesita Android SDK. Cubre el parser (con los fixtures de los PDF reales y con texto sintético), la preparación, las estadísticas, las cantidades y la copia de seguridad.
- `:app:testDebugUnitTest` usa Robolectric con Room en memoria: DAOs, mapeo de entidades a modelos y la importación de copia como transacción. La primera vez descarga el entorno de Robolectric.
- Test instrumentado (necesita un móvil o emulador conectado): compara la extracción de PdfBox-Android con los fixtures.

```bash
./gradlew :app:connectedDebugAndroidTest
```

Para regenerar los fixtures tras cambiar un PDF de `samples/`:

```bash
./gradlew :tools:run
```

Con la variable de entorno `MENUFOGON_PRINT=1` también imprime la tabla de platos leída.

## Firma (desarrollador, una sola vez)

La versión de publicación (`release`) va firmada. La firma nunca se guarda en el repositorio.

1. Instala el JDK 17 si no lo tienes (el script lo comprueba y te dice cómo).
2. Ejecuta el script de tu sistema desde la raíz del proyecto:
   - macOS o Linux: `bash scripts/firma/crear-firma.sh`
   - Windows: `powershell -ExecutionPolicy Bypass -File scripts\firma\crear-firma.ps1`
3. El script crea `~/MenuCal-firma/` con el keystore `menucal.jks`, las contraseñas aleatorias en `LEER-ANTES.txt` y un `keystore.properties`. Si ya existe una firma, no la sobrescribe.
4. **Guarda copias del keystore y de las contraseñas en dos sitios seguros** (por ejemplo, tu Drive personal y un gestor de contraseñas). Si se pierden, no se pueden publicar actualizaciones que se instalen sobre la app actual.
5. Secretos de GitHub: si tienes GitHub CLI (`gh`) instalado y con sesión iniciada, el script sube solo `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` y `ANDROID_KEY_PASSWORD`. Si no, te indica cómo añadirlos a mano en *Settings > Secrets and variables > Actions*.
6. Para firmar en tu ordenador, copia `~/MenuCal-firma/keystore.properties` a la raíz del proyecto (git lo ignora) y ejecuta `./gradlew :app:assembleRelease`.

`assembleRelease` falla con un mensaje claro si no hay firma. `assembleDebug` no la necesita.

El keystore usa formato PKCS12, que exige la misma contraseña para el almacén y para la clave; por eso los dos secretos de contraseña tienen el mismo valor.

## Publicación automática

`.github/workflows/apk.yml` se ejecuta con cada `push` a `main` y a mano (`workflow_dispatch`), nunca con `pull_request`. Pasa los tests, compila la versión firmada con `versionCode = github.run_number` y publica un GitHub Release marcado como última versión con el archivo `MenuCal.apk`. El enlace fijo de descarga es:

`https://github.com/lazarorgb-lgtm/MenuFogon/releases/latest/download/MenuCal.apk`

## Supuestos

- Cada día se registra cuántos menús de cada tipo se sirven, y puede ser 0.
- «FESTIVO» en el primero significa que ese día no hay primero para ese tipo.
- Un solo usuario, sin cuentas ni sincronización. La copia de seguridad es manual.
- El tipo de menú lo elige siempre el usuario al cargar el archivo. El nombre del archivo (NORMAL, DIB, EEI) solo se usa como sugerencia al recibir un PDF con «Compartir»; el título del documento no se usa nunca.
- Un día guardado con 0 comensales no cuenta como «día con servicio» en las estadísticas. La media diaria se calcula sobre los días con servicio.
- Solo se guarda registro de los tipos que tienen menú publicado ese día.
- Los alérgenos se asignan al plato que tienen encima en el PDF, tal como aparecen, aunque el original parezca tener alguno descolocado.
- Zona horaria Europe/Madrid y semanas ISO (lunes a domingo).

## Límites conocidos

- El parser está ajustado al formato de rejilla de los PDF de `samples/`. Un PDF con otra maquetación puede leerse mal; para eso están la revisión antes de guardar y la introducción a mano.
- Las páginas sin capa de texto se leen con reconocimiento de texto (ML Kit), menos fiable. La app lo avisa en la revisión.
- Fusionar dos platos afecta a los menús publicados, no a los registros ya guardados: cada registro conserva su copia de nombres, y las estadísticas agrupan por ese nombre.
- Reimportar un PDF no borra de la lista de platos los que dejen de usarse.
- La base de datos está en su versión 1 y todavía no hay migraciones que escribir. El esquema está exportado en `app/schemas/` y la migración destructiva no está activada: un cambio de esquema futuro exige añadir su migración en `AppDatabase.MIGRATIONS`.
- La versión release no se minimiza (`isMinifyEnabled = false`) para no romper Room ni PdfBox. El APK ocupa unos 60 MB, sobre todo por ML Kit y PdfBox.
- La fecha de «Última copia» se guarda en el móvil, no dentro de la copia.
