# Guía: dejar una tele como esta

Sirve para reinstalar esta tele o montarlo en otra Android TV / Google TV (Android 8 o superior).
Se puede seguir a mano o pasársela a una IA con acceso a la terminal ("lee GUIA.md de este repo y móntamelo en mi tele").

## Para la IA que siga esta guía

Trabaja sobre la tele real por ADB y pregunta antes de lo que la persona deba decidir. Reglas que no se saltan:

- **Desactivar, nunca desinstalar.** Solo `pm disable-user --user 0` (con `tools/disable.sh` / `disable.ps1`); nada de `pm uninstall`, root ni flasheos. Todo debe poder deshacerse con `tools/restore.sh`.
- **Backup antes del primer cambio:** `pm list packages`, `pm list packages -d`, `getprop` y `settings list global|secure|system` a `backups/<fecha>/`.
- **No tocar lo que la tele necesita:** la app de TV y las entradas HDMI/sintonizador, el menú de fuentes, el mando y Bluetooth, el teclado, Play Store y Play Services, ni los paquetes base de Android (`android`, `com.android.systemui`, `settings`, `tv.settings`, `shell`, `phone`, `providers.*`, instalador y permisos). Si un nombre no dice nada: `dumpsys package <pkg>` y preguntar.
- **Nunca abrir paquetes de fábrica o test** (`*factory*`, `com.cvte.tv.hkcfac`…): en algunas placas activan un modo fábrica irreversible.
- **La lista `tele/debloat.txt` es de una Tesla 4K FF (Skyworth SW6H).** En otra tele, úsala solo como referencia: genera una lista propia con los paquetes que existan allí y pregunta en una sola lista por streaming, Asistente, Chromecast, salvapantallas y actualizaciones.
- **Desactivar por tandas pequeñas** y hacer captura entre tandas (`adb exec-out screencap -p`; en algunas teles hay que quitar lo anterior a `\x89PNG`).
- **Guardia de Home:** el launcher de serie se desactiva solo cuando este ya está instalado y es Home (`dumpsys activity activities | grep mResumedActivity`).
- En pruebas: nada de `am force-stop com.gathin.tvhome` (apaga el servicio de botones del mando) ni de `KEYCODE_SLEEP`/`POWER` (ADB no vuelve). Nada de pulsar OK a ciegas: captura primero.
- No subir nunca claves, backups, capturas ni IPs al repo.

## Qué hace falta

