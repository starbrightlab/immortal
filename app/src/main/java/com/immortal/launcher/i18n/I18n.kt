/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher.i18n

import java.util.Locale

/**
 * Immortal i18n translation system. Supports Spanish (`es`) and English (`en`).
 */
object I18n {

  fun isSpanish(userLang: String?): Boolean {
    return when (userLang?.lowercase()) {
      "es" -> true
      "en" -> false
      else -> Locale.getDefault().language.lowercase().startsWith("es")
    }
  }

  fun translate(text: String, userLang: String? = null): String {
    if (!isSpanish(userLang)) return text
    return translateDict(text)
  }

  fun tr(en: String, es: String, userLang: String? = null): String {
    return if (isSpanish(userLang)) es else en
  }

  fun translateHelp(help: String?, userLang: String? = null): String? {
    if (help == null) return null
    if (!isSpanish(userLang)) return help
    return translateDict(help)
  }

  fun fitLabel(fit: String, userLang: String? = null): String {
    if (!isSpanish(userLang)) {
      return if (fit == "fit") "Fit" else "Fill"
    }
    return if (fit == "fit") "Ajustar" else "Rellenar"
  }

  fun rangeLabel(range: String, userLang: String? = null): String {
    if (!isSpanish(userLang)) {
      return when (range) {
        "day" -> "Day"
        "3day" -> "3 days"
        "week" -> "Week"
        "agenda" -> "Agenda"
        else -> range
      }
    }
    return when (range) {
      "day" -> "Hoy"
      "3day" -> "3 días"
      "week" -> "Semana"
      "agenda" -> "Agenda"
      else -> range
    }
  }

  fun feedLabel(feed: String, userLang: String? = null): String {
    if (!isSpanish(userLang)) {
      return when (feed) {
        "met" -> "The Met — art"
        "calm" -> "Calming landscapes"
        "art" -> "Classic art"
        "architecture" -> "Architecture"
        "space" -> "Deep space"
        "minimal" -> "Minimalist patterns"
        else -> "Calming landscapes"
      }
    }
    return when (feed) {
      "met" -> "El Met — Arte"
      "calm" -> "Paisajes relajantes"
      "art" -> "Arte clásico"
      "architecture" -> "Arquitectura"
      "space" -> "Espacio profundo"
      "minimal" -> "Patrones minimalistas"
      else -> "Paisajes relajantes"
    }
  }

  fun soundscapeLabel(soundscape: String, userLang: String? = null): String {
    if (!isSpanish(userLang)) {
      return when (soundscape) {
        "ocean", "waves" -> "Ocean waves"
        "rain" -> "Rain"
        "forest" -> "Forest birds"
        "fireplace" -> "Cozy fireplace"
        "stream" -> "Babbling stream"
        "wind" -> "Night wind"
        "white_noise" -> "White noise"
        "pink_noise" -> "Pink noise"
        "brown_noise" -> "Brown noise"
        "chimes" -> "Wind chimes"
        else -> "Off"
      }
    }
    return when (soundscape) {
      "ocean", "waves" -> "Olas del mar"
      "rain" -> "Lluvia"
      "forest" -> "Pájaros en el bosque"
      "fireplace" -> "Chimenea acogedora"
      "stream" -> "Arroyo de agua"
      "wind" -> "Viento nocturno"
      "white_noise" -> "Ruido blanco"
      "pink_noise" -> "Ruido rosa"
      "brown_noise" -> "Ruido marrón"
      "chimes" -> "Campanillas de viento"
      else -> "Apagado"
    }
  }

  fun sourceLabel(
      usesImmich: Boolean,
      usesSmb: Boolean,
      usesDav: Boolean,
      usesWebUrl: Boolean,
      usesUrl: Boolean,
      usesFolder: Boolean,
      userLang: String? = null
  ): String {
    if (!isSpanish(userLang)) {
      return when {
        usesImmich -> "Immich"
        usesSmb -> "Network share"
        usesDav -> "WebDAV"
        usesWebUrl -> "Web page"
        usesUrl -> "Shared album"
        usesFolder -> "Local folder"
        else -> "Built-in photos"
      }
    }
    return when {
      usesImmich -> "Servidor Immich"
      usesSmb -> "Carpeta de red (NAS)"
      usesDav -> "WebDAV"
      usesWebUrl -> "Página web"
      usesUrl -> "Álbum compartido"
      usesFolder -> "Carpeta local"
      else -> "Fotos de Immortal"
    }
  }

