package com.dashmate.dashmate

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.KeyEvent
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.core.content.ContextCompat
import com.dashmate.dashmate.deck.CalendarManager
import com.dashmate.dashmate.deck.LocalizationManager
import com.dashmate.dashmate.deck.LyricsFetcher
import com.dashmate.dashmate.deck.ParkingManager
import com.dashmate.dashmate.deck.PrayerTimesManager
import com.dashmate.dashmate.deck.WeatherManager
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

class MainActivity : FlutterActivity() {

    private val CHANNEL_LEGACY = "com.dashmate.dashmate/channel"
    private val REQ_LOCATION = 4107
    private val CH_CAR_BROWSER = "drivedeck/car_browser"
    private val CH_CAR_STATE = "drivedeck/car_state"
    /** The owner's name from the first-run setup (dd_car_browser prefs). */
    private val USER_NAME_KEY = "user_name"
    private val CH_NOW_PLAYING = "drivedeck/now_playing"
    private val CH_MEDIA_CONTROL = "drivedeck/media_control"
    private val CH_MEDIA_STORE = "drivedeck/media_store"
    private val CH_CALENDAR = "drivedeck/calendar"
    private val CH_PERMISSIONS = "drivedeck/permissions"
    private val CH_WELCOME = "drivedeck/welcome"
    private val PREFS_BROWSER = "dd_car_browser"

    private val scope = CoroutineScope(Dispatchers.Main)

    private var flutterChannel: MethodChannel? = null
    private var carStateEventSink: EventChannel.EventSink? = null
    private var nowPlayingEventSink: EventChannel.EventSink? = null
    private var tts: TextToSpeech? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        com.dashmate.dashmate.deck.PrayerAlertManager.scheduleAsync(this)
        val messenger = flutterEngine.dartExecutor.binaryMessenger

        val legacyChan = MethodChannel(messenger, CHANNEL_LEGACY)
        flutterChannel = legacyChan