- PC con `adb`, JDK 17 y Android SDK 34 (o Android Studio, que trae ambos).
- La tele con **Opciones de desarrollador → Depuración USB / por red** activada y en la misma red. `adb connect <ip de la tele>:5555` y aceptar el aviso en la tele.
- Para Explorar / Seguir viendo / Listas: cuenta de [Trakt](https://trakt.tv) y una clave de [TMDB](https://www.themoviedb.org/settings/api) (ambas gratis). Kodi con el addon Palantir 3.

## Pasos

### 1. Backup y limpieza

```bash
mkdir -p backups/$(date +%F)   # y guardar ahí los listados del apartado de reglas
TV_HOST=<ip> tools/disable.sh tele/debloat.txt        # Windows: tools\disable.ps1
```

Mientras el launcher nuevo no sea Home, el script se niega a desactivar el de serie (`launcherx`, `setupwraith`): es normal. Se vuelve a pasar la lista tras el paso 2.

Bloquear que las apps de streaming arranquen solas en segundo plano (siguen funcionando al abrirlas):

```bash
for p in $(grep -v '^#' tele/no-background.txt); do adb shell cmd appops set $p RUN_ANY_IN_BACKGROUND ignore; done
```

### 2. Instalar el launcher como pantalla de inicio

```powershell
launcher\build-and-install.ps1 -TvHost <ip> -InputKeyService -RestoreKeyServices
```

(En Linux/Mac: `launcher/build-and-install.sh`.) Compila, instala, lo hace Home y desactiva el launcher de serie.
`-InputKeyService` activa el servicio que hace que el botón de **fuentes** abra la barra lateral de fuentes y el del **engranaje** los ajustes rápidos; `-RestoreKeyServices` lo vuelve a activar solo si se cae.

**Google TV:** además de `launcherx` hay que desactivar `com.google.android.tungsten.setupwraith`, pero antes comprobar `adb shell dumpsys wifi | grep -o 'cname=[^ ]*'`: si la red Wi-Fi guardada es de setupwraith, olvidarla y volver a conectarla desde Ajustes; si no, la tele se queda sin Wi-Fi (y sin ADB). Después, volver a pasar `tele/debloat.txt` como en el paso 1.

### 3. Tema

Editar `themes/minimal-dark/config.json` con lo personal:

| Clave | Qué poner |
|---|---|
| `greeting_name` | El nombre del saludo |
| `latitude`, `longitude` | Ciudad para el tiempo (por defecto Madrid) |
| `dock_apps` | Las 3 apps del panel, por paquete, p. ej. `["org.xbmc.kodi", "org.smarttube.stable", "tv.pluto.android"]` |
| `video_wallpaper` | Nombre de un `.mp4` dentro de la carpeta del tema (vídeo en bucle, 720p, sin audio). Quitar la clave para usar `wallpaper.jpg` |
| `trakt_lists` | Nombres de listas de Trakt a mostrar, separados por comas; vacío = todas |

Los nombres de las fuentes HDMI (PS5, PC…) y su icono salen del nombre que se les dé en **Ajustes de la tele → Entradas**.

```powershell
powershell -ExecutionPolicy Bypass -File tools\theme.ps1 -TvHost <ip> themes\minimal-dark
```

### 4. Trakt y TMDB

1. En trakt.tv → Settings → Your API Apps → New: cualquier nombre y como redirect URI `http://localhost`. Copiar el **Client ID**.
2. Pasarlo a la tele y leer el código de activación:

   ```bash
   adb shell am broadcast -n com.gathin.tvhome/.TraktReceiver -a com.gathin.tvhome.TRAKT_CONFIG --es client_id <CLIENT_ID>
   adb logcat -d -s TvHomeTrakt
   ```

   Abrir trakt.tv/activate, meter el código y autorizar. Comprobar con `-a com.gathin.tvhome.TRAKT_STATUS`.
3. Clave de TMDB (la "API Key", 32 caracteres):

   ```bash
   adb shell am broadcast -n com.gathin.tvhome/.TraktReceiver -a com.gathin.tvhome.TMDB_CONFIG --es key <TMDB_KEY>
   ```

Guardar las claves en `trakt.txt` / `tmdb.txt` en la raíz (están en `.gitignore`, no se suben).

### 5. Kodi

- Instalar Kodi y el addon **Palantir 3**, y vincular Trakt dentro de Palantir.
- Kodi → Ajustes → Servicios → Control: activar **Permitir control remoto desde aplicaciones en este sistema**. El launcher habla con Kodi por el puerto 9090 de la propia tele.

Al pulsar una película o un capítulo, el launcher abre Kodi, espera a que termine la intro de Palantir y lo reproduce. Una serie abre el capítulo que toca; con pulsación larga se abre su pantalla de temporadas.

### 6. Comprobar y cerrar

- Reiniciar la tele (`adb reboot`) y comprobar que arranca en el launcher, que los botones de fuentes y engranaje funcionan y que Explorar se llena tras unos segundos.
- Si no se va a seguir trabajando por ADB, desactivar la depuración por red en Opciones de desarrollador.

## Deshacer

- Reactivar lo desactivado: `TV_HOST=<ip> tools/restore.sh tele/debloat.txt <launcher de serie>`.
- Volver a dejar que una app arranque en segundo plano: `adb shell cmd appops set <pkg> RUN_ANY_IN_BACKGROUND allow`.
- Mostrar de nuevo los títulos ocultados a mano: `adb shell am broadcast -n com.gathin.tvhome/.TraktReceiver -a com.gathin.tvhome.HIDDEN_CLEAR`.