  private fun translateDict(text: String): String {
    return when (text) {
      // General Navigation & Headers
      "Settings" -> "Ajustes"
      "Immortal Settings" -> "Ajustes de Immortal"
      "Screensaver" -> "Salvapantallas"
      "Calendar" -> "Calendario"
      "Immortal" -> "Lanzador Immortal"
      "MQTT" -> "Integración MQTT"
      "Tools" -> "Herramientas"
      "Close" -> "Cerrar"
      "Save" -> "Guardar"
      "Back" -> "Volver"
      "Next" -> "Siguiente"
      "Skip" -> "Omitir"
      "Done" -> "Listo"
      "Enabled" -> "Activado"
      "Disabled" -> "Desactivado"
      "On" -> "Activado"
      "Off" -> "Desactivado"
      "‹  Back" -> "‹  Volver"
      "Opens " -> "Abre "

      // Wallpaper
      "Wallpaper" -> "Fondo de pantalla"
      "Background" -> "Fondo"
      "A gradient, a photo, or sync with your screensaver (shown blurred). Tap to choose." -> "Un degradado, una foto o sincronizar con tu salvapantallas (desenfocado). Toca para elegir."
      "Dark" -> "Oscuro"
      "Sky" -> "Cielo"
      "Star field" -> "Campo de estrellas"
      "Film grain" -> "Grano de película"
      "Adds a subtle grain texture over the wallpaper." -> "Añade una textura de grano suave sobre el fondo de pantalla."
      "Midnight" -> "Medianoche"
      "Dusk" -> "Anochecer"
      "Ocean" -> "Océano"
      "Ember" -> "Ascua"
      "Aurora" -> "Aurora"
      "Wilderness" -> "Naturaleza"
      "Denali" -> "Denali"
      "Yosemite" -> "Yosemite"

      // World Clock Cities
      "Cupertino" -> "Cupertino"
      "Denver" -> "Denver"
      "New York" -> "Nueva York"
      "São Paulo" -> "São Paulo"
      "London" -> "Londres"
      "Paris" -> "París"
      "Berlin" -> "Berlín"
      "Cape Town" -> "Ciudad del Cabo"
      "Dubai" -> "Dubái"
      "Mumbai" -> "Mumbai"
      "Singapore" -> "Singapur"
      "Tokyo" -> "Tokio"
      "Sydney" -> "Sídney"
      "Choose locations for the World Clock widget. The first four (in the order you pick them) are shown." -> "Elige las ciudades para el widget de Reloj Mundial. Se muestran las cuatro primeras en el orden elegido."
      "Tip: the widget shows up to four clocks. Re-pick a city to move it to the end of the order." -> "Consejo: el widget muestra hasta cuatro relojes. Vuelve a seleccionar una ciudad para moverla al final."

      // Multi-room Audio Subpage
      "Join this Portal to the same Snapcast group as your other rooms, then point it at your Music Assistant server. The synced audio is played by the Snapcast app." -> "Une este Portal al mismo grupo Snapcast que tus otras habitaciones y vincúlalo a tu servidor Music Assistant."
      "Sends play/pause/skip to Music Assistant, and shows now-playing for AirPlay sources (which don't carry it over Snapcast). Library/radio now-playing works without it." -> "Envía reproducir/pausar/siguiente a Music Assistant y muestra información de reproducción."
      "Music Assistant username" -> "Usuario de Music Assistant"
      "Music Assistant password" -> "Contraseña de Music Assistant"
      "Sign in" -> "Iniciar sesión"
      "Starting…" -> "Iniciando…"
      "Connecting…" -> "Conectando…"
      "Connected" -> "Conectado"
      "Disconnected" -> "Desconectado"

      // Home Assistant MQTT Subpage
      "Publish this Portal to Home Assistant as auto-discovered entities — presence, screen, battery, now-playing, and controls — over your MQTT broker." -> "Publica este Portal en Home Assistant como entidades detectadas automáticamente (presencia, pantalla, batería, reproductor y controles) a través de tu servidor MQTT."
      "Setting it up" -> "Configuración"
      "In Home Assistant, add the Mosquitto broker add-on (Settings → Add-ons) and the MQTT integration. New to MQTT? See home-assistant.io/integrations/mqtt" -> "1. En Home Assistant, añade el complemento Mosquitto broker (Ajustes → Complementos) y la integración MQTT."
      "Turn on the toggle below and enter your broker's address (and login, if any)." -> "2. Activa el interruptor inferior e introduce la dirección de tu servidor MQTT (y credenciales)."
      "This Portal appears automatically under Settings → Devices as a new MQTT device — no YAML needed." -> "3. Este Portal aparecerá automáticamente en Ajustes → Dispositivos como un nuevo dispositivo MQTT."
      "Publish to Home Assistant" -> "Publicar en Home Assistant"
      "Exposes this Portal's state and controls as Home Assistant entities over MQTT." -> "Expone el estado y los controles de este Portal como entidades de Home Assistant."
      "MQTT broker IP / host" -> "IP / Dirección del servidor MQTT"
      "Port (default 1883, or 8883 for TLS)" -> "Puerto (por defecto 1883, o 8883 para TLS)"
      "Username (optional)" -> "Usuario (opcional)"
      "Password (optional)" -> "Contraseña (opcional)"
      "Use TLS / SSL" -> "Usar TLS / SSL"
      "Encrypt the connection (e.g. a broker behind a reverse proxy on port 8883)." -> "Cifra la conexión (ej. servidor tras un proxy inverso en el puerto 8883)."
      "Validate certificate" -> "Validar certificado"
      "Verify the broker's certificate and hostname. Turn off only for a self-signed broker on a trusted network." -> "Verifica el certificado y nombre de host. Desactívalo solo para certificados autofirmados."
      "Connects to a broker on your LAN over plain MQTT or TLS. Your Portal shows up in Home Assistant automatically as a device with presence, screen, battery, now-playing and a few controls — no configuration.yaml editing." -> "Se conecta a un servidor MQTT en tu red local. Tu Portal aparece automáticamente en Home Assistant sin editar archivos YAML."

      // Remote Pair Activity & UI
      "Use a phone or tablet on the same Wi-Fi as a remote for this Portal — nav buttons and an app launcher, no extra app to install." -> "Usa un teléfono o tablet en tu misma red Wi-Fi como mando a distancia: botones de navegación y lanzador de apps sin instalar nada."
      "Connect your Portal to Wi-Fi to use the remote." -> "Conecta tu Portal a la red Wi-Fi para usar el mando a distancia."
      "Scan with your phone camera" -> "Escanea con la cámara de tu teléfono"
      "Code expires in a few minutes" -> "El código caduca en unos minutos"

      // Digital Clock Settings Activity
      "Choose the digital clock and how it looks on the screensaver." -> "Elige la apariencia del reloj digital en el salvapantallas."
      "See the clock full-screen with your current settings." -> "Mira el reloj a pantalla completa con tus ajustes actuales."

      // Sleep Settings Activity
      "Sleep Timer" -> "Temporizador de apagado"
      "Set the timer, then start the countdown." -> "Configura el temporizador y luego inicia la cuenta atrás."
      "Enable Sleep Timer below before starting." -> "Activa el temporizador de apagado abajo antes de empezar."

      // Sunrise Settings Activity
      "🌅 Sunrise alarm" -> "🌅 Alarma de amanecer"
      "The screen brightens gradually to wake you, with an optional gentle chime." -> "La pantalla se ilumina progresivamente para despertarte, con un sonido suave opcional."
      "Wake on" -> "Días de alarma"
      "Chime at the end" -> "Sonido al finalizar"
      "Alarm is off." -> "La alarma está desactivada."

      // Start on Boot Subpage
      "Pick which installed apps Immortal relaunches after a reboot." -> "Elige qué aplicaciones instaladas reinicia Immortal tras reiniciar el dispositivo."
      "Loading apps…" -> "Cargando aplicaciones…"
      "No other apps installed yet." -> "No hay otras aplicaciones instaladas."
      "These apps relaunch automatically after a reboot — handy for players like Music Assistant that don't restart themselves." -> "Estas aplicaciones se reinician automáticamente al encender el dispositivo."

      // Device Health Subpage
      "The permissions your Portal was set up with, and what each one powers." -> "Los permisos con los que se configuró tu Portal y qué función activa cada uno."
      "✓  Everything's set up correctly." -> "✓ Todo está configurado correctamente."
      "How to fix" -> "Cómo solucionarlo"
      "Reconnect your Portal to a computer and re-run Immortal setup — it re-grants all of these. (Advanced: re-run provision.sh / provision.ps1 from the provisioning kit.)" -> "Vuelve a conectar tu Portal al ordenador y ejecuta el instalador de Immortal para restaurar todos los permisos."
      "Allow uninstall" -> "Permitir desinstalación"
      "Disabling the screen-off device admin lets Immortal be uninstalled, but it also stops automatic screen-off (screensaver sleep and the Home Assistant control) until you re-run setup. Only do this if you know what you're doing." -> "Desactivar el administrador de pantalla apagada permite desinstalar Immortal, pero detiene el apagado automático."
      "Tap again to confirm — this stops automatic screen-off" -> "Pulsa de nuevo para confirmar (desactiva el apagado automático)"
      "Disable screen-off admin" -> "Desactivar administrador de pantalla apagada"

      // Immortal Navigation Rows & Titles
      "World clock" -> "Reloj mundial"
      "World clock locations" -> "Ubicaciones del reloj mundial"
      "Pick which cities the World Clock widget shows (first four are displayed)." -> "Elige qué ciudades muestra el widget de reloj mundial (se muestran las cuatro primeras)."
      "Multi-room audio" -> "Audio multihabitación"
      "Quick buttons" -> "Botones rápidos"
      "Almanac" -> "Almanaque"
      "Romanian name-days & Orthodox feasts" -> "Santo del día rumano y fiestas ortodoxas"
      "Irish holidays" -> "Festividades irlandesas"
      "Prayer times" -> "Horarios de oración"
      "Sound & input" -> "Sonido y entrada"
      "Touch sounds" -> "Sonidos al tocar"
      "Back gesture" -> "Gesto de volver"
      "Control from your phone" -> "Controlar desde tu teléfono"
      "On — pair a phone as a remote" -> "Activado: vincula un teléfono como mando"
      "Needs the accessibility-based top-bar watch enabled during setup. The switcher shows your recently used apps; tap one to switch." -> "Requiere la supervisión de la barra superior de accesibilidad activada. Muestra tus aplicaciones recientes."
      "Home Assistant" -> "Home Assistant"
      "Remote" -> "Mando a distancia"
      "Sounds" -> "Sonidos"
      "Welcome overlay" -> "Mensaje de bienvenida"
      "Digital clock" -> "Reloj digital"
      "Sleep & idle" -> "Reposo e inactividad"
      "Wake-up light" -> "Luz de amanecer"
      "Start on boot" -> "Iniciar al arrancar"
      "Device" -> "Dispositivo"
      "Chimes & spoken time" -> "Carillón y hora hablada"
      "Hourly chime, spoken time, golden-hour tone, quiet hours" -> "Carillón cada hora, hora hablada, tono de hora dorada y horas silenciosas"
      "Welcome-back greeting" -> "Saludo de bienvenida"
      "A time-of-day greeting when the screensaver starts" -> "Un saludo según el momento del día al activar el salvapantallas"
      "Clock screensaver" -> "Salvapantallas de reloj"
      "Show a large digital clock as the screensaver" -> "Muestra un reloj digital grande como salvapantallas"
      "Screen-off timers" -> "Temporizadores de apagado"
      "Idle timeout and the overnight sleep window" -> "Inactividad y periodo de reposo nocturno"
      "Sunrise alarm" -> "Alarma de amanecer"
      "Brighten the screen gradually at a set time" -> "Aumenta el brillo progresivamente a la hora fijada"

      // Tools Screen & Submenus
      "Extra utilities for your Portal." -> "Utilidades adicionales para tu Portal."
      "Cameras" -> "Cámaras"
      "View a saved RTSP camera feed" -> "Ver transmisión de cámara RTSP guardada"
      "Countdowns" -> "Cuentas atrás"
      "Days until birthdays and events" -> "Días hasta cumpleaños y eventos especiales"
      "Days until birthdays, holidays, and special events." -> "Días hasta cumpleaños, vacaciones y eventos especiales."
      "Lamp" -> "Lámpara"
      "A full-screen warm-white light" -> "Luz cálida a pantalla completa"
      "Bedtime story" -> "Cuento para dormir"
      "Public-domain tales read aloud" -> "Cuentos clásicos leídos en voz alta"
      "Intercom" -> "Intercomunicador"
      "Talk to another Portal on your Wi-Fi" -> "Hablar con otro Portal en tu red Wi-Fi"
      "Talk to other Portals on your home Wi-Fi." -> "Habla con otros dispositivos Portal en tu red Wi-Fi."
      "Timers" -> "Temporizadores"
      "Kitchen timers with a live countdown" -> "Temporizadores de cocina con cuenta atrás"
      "Leave a note" -> "Dejar una nota"
      "A sticky note or a quick voice memo" -> "Una nota adhesiva o nota de voz rápida"
      "Converter" -> "Conversor"
      "Units and currency" -> "Unidades y divisas"
      "ISS passes" -> "Pases de la ISS"
      "When the space station flies over" -> "Cuándo pasa la estación espacial"
      "Aurora outlook" -> "Pronóstico de auroras"
      "Northern-lights chance for your location" -> "Probabilidad de auroras boreales en tu ubicación"
      "Speed test" -> "Test de velocidad"
      "Check your internet speed (Cloudflare)" -> "Comprobar la velocidad de internet (Cloudflare)"

      // Tool Overlays & Sub-activities
      "🛰️ Space station overhead" -> "🛰️ Estación espacial arriba"
      "Finding passes…" -> "Buscando pases…"
      "No passes found. Check the device is online so it can fetch the latest orbit, and that your location is set (it follows the weather tile)." -> "No se encontraron pases. Comprueba que el dispositivo tiene internet y la ubicación configurada."
      "🌌 Aurora outlook" -> "🌌 Pronóstico de auroras"
      "Checking space weather…" -> "Comprobando clima espacial…"
      "⚡ Internet speed test" -> "⚡ Test de velocidad de internet"
      "Tap Start to test your internet speed." -> "Toca Iniciar para probar la velocidad de internet."
      "Testing..." -> "Probando..."
      "Start test" -> "Iniciar test"
      "Download" -> "Descarga"
      "Upload" -> "Subida"
      "Ping" -> "Latencia"
      "⏱️ Timers" -> "⏱️ Temporizadores"
      "Minutes" -> "Minutos"
      "Seconds" -> "Segundos"
      "Start timer" -> "Iniciar temporizador"
      "Pause" -> "Pausar"
      "Resume" -> "Reanudar"
      "Reset" -> "Reiniciar"
      "📝 Quick notes" -> "📝 Notas rápidas"
      "Type a note or tap record for a voice memo." -> "Escribe una nota o toca grabar para una nota de voz."
      "Type a note…" -> "Escribe una nota…"
      "Record voice memo" -> "Grabar nota de voz"
      "Stop recording" -> "Detener grabación"
      "Play voice memo" -> "Reproducir nota de voz"
      "Clear note" -> "Borrar nota"
      "📏 Unit converter" -> "📏 Conversor de unidades"
      "Value" -> "Valor"
      "From" -> "De"
      "To" -> "A"
      "Warm Lamp" -> "Lámpara Cálida"
      "Night Light" -> "Luz Nocturna"
      "Tap to close or use back gesture" -> "Toca para cerrar o usa el gesto de volver"
      "Press & hold to speak" -> "Mantén presionado para hablar"
      "Listening…" -> "Escuchando…"
      "Transmitting…" -> "Transmitiendo…"
      "No other Portals found on LAN" -> "No se encontraron otros Portals en la red LAN"
      "Add countdown" -> "Añadir cuenta atrás"
      "Title" -> "Título"
      "Date" -> "Fecha"

      // Immortal Domain & Sections
      "Weather" -> "El tiempo"
      "Home screen" -> "Pantalla de inicio"
      "Audio" -> "Audio"
      "Clock" -> "Reloj"
      "Weather widget" -> "Widget del tiempo"
      "Now-playing mini-player" -> "Mini-reproductor en cabecera"
      "Temperature unit" -> "Unidad de temperatura"
      "App tile size" -> "Tamaño de iconos"
      "Hide status bar" -> "Ocultar barra de estado"
      "Constrain page width" -> "Limitar ancho de página"
      "Music Assistant port" -> "Puerto de Music Assistant"
      "Music Assistant user" -> "Usuario de Music Assistant"
      "Snapcast host" -> "Servidor Snapcast"
      "Broker host" -> "Dirección del servidor"
      "Broker" -> "Servidor Broker"
      "Security" -> "Seguridad"
      "Use TLS" -> "Usar TLS"
      "App-switcher button" -> "Botón multitarea"
      "Always show" -> "Mostrar siempre"
      "Device name" -> "Nombre del dispositivo"
      "Display duration" -> "Duración de pantalla"
      "Show greeting" -> "Mostrar saludo"
      "Show clock" -> "Mostrar reloj"
      "Show date" -> "Mostrar fecha"
      "Speak greeting" -> "Hablar saludo"
      "Clock style" -> "Estilo del reloj"
      "Appearance" -> "Apariencia"
      "Layout & background" -> "Diseño y fondo"
      "Layout & Background" -> "Diseño y fondo"
      "Photo Frame" -> "Marco de fotos"
      "Display" -> "Pantalla y visibilidad"
      "Clock & Widgets" -> "Reloj y widgets"
      "Position" -> "Posición"
      "Glow" -> "Brillo deslumbrante"
      "Show seconds" -> "Mostrar segundos"
      "Chime at end" -> "Carillón al finalizar"
      "Ramp minutes" -> "Duración de aceleración"
      "Ramp" -> "Aceleración"
      "Spoken time" -> "Hora hablada"
      "Golden-hour tone" -> "Tono de hora dorada"
      "Golden hour" -> "Hora dorada"
      "Sunrise sound" -> "Sonido de amanecer"
      "Ping volume" -> "Volumen de aviso"
      "Quiet hours" -> "Horario silencioso"
      "Quiet from" -> "Silencio desde"
      "Quiet until" -> "Silencio hasta"
      "Hourly chime" -> "Carillón cada hora"
      "Chime sound" -> "Sonido del carillón"
      "Chime volume" -> "Volumen del carillón"
      "Sunrise wake light" -> "Luz de amanecer"
      "Wake time" -> "Hora de alarma"
      "Duration" -> "Duración"
      "Topic prefix" -> "Prefijo de tema"
      "Port" -> "Puerto"
      "Username" -> "Usuario"
      "Password" -> "Contraseña"

      // Screensaver Specs
      "Clock face" -> "Estilo del reloj"
      "Photo source" -> "Fuente de fotos"
      "Open when you tap to exit" -> "Abrir al pulsar la pantalla"
      "Album refresh" -> "Refresco de álbum"
      "Fit" -> "Ajuste de foto"
      "Fill" -> "Rellenar"
      "fit" -> "Ajustar"
      "fill" -> "Rellenar"
      "Photo interval" -> "Intervalo de foto"
      "Shuffle" -> "Orden aleatorio"
      "Play videos" -> "Reproducir vídeos"
      "Crop vertical photos (~20%)" -> "Recortar fotos verticales (~20%)"
      "Store media on this device" -> "Almacenar contenido en este dispositivo"
      "Storage limit" -> "Límite de almacenamiento"
      "Sleep on battery when nobody's around" -> "Apagar en batería si no hay nadie"
      "Show now playing" -> "Mostrar música en reproducción"
      "Reduce screen burn-in" -> "Protección contra marcado de pantalla"
      "Legibility gradient" -> "Gradiente de legibilidad"
      "Power" -> "Alimentación y energía"
      "Idle screen-off" -> "Inactividad antes de apagar"
      "Overnight screen-off" -> "Reposo nocturno"
      "Off from" -> "Apagado desde"
      "Off until" -> "Apagado hasta"
      "Show a dim night clock" -> "Mostrar reloj tenue de noche"
      "Photo feed" -> "Colección de fotos"
      "Sleep after" -> "Apagar tras"
      "Pause audio on sleep" -> "Pausar audio al apagar"
      "Close app on sleep" -> "Cerrar app al apagar"
      "Ambient sound" -> "Sonido ambiental"
      "Soundscape volume" -> "Volumen de sonido ambiental"
      "Ambient dashboard" -> "Panel ambiental"
      "Gesture wave to wake" -> "Despertar al pasar la mano"
      "Welcome screen" -> "Pantalla de bienvenida"
      "Show clock & date" -> "Mostrar reloj y fecha"

      // Options
      "Standard" -> "Estándar"
      "Large" -> "Grande"
      "XL" -> "Extra Grande (XL)"
      "Off" -> "Apagado"
      "Hourly" -> "Por horas"
      "Daily" -> "Diario (7 días)"
      "12-hour" -> "12 horas"
      "24-hour" -> "24 horas"
      "Auto" -> "Automático"
      "Fahrenheit" -> "Fahrenheit (°F)"
      "Celsius" -> "Celsius (°C)"
      "System default / Idioma del sistema" -> "Idioma del sistema"
      "Always on" -> "Siempre encendido"
      "Follow presence" -> "Seguir presencia"
      "Immortal launcher" -> "Lanzador Immortal"
      "Classic" -> "Clásico"
      "Flip" -> "Pestañas / Flip"
      "Bold" -> "Negrita"
      "Neon" -> "Neón"
      "Segment" -> "Digital 7 Segmentos"
      "Analog" -> "Analógico"
      "Light" -> "Fina / Light"
      "Normal" -> "Normal"
      "Mono" -> "Monoespaciada"
      "Serif" -> "Serif"
      "LED" -> "LED"
      "Digital" -> "Digital"
      "Tech" -> "Tecnológico"
      "Small" -> "Pequeño"
      "Medium" -> "Mediano"
      "Center" -> "Centrado"
      "Top" -> "Arriba"
      "Bottom" -> "Abajo"
      "Minimal" -> "Mínimo"
      "Black" -> "Fondo negro"
      "Gradient" -> "Degradado"
      "None" -> "Ninguno"
      "Soft" -> "Suave"
      "Strong" -> "Intenso"
      "Morning" -> "Mañanero"
      "Rooster" -> "Canto de gallo"

      // Helps
      "Choose language for Immortal / Elige el idioma para la aplicación." -> "Elige el idioma para la aplicación."
      "Auto follows your Portal's language & region setting." -> "Sigue automáticamente el idioma y región del Portal."
      "Large is closer to the stock Portal launcher." -> "El tamaño grande es más cercano al lanzador original del Portal."
      "Show a forecast below your apps. Off by default." -> "Muestra el pronóstico del tiempo debajo de tus aplicaciones. Desactivado por defecto."
      "Applies to the home screen, screensaver and forecast. Auto follows your Portal's system setting." -> "Aplica a la pantalla de inicio, salvapantallas y previsión. Automático sigue el sistema."
      "Show the current track, cover art and controls in the header while music is playing." -> "Muestra la canción actual, la portada y los controles en la cabecera mientras suena música."
      "Hidden by default for a cleaner full-screen look. Swipe down from the top to reveal it briefly." -> "Oculto por defecto para una vista limpia a pantalla completa. Desliza hacia abajo desde arriba para mostrarla."
      "Cap the home screen width on large landscape displays instead of filling the whole panel. Off by default." -> "Limita el ancho en pantallas panorámicas grandes (Portal+) en lugar de llenar todo el panel. Desactivado por defecto."
      "Music Assistant's web server port — 8095 by default." -> "Puerto del servidor web de Music Assistant (8095 por defecto)."
      "A centered button at the top that opens your recent apps to switch between them." -> "Un botón centrado en la parte superior para cambiar rápidamente entre aplicaciones recientes."
      "On: always visible. Off: only while the system top bar is revealed." -> "Activado: siempre visible. Desactivado: solo al deslizar la barra superior del sistema."
      "Shown in the phone remote and in Home Assistant." -> "Se muestra en el mando a distancia del teléfono y en Home Assistant."
      "A soft chime on the hour." -> "Un suave sonido a cada hora en punto."
      "Spoken time on the hour (\"It's three o'clock\"), via TTS." -> "Anuncia la hora hablada en punto mediante voz sintética."
      "A sound at sunrise and sunset." -> "Un tono especial al amanecer y al atardecer."
      "Ring volume for \"ping the other room\" - louder by default since it's a doorbell." -> "Volumen del aviso para otra habitación (más alto por defecto como un timbre)."
      "Silence all cues inside a nightly window." -> "Silencia todas las señales sonoras durante el horario nocturno."
      "Wake to a gradual screen-brightening ramp, optionally finishing with a chime." -> "Despierta con un aumento progresivo de luz en pantalla y un carillón suave al final."
      "How long the screen takes to brighten from ember to full daylight." -> "Tiempo que tarda la pantalla en pasar de baja luz a brillo máximo."
      "Finish the ramp with a soft chime crescendo." -> "Finaliza el incremento de luz con un sonido suave en crescendo."
      "Use a large clock instead of the photo frame screensaver." -> "Muestra un reloj grande como salvapantallas en lugar del marco de fotos."
      "How long the welcome overlay shows before auto-dismissing." -> "Tiempo que permanece la pantalla de bienvenida antes de ocultarse."
      "Show a time-of-day greeting (\"Good morning\")." -> "Muestra un saludo según el momento del día (\"Buenos días\")."
      "Speak the greeting through Android TTS when the overlay shows." -> "Pronuncia el saludo mediante voz sintética al mostrar la pantalla de bienvenida."
      "Shows a 7-day or hourly forecast below the app grid." -> "Muestra la previsión del tiempo debajo de la rejilla de aplicaciones."
      "Format for the home header, screensaver, and widgets." -> "Formato para la cabecera, salvapantallas y widgets."
      "Immersive mode for a clean wall-frame look." -> "Modo inmersivo para marco de pared sin barras."
      "Shows current playback in the home header." -> "Muestra la canción actual en la cabecera."
      "Centers content on large landscape screens (Portal+)." -> "Centra el contenido en pantallas grandes (Portal+)."
      "A public iCalendar (.ics) link - a Google \"secret iCal\" address or an Apple iCloud public-calendar link. Shows your upcoming events on the frame." -> "Enlace iCalendar (.ics) público de Google o iCloud para mostrar tus eventos en el marco."
      "Wakes the screen with a gentle sunrise effect before your alarm." -> "Enciende la pantalla suavemente con efecto amanecer antes de la alarma."
      "Plays a gentle chime on the hour." -> "Toca una suave melodía al cambiar de hora."
      "Automatically dims and turns off the display after inactivity." -> "Atenúa y apaga la pantalla tras un tiempo de inactividad."
      "Choose how the time looks - the classic corner clock, a big centred clock, or the full-screen flip clock." -> "Elige la apariencia de la hora: esquina clásica, reloj central o reloj flip."
      "Where your photos come from - the built-in feed, your own folder or a shared album, or a self-hosted source like Immich or a NAS." -> "Origen de tus fotos: colección integrada, carpeta propia, álbum compartido o NAS/Immich."
      "Tapping the screensaver wakes the Portal. By default that brings you home to Immortal - or pick an app (like Home Assistant) to drop straight into." -> "Al tocar la pantalla se reactiva el Portal. Elige si volver a Immortal o abrir una app directa."
      "How often to re-fetch the photo list from a network album source." -> "Frecuencia para actualizar la lista de fotos de un álbum en red."
      "Trims ~20% off the top and bottom of portrait photos so they look less tall and narrow." -> "Recorta ~20% superior e inferior de fotos verticales para que no se vean tan estrechas."
      "Downloads each photo and video from your server once, then plays it from this device on every loop instead of fetching it again. Videos are shrunk to fit the screen. The frame loads faster and your server does far less work; it uses some of this device's storage." -> "Guarda fotos y vídeos en el dispositivo para cargarlos al instante sin sobrecargar tu servidor."
      "The most storage the saved copies may use. When it fills up, the items shown longest ago are removed first. Also limited by free space." -> "Límite máximo de memoria. Al llenarse, las fotos más antiguas se eliminan automáticamente."
      "Unplugged, keep showing photos while someone's nearby and sleep when the room empties (saves the battery). Off: the frame stays on, on battery too." -> "Al estar sin cable, muestra fotos solo cuando hay alguien cerca para ahorrar batería."
      "Overlay the current track and cover art on the photo frame while music is playing." -> "Muestra la carátula y título de la canción sobre las fotos mientras suena música."
      "Darken the bottom of the frame so the clock and widgets stay readable over bright photos. Turn off to show photos clean edge-to-edge." -> "Oscurece la parte inferior para leer la hora sobre fotos claras. Desactiva para ver la foto limpia."
      "Follow presence: photos while someone's around, screen off (and multi-room music paused) when the room empties. Always on: a permanent frame on mains power." -> "Seguir presencia: apaga la pantalla si la habitación está vacía. Siempre encendido: pantalla activa continua."
      "After the screensaver shows this long with no touch, the screen turns off; tap to wake. A simple timer - it can't tell whether someone's in the room." -> "Tras este tiempo sin tocar la pantalla, se apaga automáticamente."
      "Keep the screen off (or show a dim night clock) between two times every night." -> "Mantiene la pantalla apagada o con reloj nocturno tenue entre dos horas cada noche."
      "Which online photo feed to use with the built-in source." -> "Colección de fotos en línea para la fuente integrada."
      "Turn the screensaver off after a set time." -> "Apaga el salvapantallas tras el tiempo configurado."
      "Synthesised ambient sound played while the screensaver shows." -> "Sonido ambiental relajante mientras el salvapantallas está activo."

      // Activity Headers & Descriptions
      "Tune how the launcher looks and what it shows." -> "Personaliza el diseño y funciones del lanzador."
      "Show the photo-frame screensaver" -> "Mostrar el salvapantallas de fotos"
      "Turn this off to let your Portal's screen sleep on its own timer (or run your own screensaver). Immortal won't switch it back on." -> "Desactívalo para permitir que la pantalla de tu Portal se apague según su propio temporizador."
      "Preview screensaver" -> "Vista previa del salvapantallas"
      "Photo frame screensaver" -> "Salvapantallas de fotos"
      "Configure photo sources, display style, and energy saving." -> "Configura las fuentes de fotos, el estilo y el ahorro de energía."

      // Sub-screens
      "Choose how the time looks on your screensaver." -> "Elige la apariencia de la hora en tu salvapantallas."
      "Show a clock face" -> "Mostrar el reloj"
      "Photos only — no clock or widgets on the screensaver. The now-playing card still follows its own switch in screensaver settings." -> "Solo fotos: sin reloj ni widgets en el salvapantallas."
      "More faces — and premium layouts — are coming. Your photos keep showing behind the clock; the flip clock takes over the whole screen on its own." -> "Tus fotos continuarán mostrándose detrás del reloj."
      "Clock, date, weather and now-playing in the corners" -> "Reloj, fecha, clima y reproductor en las esquinas"
      "Flip clock" -> "Reloj Flip"
      "The retro split-flap clock, full screen" -> "Reloj retro de pestañas a pantalla completa"
      "Big clock" -> "Reloj grande"
      "A large clock, centred and clean" -> "Un reloj grande, centrado y limpio"
      "A tall condensed clock with the date" -> "Un reloj alto y condensado con la fecha"
      "Just the time, quietly in the corner" -> "Solo la hora, discretamente en la esquina"
      "Preview" -> "Vista previa"
      "Choose where your screensaver photos come from." -> "Elige de dónde provienen las fotos de tu salvapantallas."
      "Set up from your phone" -> "Configurar desde tu teléfono"
      "Pair the phone remote, then enter Immich keys, NAS details, a link, or the calendar from another device on your Wi-Fi." -> "Vincular el teléfono para introducir claves de Immich, datos del NAS o del calendario."
      "Immortal photos" -> "Fotos de Immortal"
      "A calming built-in photo feed (no setup)." -> "Colección de fotos integradas (sin configuración)."
      "My photos & videos" -> "Mis fotos y vídeos"
      "Choose a different folder…" -> "Elegir una carpeta diferente…"
      "Shared album link" -> "Enlace de álbum compartido"
      "Paste a different link…" -> "Pegar un enlace diferente…"
      "Advanced" -> "Avanzado (Servidor propio)"
      "Self-hosted sources — pull from your own server or NAS, or point the screensaver at a web frame like Immich Kiosk. These are on your local network." -> "Fuentes alojadas en red local (Immich, NAS o WebDAV)."
      "Immich server" -> "Servidor Immich"
      "Change Immich server or album…" -> "Cambiar servidor o álbum de Immich…"
      "Network share (NAS)" -> "Carpeta en red (NAS)"
      "Change network share…" -> "Cambiar carpeta de red…"
      "WebDAV folder" -> "Carpeta WebDAV"
      "Change WebDAV folder…" -> "Cambiar carpeta WebDAV…"
      "Open when dismissed" -> "Abrir al pulsar la pantalla"
      "Choose what opens when you tap the screensaver to wake the Portal." -> "Elige qué app se abre al pulsar la pantalla para reactivar el Portal."
      "Open your Home Assistant dashboard on tap." -> "Abre el panel de Home Assistant al pulsar."
      "Dashboard" -> "Panel de control"
      "The path after your Home Assistant address (e.g. lovelace/0). Leave blank to open your default dashboard." -> "Ruta tras la dirección de Home Assistant (ej. lovelace/0). Dejar en blanco para el panel por defecto."
      "Or an app" -> "O una aplicación"
      "Return to your home screen (default)." -> "Volver a la pantalla de inicio (por defecto)."
      "Finding your apps…" -> "Buscando tus aplicaciones…"
      "Calendar link" -> "Enlace de calendario"
      "Paste a private iCalendar (.ics) link from Google Calendar or Apple iCloud. Immortal reads it directly — it can't sign in to your account." -> "Pega un enlace iCalendar (.ics) privado de Google Calendar o Apple iCloud."
      "Examples: Google \"secret address in iCal format\", Apple iCloud public calendar." -> "Ejemplos: Dirección secreta en formato iCal de Google, calendario público de Apple iCloud."
      "That doesn't look like a calendar (.ics) link yet." -> "Aún no parece un enlace de calendario (.ics) válido."
      "Use this calendar" -> "Usar este calendario"
      "Remove calendar" -> "Eliminar calendario"
      "Cancel" -> "Cancelar"
      "How to get a link:" -> "Cómo obtener un enlace:"
      "Paste a public link from iCloud Shared Albums or Google Photos. Make sure it's shared as \"anyone with the link\" — Immortal can't sign in." -> "Pega un enlace público de un Álbum Compartido de iCloud o Google Photos."
      "Examples: iCloud Shared Album, Google Photos shared album." -> "Ejemplos: Álbum compartido de iCloud, Álbum compartido de Google Photos."
      "That doesn't look like a supported share link yet." -> "Aún no parece un enlace de álbum compartido válido."
      "Use this album" -> "Usar este álbum"
      "Pull photos straight from your self-hosted Immich server. Enter its address and an API key (Immich → Account Settings → API Keys)." -> "Obtén fotos directamente de tu servidor Immich. Introduce la dirección y la clave API."
      "API key" -> "Clave API"
      "Connect" -> "Conectar"
      "Disconnect / Clear" -> "Desconectar / Limpiar"

      else -> text
    }
  }
}
