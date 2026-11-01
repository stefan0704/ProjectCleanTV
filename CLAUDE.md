# ProjectCleanTV

Proyecto personal para el Android TV de Stefan (Tesla 4K FF = Skyworth SW6H, Google TV / Android 11). Se responde en español.
Basado en CleanYourTV (MIT); el repo solo contiene lo propio: `launcher/`, `themes/`, `tele/`, `tools/`. Rama de trabajo: `tele` (en GitHub, `main` de stefan0704/ProjectCleanTV).
Guardar = commit y push a `origin`. El repo original no se toca.

## Reparto de trabajo (ahorro de tokens)
- Sesión principal (Opus): decisiones de diseño, arquitectura, código delicado, hablar con el usuario.
- `android-dev` (Sonnet): implementar cambios concretos en el launcher (Java/Gradle).
- `tv-operator` (Haiku): comandos ADB mecánicos, capturas, listados de paquetes, instalar APK.
- `reviewer` (Sonnet): revisar el diff antes de instalar en la tele.
- Delegar solo con un brief cerrado (archivos, qué hacer, cómo verificar). Nada de delegar tareas de 1–3 llamadas.

## Reglas de comportamiento
- Pensar antes de codificar: decir supuestos, preguntar si hay ambigüedad.
- Simplicidad: el mínimo código que resuelve lo pedido, nada especulativo.
- Cambios quirúrgicos: tocar solo lo necesario, respetar el estilo del código existente.
- Objetivo verificable: cada cambio termina con build OK y/o captura de la tele. Mantener el APK ligero.

## Seguridad en la tele
- Nunca desinstalar: solo `pm disable-user --user 0` vía `tools/disable.sh`, con backup antes; todo se deshace con `tools/restore.sh`.
- No desactivar lo que la tele necesita: app de TV y entradas HDMI/sintonizador, mando y Bluetooth, teclado, Play Store y Play Services, ni paquetes base de Android (`com.android.*` esenciales, `android`, systemui, settings, tv.settings). `tools/disable.sh` ya rechaza los protegidos.
- Nunca arrancar paquetes de fábrica o test (`*factory*`, `com.cvte.tv.hkcfac`, etc.).
- Se conservan: Asistente (katniss), Chromecast (mediashell), actualizaciones y salvapantallas de Google Fotos (dreamx).
- En pruebas: nada de `am force-stop com.gathin.tvhome` (apaga el servicio de botones del mando), nada de KEYCODE_SLEEP/POWER (ADB no vuelve), nada de OK sobre películas, episodios o apps sin captura previa.
- Nunca subir claves (`trakt.txt`, `tmdb.txt`), backups, capturas, vídeos ni IPs.