        try {
            tts = TextToSpeech(this) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.setLanguage(Locale.getDefault())
                }
            }
        } catch (e: Exception) {
        }

        try {
            startForegroundService(Intent(this, DashMateMediaCardService::class.java))
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed starting DashMateMediaCardService: ${e.message}")
        }

        val browserChan = MethodChannel(messenger, CH_CAR_BROWSER)
        MethodChannel(messenger, CH_CAR_STATE)
        val mediaControlChan = MethodChannel(messenger, CH_MEDIA_CONTROL)
        MethodChannel(messenger, CH_MEDIA_STORE)
        val calendarChan = MethodChannel(messenger, CH_CALENDAR)
        val permissionsChan = MethodChannel(messenger, CH_PERMISSIONS)
        val welcomeChan = MethodChannel(messenger, CH_WELCOME)

        EventChannel(messenger, "$CH_CAR_STATE/stream").setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                carStateEventSink = events
                events?.success(
                    mapOf(
                        "connected" to true,
                        "connectionType" to "android_auto",
                        "carApiLevel" to 7,
                        "scr_w" to 1190,
                        "scr_h" to 480,
                        "scr_dpi" to 160,
                    )
                )
            }

            override fun onCancel(arguments: Any?) {
                carStateEventSink = null
            }
        })

        EventChannel(messenger, CH_NOW_PLAYING).setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                nowPlayingEventSink = events
                events?.success(nowPlayingMap())
            }

            override fun onCancel(arguments: Any?) {
                nowPlayingEventSink = null
            }
        })

        DashMateStateBridge.addListener(object : DashMateStateBridge.StateListener {
            override fun onStateUpdated(stateJson: String) {
                runOnUiThread {
                    flutterChannel?.invokeMethod("onStateUpdate", stateJson)
                    nowPlayingEventSink?.success(nowPlayingMap())
                }
            }

            override fun onSwitchPage(page: String) {}

            override fun onExecuteScript(script: String) {}
        })

        browserChan.setMethodCallHandler { call, result -> handleBrowser(call, result) }
        mediaControlChan.setMethodCallHandler { call, result -> handleMediaControl(call, result) }
        calendarChan.setMethodCallHandler { call, result ->
            if (call.method == "getEvents") {
                val calObj = CalendarManager.getUpcomingEvents(this)
                result.success(calObj.toString())
            } else {
                result.notImplemented()
            }
        }
        permissionsChan.setMethodCallHandler { call, result -> handlePermissions(call, result) }
        welcomeChan.setMethodCallHandler { call, result ->
            if (call.method == "playChime") {
                DashMateStateBridge.playWelcomeChime(this)
                result.success(true)
            } else {
                result.notImplemented()
            }
        }
        legacyChan.setMethodCallHandler { call, result -> handleLegacy(call, result) }
    }

    private fun nowPlayingMap(): Map<String, Any?> = mapOf(
        "title" to DashMateStateBridge.songTitle,
        "artist" to DashMateStateBridge.songArtist,
        "playing" to DashMateStateBridge.isPlaying,
    )

    private fun browserPrefs(): SharedPreferences = getSharedPreferences(PREFS_BROWSER, Context.MODE_PRIVATE)

    private fun dispatchMediaKey(keyCode: Int) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private fun togglePlayPause() {
        dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        DashMateStateBridge.isPlaying = !DashMateStateBridge.isPlaying
        DashMateStateBridge.notifyStateChanged(this)
    }

    private fun jsonArrayToList(json: String?): List<String> {
        val arr = JSONArray(json)
        val list = ArrayList<String>()
        for (i in 0 until arr.length()) list.add(arr.getString(i))
        return list
    }

    // ---------------------------------------------------------------- drivedeck/car_browser

    private fun handleBrowser(call: MethodCall, result: MethodChannel.Result) {
        val sp = browserPrefs()
        when (call.method) {
            "setBookmarks" -> {
                val bm = call.argument<String>("bookmarks") ?: "[]"
                sp.edit().putString("bookmarks", bm).apply()
                result.success(true)
            }
            "setNavScale" -> {
                val scale = call.argument<Double>("scale") ?: 1.0
                sp.edit().putFloat("nav_scale", scale.toFloat()).apply()
                result.success(true)
            }
            "setNavStyle" -> {
                val opacity = call.argument<Double>("opacity") ?: 1.0
                val color = call.argument<String>("color") ?: "#5EF2D6"
                sp.edit().putFloat("nav_opacity", opacity.toFloat()).putString("nav_color", color).apply()
                result.success(true)
            }
            "setMenuItems" -> {
                val items = call.argument<List<String>>("items")
                    ?: listOf("home", "lyrics", "prayer", "browse", "parking")
                sp.edit().putString("menu_items", JSONArray(items).toString()).apply()
                result.success(true)
            }
            "sendToCar" -> {
                // Opens the car's browser on this address (the car screen takes it from pendingWebUrl).
                val url = call.argument<String>("url") ?: ""
                if (url.isNotBlank()) {
                    DashMateStateBridge.pendingWebUrl = url
                    DashMateStateBridge.setPage("browse")
                }
                result.success(true)
            }
            "carInfo" -> {
                result.success(
                    mapOf(
                        "scr_name" to "Google Desktop Head Unit",
                        "scr_w" to 1190,
                        "scr_h" to 480,
                        "scr_dpi" to 160,
                    )
                )
            }
            "deviceId" -> {
                val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "dashmate"
                val sha = MessageDigest.getInstance("SHA-256").digest(androidId.toByteArray(Charsets.UTF_8))
                val hex = sha.joinToString("") { String.format("%02x", it) }
                result.success("autodeck-trial:$hex")
            }
            "clearBrowserData" -> {
                WebStorage.getInstance().deleteAllData()
                CookieManager.getInstance().removeAllCookies(null)
                result.success(true)
            }
            "setShowPrayer" -> {
                val show = call.argument<Boolean>("show_prayer") ?: true
                sp.edit().putBoolean("show_prayer", show).apply()
                DashMateStateBridge.notifyStateChanged(this)
                result.success(true)
            }
            "setCarTemplate" -> {
                val tmpl = call.argument<String>("template") ?: "nav"
                sp.edit().putString("car_template", tmpl).apply()
                result.success(true)
            }
            "setTheme" -> {
                val mode = call.argument<String>("mode") ?: "dark"
                sp.edit().putString("theme", mode).apply()
                result.success(true)
            }
            "appVersion" -> {
                // Real version from the build (pubspec version → versionName/versionCode).
                val info = packageManager.getPackageInfo(packageName, 0)
                @Suppress("DEPRECATION")
                val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
                result.success("${info.versionName} (build $code)")
            }
            "getShowPrayer" -> result.success(sp.getBoolean("show_prayer", true))
            "getBookmarks" -> {
                result.success(sp.getString("bookmarks", "[]"))
            }
            else -> result.notImplemented()
        }
    }

    // ---------------------------------------------------------------- drivedeck/media_control

    // RECONSTRUCTED: jadx failed to decompile this handler; rebuilt from the instruction dump
    // ("toggle" is an alias of "playPause").
    private fun handleMediaControl(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "next" -> {
                dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
                result.success(true)
            }
            "previous" -> {
                dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                result.success(true)
            }
            "playPause", "toggle" -> {
                togglePlayPause()
                result.success(true)
            }
            else -> result.notImplemented()
        }
    }

    // ---------------------------------------------------------------- drivedeck/permissions

    private fun handlePermissions(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "checkCalendar" -> {
                val enabled = ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.READ_CALENDAR
                ) == PackageManager.PERMISSION_GRANTED
                result.success(enabled)
            }
            "openNotificationSettings" -> {
                openNotificationListenerSettings()
                result.success(true)
            }
            "checkNotifications" -> {
                val listeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
                val enabled = listeners != null && listeners.contains(packageName)
                result.success(enabled)
            }
            else -> result.notImplemented()
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Freshest last-known fix from any provider, or null. */
    @android.annotation.SuppressLint("MissingPermission")
    private fun lastKnownLocation(): android.location.Location? {
        val lm = getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager ?: return null
        return try {
            lm.getProviders(true)
                .mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (e: SecurityException) { null } }
                .maxByOrNull { it.time }
        } catch (e: Exception) {
            null
        }
    }

    private fun openNotificationListenerSettings() {
        val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
    }

    // ---------------------------------------------------------------- com.dashmate.dashmate/channel

    private fun handleLegacy(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "getPrayerAlerts" -> {
                val st = com.dashmate.dashmate.deck.PrayerAlertManager.settings(this)
                result.success(mapOf("azan" to st.azan, "remind" to st.remind, "minutes" to st.minutes))
            }
            "setPrayerAlerts" -> {
                com.dashmate.dashmate.deck.PrayerAlertManager.saveSettings(
                    this,
                    call.argument<Boolean>("azan") ?: true,
                    call.argument<Boolean>("remind") ?: true,
                    call.argument<Int>("minutes") ?: 10,
                )
                result.success(true)
            }
            "getAutoPark" -> result.success(com.dashmate.dashmate.deck.ParkingAutoSaver.isEnabled(this))
            "setAutoPark" -> {
                com.dashmate.dashmate.deck.ParkingAutoSaver.setEnabled(this, call.argument<Boolean>("enabled") ?: true)
                result.success(true)
            }
            "getFootballPrefs" -> {
                val sp = browserPrefs()
                val compsJson = sp.getString("football_comps", "[\"PL\", \"CL\"]")
                val teamsJson = sp.getString("football_teams", "[\"Arsenal\", \"Liverpool\"]")
                val key = sp.getString("football_api_key", "")
                val compsList = ArrayList<String>()
                val teamsList = ArrayList<String>()
                try {
                    val arr1 = JSONArray(compsJson)
                    for (i in 0 until arr1.length()) compsList.add(arr1.getString(i))
                    val arr2 = JSONArray(teamsJson)
                    for (i in 0 until arr2.length()) teamsList.add(arr2.getString(i))
                } catch (e: Exception) {
                }
                result.success(
                    mapOf(
                        "competitions" to compsList,
                        "favouriteTeams" to teamsList,
                        "apiKey" to key,
                    )
                )
            }
            "setWelcomePrefs" -> {
                val sp = browserPrefs()
                val mode = call.argument<Int>("mode") ?: 1
                val voice = call.argument<Boolean>("voiceGreeting") ?: true
                val text = call.argument<String>("customText") ?: ""
                val speed = call.argument<Double>("speed")?.toFloat() ?: 1.0f
                val pitch = call.argument<Double>("pitch")?.toFloat() ?: 1.0f
                val vol = call.argument<Double>("volume")?.toFloat() ?: 0.8f
                sp.edit()
                    .putInt("welcome_mode", mode)
                    .putBoolean("welcome_voice", voice)
                    .putString("welcome_text", text)
                    .putFloat("welcome_speed", speed)
                    .putFloat("welcome_pitch", pitch)
                    .putFloat("welcome_volume", vol)
                    .apply()
                result.success(true)
            }
            "setCarCardMode" -> {
                val sp = browserPrefs()
                val show = call.argument<Boolean>("show") ?: true
                val mode = call.argument<String>("mode") ?: "Auto"
                sp.edit().putBoolean("car_card_show", show).putString("car_card_mode", mode).apply()
                DashMateStateBridge.carCardMode = mode
                DashMateStateBridge.notifyStateChanged(this)
                result.success(true)
            }
            "getMenuItems" -> {
                val json = browserPrefs().getString("menu_items", "[\"home\",\"lyrics\",\"prayer\",\"custom\",\"web\"]")
                try {
                    result.success(jsonArrayToList(json))
                } catch (e: Exception) {
                    result.success(listOf("home", "lyrics", "prayer", "custom", "web"))
                }
            }
            "loadYoutube" -> {
                val videoId = call.argument<String>("videoId") ?: ""
                DashMateStateBridge.currentYoutubeId = videoId
                DashMateStateBridge.notifyStateChanged(this)
                result.success(true)
            }
            "highlightWidget" -> {
                val id = call.argument<String>("id") ?: ""
                DashMateStateBridge.highlightWidget(id)
                result.success(true)
            }
            "getUserSetup" -> {
                // First-run setup: done once the owner has entered a name.
                result.success(
                    mapOf(
                        "name" to (browserPrefs().getString(USER_NAME_KEY, null) ?: ""),
                        "shape" to CarPreviewActivity.savedShape(this),
                        "size" to CarPreviewActivity.savedSize(this),
                    )
                )
            }
            "saveUserSetup" -> {
                val name = (call.argument<String>("name") ?: "").trim().take(40)
                val shape = call.argument<String>("shape")
                if (name.isNotEmpty()) browserPrefs().edit().putString(USER_NAME_KEY, name).apply()
                if (shape == "tall" || shape == "wide") CarPreviewActivity.chooseShape(this, shape == "tall")
                result.success(true)
            }
            "testVoiceGreeting" -> {
                val rawText = call.argument<String>("text") ?: ""
                val speed = call.argument<Double>("speed")?.toFloat() ?: 1.0f
                val pitch = call.argument<Double>("pitch")?.toFloat() ?: 1.0f
                if (rawText.isBlank()) {
                    // Nothing typed: no voice, just the chime the car plays.
                    tts?.stop()
                    DashMateStateBridge.playWelcomeChime(this)
                    result.success(true)
                    return
                }
                val ownerName = browserPrefs().getString(USER_NAME_KEY, null) ?: ""
                val text = run {
                    rawText
                        .replace("{name}", ownerName)
                        .replace("{prayer_name}", "Maghrib")
                        .replace("{prayer_time}", "6:59 PM")
                        .replace("{time}", "7:15 PM")
                        .replace("{rain}", "No rain expected")
                }
                tts?.setSpeechRate(speed)
                tts?.setPitch(pitch)
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "greeting_test")
                result.success(true)
            }
            "getRainRadar" -> {
                scope.launch {
                    val radarObj = WeatherManager.getRainRadar(this@MainActivity)
                    result.success(radarObj?.toString())
                }
            }
            "mediaPrevious" -> {
                dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                result.success(true)
            }
            "updateMusic" -> {
                val title = call.argument<String>("title") ?: "Unknown"
                val artist = call.argument<String>("artist") ?: "Unknown"
                val isPlaying = call.argument<Boolean>("isPlaying") ?: false
                // Remaining args are the declared defaults of updateMediaInfo (passed explicitly).
                DashMateStateBridge.updateMediaInfo(this, title, artist, isPlaying, "", "", 0L, 0L, 0L, 1.0f)
                result.success(true)
            }
            "updateSpeed" -> {
                val speed = call.argument<Double>("speed") ?: 0.0
                DashMateStateBridge.currentSpeed = speed.toFloat()
                DashMateStateBridge.notifyStateChanged(this)
                result.success(true)
            }
            "getWeatherData" -> {
                scope.launch {
                    val weatherObj = WeatherManager.getWeatherData(this@MainActivity)
                    result.success(weatherObj.toString())
                }
            }
            "getPrayerData" -> {
                scope.launch {
                    val prayerObj = PrayerTimesManager.getPrayerData(this@MainActivity)
                    result.success(prayerObj.toString())
                }
            }
            "saveParkingSpot" -> {
                // The phone's own position unless the caller passes one (never a made-up spot).
                var lat = call.argument<Double>("lat")
                var lng = call.argument<Double>("lng")
                if (lat == null || lng == null) {
                    if (!hasLocationPermission()) {
                        androidx.core.app.ActivityCompat.requestPermissions(
                            this,
                            arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION),
                            REQ_LOCATION,
                        )
                        result.error("no_permission", "Location permission needed", null)
                        return
                    }
                    val fix = lastKnownLocation()
                    if (fix == null) {
                        result.error("no_location", "No location fix yet", null)
                        return
                    }
                    lat = fix.latitude
                    lng = fix.longitude
                }
                val bay = call.argument<String>("bay") ?: ""
                val level = call.argument<String>("level") ?: ""
                val notes = call.argument<String>("notes") ?: ""
                ParkingManager.saveSpot(this, lat, lng, bay, level, notes)
                DashMateStateBridge.notifyStateChanged(this)
                result.success(true)
            }
            "getCalendarEvents" -> {
                val calObj = CalendarManager.getUpcomingEvents(this)
                result.success(calObj.toString())
            }
            "mediaPlayPause" -> {
                togglePlayPause()
                result.success(true)
            }
            "setNavScale" -> {
                val sp = browserPrefs()
                val scale = call.argument<Any>("scale")?.toString()?.toFloatOrNull() ?: 1.0f
                val opacity = call.argument<Any>("opacity")?.toString()?.toFloatOrNull() ?: 0.92f
                val colorIdx = call.argument<Int>("colorIndex") ?: 0
                sp.edit()
                    .putFloat("nav_scale", scale)
                    .putFloat("nav_opacity", opacity)
                    .putInt("nav_color_idx", colorIdx)
                    .apply()
                result.success(true)
            }
            "getLayoutSettings" -> {
                if (call.argument<Boolean>("tall") == true) {
                    // Tall (portrait) layout, e.g. the Jaecoo J7; null = use the built-in default.
                    result.success(browserPrefs().getString(DashMateStateBridge.PORTRAIT_LAYOUT_KEY, null))
                    return
                }
                val sp = browserPrefs()
                val layoutStr = sp.getString("dashmate_layout", null)
                    ?: sp.getString("car_layouts_current", null)
                    ?: getSharedPreferences("DashMatePrefs", Context.MODE_PRIVATE).getString("dashmate_layout", null)
                    ?: DashMateStateBridge.cachedLayoutJson
                result.success(layoutStr)
            }
            "setMenuItems" -> {
                val items = call.argument<List<String>>("items")
                    ?: listOf("home", "lyrics", "prayer", "custom", "web")
                browserPrefs().edit().putString("menu_items", JSONArray(items).toString()).apply()
                result.success(true)
            }
            "setBgMode" -> {
                val mode = call.argument<String>("mode") ?: "cover"
                val url = call.argument<String>("url") ?: ""
                DashMateStateBridge.bgMode = mode
                DashMateStateBridge.bgUrl = url
                browserPrefs().edit().putString("bg_mode", mode).putString("bg_url", url).apply()
                DashMateStateBridge.notifyStateChanged(this)
                result.success(true)
            }
            "getCarCardMode" -> {
                val sp = browserPrefs()
                result.success(
                    mapOf(
                        "show" to sp.getBoolean("car_card_show", true),
                        "mode" to sp.getString("car_card_mode", "Auto"),
                    )
                )
            }
            "clearParkingSpot" -> {
                ParkingManager.clearSpot(this)
                DashMateStateBridge.notifyStateChanged(this)
                result.success(true)
            }
            "setPrayerZone" -> {
                val zone = call.argument<String>("zone") ?: "WLY01"
                PrayerTimesManager.setSelectedZone(this, zone)
                scope.launch {
                    val prayerObj = PrayerTimesManager.getPrayerData(this@MainActivity)
                    // Refresh weather as well (result intentionally unused).
                    WeatherManager.getWeatherData(this@MainActivity)
                    DashMateStateBridge.notifyStateChanged(this@MainActivity)
                    result.success(prayerObj.toString())
                }
            }
            "fetchLyrics" -> {
                val title = call.argument<String>("title") ?: ""
                val artist = call.argument<String>("artist") ?: ""
                scope.launch {
                    val lrc = LyricsFetcher.fetchLyrics(artist, title, this@MainActivity)
                    val lrcText = lrc.joinToString("\n") { "[${it.timeMs}] ${it.text}" }
                    result.success(lrcText)
                }
            }
            "setLanguage" -> {
                val lang = call.argument<String>("lang") ?: "en"
                LocalizationManager.setLanguage(this, lang)
                DashMateStateBridge.notifyStateChanged(this)
                result.success(true)
            }
            "saveLayoutSettings" -> {
                val layout = call.argument<String>("layout") ?: call.argument<String>("layoutJson") ?: ""
                if (layout.isNotBlank() && call.argument<Boolean>("tall") == true) {
                    browserPrefs().edit().putString(DashMateStateBridge.PORTRAIT_LAYOUT_KEY, layout).apply()
                    DashMateStateBridge.applyPortraitLayoutLive(layout)
                    DashMateStateBridge.notifyStateChanged(this)
                } else if (layout.isNotBlank()) {
                    browserPrefs().edit()
                        .putString("dashmate_layout", layout)
                        .putString("car_layouts_current", layout)
                        .apply()
                    getSharedPreferences("DashMatePrefs", Context.MODE_PRIVATE).edit()
                        .putString("dashmate_layout", layout)
                        .putString("car_layouts_current", layout)
                        .apply()
                    DashMateStateBridge.cachedLayoutJson = layout
                    DashMateStateBridge.applyLayoutLive(layout)
                    DashMateStateBridge.notifyStateChanged(this)
                }
                result.success(true)
            }
            "getAirQuality" -> {
                scope.launch {
                    val airObj = WeatherManager.getAirQuality(this@MainActivity)
                    result.success(airObj.toString())
                }
            }
            "openNotificationSettings" -> {
                openNotificationListenerSettings()
                result.success(true)
            }
            "setFootballPrefs" -> {
                val sp = browserPrefs()
                val comps = call.argument<List<String>>("competitions") ?: listOf("PL", "CL")
                val teams = call.argument<List<String>>("favouriteTeams") ?: listOf("Arsenal", "Liverpool")
                val key = call.argument<String>("apiKey") ?: ""
                sp.edit()
                    .putString("football_comps", JSONArray(comps).toString())
                    .putString("football_teams", JSONArray(teams).toString())
                    .putString("football_api_key", key)
                    .apply()
                result.success(true)
            }
            "getLanguage" -> {
                result.success(LocalizationManager.getLanguage(this))
            }
            "navigateParking" -> {
                if (ParkingManager.getSpot(this) != null) {
                    ParkingManager.navigateBack(this)
                    result.success(true)
                } else {
                    result.success(false)
                }
            }
            "getBgPrefs" -> {
                val sp = browserPrefs()
                val bgMode: String? = DashMateStateBridge.bgMode.let {
                    if (it.isBlank()) sp.getString("bg_mode", "cover") else it
                }
                val bgUrl: String? = DashMateStateBridge.bgUrl.let {
                    if (it.isBlank()) sp.getString("bg_url", "") else it
                }
                result.success(mapOf("mode" to bgMode, "url" to bgUrl))
            }
            "getWelcomePrefs" -> {
                val sp = browserPrefs()
                result.success(
                    mapOf(
                        "mode" to sp.getInt("welcome_mode", 1),
                        "voiceGreeting" to sp.getBoolean("welcome_voice", false),
                        "customText" to sp.getString("welcome_text", ""),
                        "speed" to sp.getFloat("welcome_speed", 1.0f).toDouble(),
                        "pitch" to sp.getFloat("welcome_pitch", 1.0f).toDouble(),
                        "volume" to sp.getFloat("welcome_volume", 0.8f).toDouble(),
                    )
                )
            }
            "getButtonPrefs" -> {
                val sp = browserPrefs()
                val scale = sp.getFloat("nav_scale", 1.0f) * 100
                val opacity = sp.getFloat("nav_opacity", 0.92f) * 100
                val colorIdx = sp.getInt("nav_color_idx", 0)
                result.success(
                    mapOf(
                        "size" to scale.toDouble(),
                        "opacity" to opacity.toDouble(),
                        "colorIndex" to colorIdx,
                    )
                )
            }
            "getParkingSpot" -> {
                val spot = ParkingManager.getSpot(this)
                if (spot != null) {
                    val map = mapOf(
                        "lat" to spot.lat,
                        "lng" to spot.lng,
                        "bay" to spot.bay,
                        "level" to spot.level,
                        "notes" to spot.notes,
                        "timestamp" to spot.timestamp,
                    )
                    result.success(JSONObject(map).toString())
                } else {
                    result.success(null)
                }
            }
            "getState" -> {
                result.success(DashMateStateBridge.buildStateJson(this))
            }
            "openCarPreview" -> {
                startActivity(Intent(this, CarPreviewActivity::class.java))
                result.success(true)
            }
            "getLayoutMode" -> result.success(DashMateStateBridge.layoutMode(this))
            "setLayoutMode" -> {
                val mode = if (call.argument<String>("mode") == "auto") "auto" else "tall"
                browserPrefs().edit().putString(DashMateStateBridge.LAYOUT_MODE_KEY, mode).apply()
                DashMateStateBridge.notifyStateChanged(this)
                result.success(true)
            }
            "getCarScreenSize" -> {
                result.success(CarPreviewActivity.savedSize(this))
            }
            "setFont" -> {
                val font = call.argument<String>("font") ?: "orbitron"
                DashMateStateBridge.activeFont = font
                DashMateStateBridge.notifyStateChanged(this)
                result.success(true)
            }
            "setPage", "setCarPage" -> {
                val page = call.argument<String>("page") ?: "home"
                call.argument<String>("mode")?.let { DashMateStateBridge.customMode = it }
                DashMateStateBridge.setPage(page)
                if (page == "custom") DashMateStateBridge.notifyStateChanged(this)
                result.success(true)
            }
            "mediaNext" -> {
                dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
                result.success(true)
            }
            else -> result.notImplemented()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.stop()
        tts?.shutdown()
    }
}
