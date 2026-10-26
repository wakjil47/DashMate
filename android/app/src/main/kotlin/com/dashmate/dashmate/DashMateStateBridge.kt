package com.dashmate.dashmate

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.dashmate.dashmate.deck.CalendarManager
import com.dashmate.dashmate.deck.LocalizationManager
import com.dashmate.dashmate.deck.LyricLine
import com.dashmate.dashmate.deck.LyricsFetcher
import com.dashmate.dashmate.deck.ParkingManager
import com.dashmate.dashmate.deck.PrayerTimesManager
import com.dashmate.dashmate.deck.WeatherManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList

object DashMateStateBridge {
    private const val TAG = "DashMateStateBridge"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO)
    private val listeners = CopyOnWriteArrayList<StateListener>()

    interface StateListener {
        fun onStateUpdated(stateJson: String)
        fun onSwitchPage(page: String)
        fun onExecuteScript(script: String)
    }

    var cachedLayoutJson: String? = null

    /** Prefs key (dd_car_browser) of the tall-screen widget layout, edited on the phone. */
    const val PORTRAIT_LAYOUT_KEY = "dashmate_layout_portrait"

    /** Prefs key (dd_car_browser): "tall" (default) = the car always uses the tall placement and tall UI
     *  on a full-screen deck, whatever the surface shape; "auto" = follow the screen's shape. */
    const val LAYOUT_MODE_KEY = "dashmate_layout_mode"

    fun layoutMode(context: Context?): String {
        val v = try {
            context?.getSharedPreferences("dd_car_browser", Context.MODE_PRIVATE)?.getString(LAYOUT_MODE_KEY, null)
        } catch (e: Exception) { null }
        return if (v == "auto") "auto" else "tall"
    }
    var currentSpeed: Float = 0f
    var speedUnit: String = "km/h"
    var isGps: Boolean = true

    var songTitle: String = "Not Playing"
    var songArtist: String = "DashMate Audio"
    var songApp: String = "YouTube Music"
    var songCoverUrl: String = ""
    var currentArtBitmap: Bitmap? = null
    var currentArtUri: Uri? = null
    var isPlaying: Boolean = false
    var playbackPositionMs: Long = 0L
    var playbackPositionTimestampMs: Long = 0L
    var playbackSpeed: Float = 1.0f
    var playbackDurationMs: Long = 0L
    var isFetchingLyrics: Boolean = false
    var currentLyrics: List<LyricLine> = emptyList()

    var nextPrayerName: String = "Subuh"
    var nextPrayerTime: String = "05:49"
    var prayerCountdown: String = "42m"

    fun getCurrentPositionMs(): Long {
        if (!isPlaying || playbackPositionTimestampMs == 0L) {
            return playbackPositionMs
        }
        val elapsed = SystemClock.elapsedRealtime() - playbackPositionTimestampMs
        val computed = playbackPositionMs + (elapsed * playbackSpeed).toLong()
        return if (playbackDurationMs > 0) {
            computed.coerceIn(0L, playbackDurationMs)
        } else {
            computed.coerceAtLeast(0L)
        }
    }

    @Volatile var lyricsSyncKnown: Boolean = true

    /**
     * Car audio delay for the lyrics on the car screen (ms): Android Auto plays the sound in the car a
     * little after the phone's clock, so the lyrics wait this long. Set with Earlier / Later on the
     * Lyrics page; the phone's own lyrics don't use it.
     */
    // v2 (26 Oct): new default from Android Auto's own log ("CAR.AUDIO.PERF ... AUDIO_STREAM_MEDIA"
    // buffer 1.0–2.0 s); the new key also drops the old value, which had been tapped down to -3 s.
    private const val LYRICS_DELAY_KEY = "lyrics_car_delay_v2"
    private const val LYRICS_DELAY_DEFAULT = 1500
    fun lyricsDelayMs(context: Context?): Int =
        context?.getSharedPreferences("dd_car_browser", Context.MODE_PRIVATE)?.getInt(LYRICS_DELAY_KEY, LYRICS_DELAY_DEFAULT)
            ?: LYRICS_DELAY_DEFAULT
    fun changeLyricsDelayMs(context: Context, deltaMs: Int): Int {
        val v = (lyricsDelayMs(context) + deltaMs).coerceIn(-15000, 15000)   // up to 15 s sooner or later
        context.getSharedPreferences("dd_car_browser", Context.MODE_PRIVATE).edit().putInt(LYRICS_DELAY_KEY, v).apply()
        notifyStateChanged(context)
        return v
    }

    fun updatePlaybackProgress(positionMs: Long, durationMs: Long = 0L, speed: Float = 1.0f) {
        playbackPositionMs = positionMs
        playbackPositionTimestampMs = SystemClock.elapsedRealtime()
        if (durationMs > 0) {
            playbackDurationMs = durationMs
        }
        playbackSpeed = if (speed > 0f) speed else 1.0f
        if (listeners.isNotEmpty()) {
            notifyStateChanged()
        }
    }

    var activeFont: String = "orbitron"
    var bgMode: String = "cover"
    var bgUrl: String = ""
    var currentPage: String = "home"
    var currentYoutubeId: String = ""
    /** Address the phone sent to the car browser (Browse screen); the car screen takes it once. */
    @Volatile var pendingWebUrl: String? = null
        set(v) { field = v; pendingWebUrlAt = if (v != null) System.currentTimeMillis() else 0L }
    @Volatile private var pendingWebUrlAt: Long = 0L
    /** The phone's link, once, and only if it was sent in the last minute (never a days-old link). */
    fun takePendingWebUrl(): String? {
        val url = pendingWebUrl
        val fresh = url != null && System.currentTimeMillis() - pendingWebUrlAt < 60_000L
        pendingWebUrl = null
        return if (fresh) url else null
    }

    /** Which Custom page the phone picked: weather (default), calendar, sports, radar or ev. */
    var customMode: String = "weather"

    /**
     * Set when the Custom page is opened by tapping a Home card (weather, sports, calendar...):
     * shows that card's topic without changing the mode saved on the phone. Cleared on leaving.
     */
    var customCardMode: String? = null
    var appLanguage: String = "en"
    var carCardMode: String = "music"

    var ron95Price: String = "2.05"
    var ron97Price: String = "3.19"
    var dieselPrice: String = "2.95"

    var shortcutsList: MutableList<JSONObject> = mutableListOf(
        JSONObject().apply {
            put("name", "YouTube")
            put("url", "https://m.youtube.com")
            put("icon", "dd_youtube.png")
        },
        JSONObject().apply {
            put("name", "RTM Klik")
            put("url", "https://rtmklik.rtm.gov.my")
            put("icon", "dd_rtm.png")
        },
        JSONObject().apply {
            put("name", "Google")
            put("url", "https://www.google.com")
            put("icon", "https://www.google.com/favicon.ico")
        },
        JSONObject().apply {
            put("name", "Waze")
            put("url", "https://www.waze.com/live-map")
            put("icon", "https://www.waze.com/favicon.ico")
        },
        JSONObject().apply {
            put("name", "Parking")
            put("url", "dashmate://parking")
            put("icon", "assets/icons/parking.svg")
        },
    )

    fun addListener(listener: StateListener) {
        listeners.add(listener)
        listener.onStateUpdated(buildStateJson(null))
    }

    fun removeListener(listener: StateListener) {
        listeners.remove(listener)
    }

    private var lastMediaUpdateMs: Long = 0L
    private var lastMediaKey: String = ""

    fun setPage(page: String) {
        if (page != "custom") customCardMode = null
        if (currentPage != page || page == "custom" || pendingWebUrl != null) {
            currentPage = page
            mainHandler.post {
                for (listener in listeners) {
                    listener.onSwitchPage(page)
                }
            }
        }
    }

    fun playWelcomeChime(context: Context) {
        try {
            val afd = context.assets.openFd("dd_welcome_chime.wav")
            val mediaPlayer = MediaPlayer()
            mediaPlayer.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            mediaPlayer.prepare()
            mediaPlayer.start()
            mediaPlayer.setOnCompletionListener {
                it.release()
                afd.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not play welcome chime: ${e.message}")
        }
    }

    fun updateMediaInfo(
        context: Context,
        title: String,
        artist: String,
        playing: Boolean,
        coverUrl: String = "",
        app: String = "",
        durationMs: Long = 0L,
        /** -1 = unknown (song notifications carry no position): keep the running clock. */
        positionMs: Long = -1L,
        lastUpdateTime: Long = 0L,
        speed: Float = 1.0f,
        /** False for DashMate radio without a song name: don't search lyrics for a station name. */
        fetchLyrics: Boolean = true,
        /** False: the song was already playing when the radio was tuned, so lyrics can't be timed. */
        syncKnown: Boolean = true,
    ) {
        lyricsSyncKnown = syncKnown
        val titleChanged = title != songTitle || artist != songArtist
        val playStateChanged = playing != isPlaying
        val now = System.currentTimeMillis()
        val currentKey = "$title|$artist|$playing"
        val isSamePlayback = currentKey == lastMediaKey
        // Skip quick repeats, but never one that carries a fresh position (that is the sync fix).
        if (!titleChanged && !playStateChanged && isSamePlayback && now - lastMediaUpdateMs < 1500 && positionMs < 0) {
            return
        }
        lastMediaKey = currentKey
        lastMediaUpdateMs = now

        songTitle = title
        songArtist = artist
        isPlaying = playing
        if (coverUrl.isNotBlank()) {
            songCoverUrl = coverUrl
        }
        if (app.isNotBlank()) {
            songApp = app
        }

        if (titleChanged) {
            playbackPositionMs = positionMs.coerceAtLeast(0L)
            playbackDurationMs = durationMs.coerceAtLeast(0L)
            playbackPositionTimestampMs = if (lastUpdateTime > 0) lastUpdateTime else SystemClock.elapsedRealtime()
        } else {
            if (durationMs > 0) {
                playbackDurationMs = durationMs
            }
            if (positionMs >= 0) {
                playbackPositionMs = positionMs
                playbackPositionTimestampMs = if (lastUpdateTime > 0) lastUpdateTime else SystemClock.elapsedRealtime()
            }
        }
        playbackSpeed = if (speed > 0f) speed else 1.0f

        if (titleChanged && !fetchLyrics) {
            currentLyrics = emptyList()
            isFetchingLyrics = false
        }
        if (fetchLyrics && titleChanged && title.isNotBlank() && title != "Not Playing") {
            isFetchingLyrics = true
            currentLyrics = emptyList()
            notifyStateChanged(context)
            scope.launch {
                try {
                    val fetched = LyricsFetcher.fetchLyrics(artist, title, context)
                    currentLyrics = fetched
                    Log.d(TAG, "Fetched ${currentLyrics.size} lyrics lines for $title")
                } catch (e: Exception) {
                    Log.w(TAG, "Error fetching lyrics: ${e.message}")
                } finally {
                    isFetchingLyrics = false
                    notifyStateChanged(context)
                }
            }
            return
        }
        notifyStateChanged(context)
    }

    fun refreshAllServices(context: Context) {
        scope.launch {
            try {
                WeatherManager.getWeatherData(context)
                WeatherManager.getAirQuality(context)
                WeatherManager.getRainRadar(context)
                com.dashmate.dashmate.deck.FuelPriceManager.refresh(context)
                com.dashmate.dashmate.deck.PrayerTimesManager.getPrayerData(context)
            } catch (e: Exception) {
                Log.w(TAG, "Service refresh error: ${e.message}")
            }
            notifyStateChanged(context)
        }
    }

    private var savedChoicesRestored = false

    /** After a restart: the background and car card the owner picked on the phone (saved in prefs). */
    private fun restoreSavedChoices(context: Context) {
        if (savedChoicesRestored) return
        savedChoicesRestored = true
        try {
            val sp = context.getSharedPreferences("dd_car_browser", Context.MODE_PRIVATE)
            sp.getString("bg_mode", null)?.takeIf { it.isNotBlank() }?.let { bgMode = it }
            sp.getString("bg_url", null)?.let { if (bgUrl.isBlank()) bgUrl = it }
            sp.getString("car_card_mode", null)?.takeIf { it.isNotBlank() }?.let { carCardMode = it }
        } catch (e: Exception) {
        }
    }

    fun buildStateJson(context: Context?): String {
        if (context != null) restoreSavedChoices(context)
        val state = JSONObject()
        state.put("speed", currentSpeed.toInt())
        state.put("speedOnly", false)
        state.put("gps", isGps)
        state.put("fn", if (currentPage == "custom") (customCardMode ?: customMode) else activeFont)
        state.put("page", currentPage)
        state.put("carCard", carCardMode)
        state.put("layoutMode", layoutMode(context))

        if (currentPage == "custom") {
            // Custom page: one full-screen layout per mode picked on the phone (Custom page screen).
            fun tile(id: String, x: Int, y: Int, w: Int, h: Int) =
                JSONObject().apply { put("id", id); put("x", x); put("y", y); put("w", w); put("h", h) }
            state.put("layout", JSONObject().apply {
                put("v", 2)
                put("textScale", 1)
                put("clockFont", "thin")
                put("widgets", JSONArray().apply {
                    when (customCardMode ?: customMode) {
                        "calendar" -> put(tile("calendar", 1, 1, 12, 8))
                        "sports" -> put(tile("sports", 1, 1, 12, 8))
                        "radar" -> put(tile("radar", 1, 1, 12, 8))
                        "ev" -> put(tile("ev", 1, 1, 12, 8))
                        else -> {
                            put(tile("forecast", 1, 1, 6, 8))
                            put(tile("air", 7, 1, 6, 4))
                            put(tile("radar", 7, 5, 6, 4))
                        }
                    }
                })
            })
        } else {
            var activeLayoutStr = cachedLayoutJson
            if (context != null) {
                try {
                    val spBrowser = context.getSharedPreferences("dd_car_browser", Context.MODE_PRIVATE)
                    val spPrefs = context.getSharedPreferences("DashMatePrefs", Context.MODE_PRIVATE)
                    val layoutStr = spBrowser.getString("dashmate_layout", null)
                        ?: spBrowser.getString("car_layouts_current", null)
                        ?: spPrefs.getString("dashmate_layout", null)
                    if (!layoutStr.isNullOrBlank()) {
                        activeLayoutStr = layoutStr
                        cachedLayoutJson = layoutStr
                    }
                } catch (e: Exception) {
                }
            }
            if (!activeLayoutStr.isNullOrBlank()) {
                try {
                    state.put("layout", JSONObject(activeLayoutStr))
                } catch (e: Exception) {
                }
            }
            // Tall screens (Jaecoo J7) use their own layout; the page falls back to its portrait default.
            try {
                context?.getSharedPreferences("dd_car_browser", Context.MODE_PRIVATE)
                    ?.getString(PORTRAIT_LAYOUT_KEY, null)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { state.put("layoutP", JSONObject(it)) }
            } catch (e: Exception) {
            }
        }

        // Portrait default layout: only when the owner hasn't saved a tall layout (added above).
        if (!state.has("layoutP")) state.put("layoutP", JSONObject().apply {
            put("v", 2)
            put("textScale", 1)
            put("clockFont", "thin")
            put("widgets", JSONArray().apply {
                put(JSONObject().apply {
                    put("id", "clock"); put("x", 1); put("y", 1); put("w", 12); put("h", 3)
                })
                put(JSONObject().apply {
                    put("id", "music"); put("x", 1); put("y", 4); put("w", 12); put("h", 4)
                })
                put(JSONObject().apply {
                    put("id", "prayer"); put("x", 1); put("y", 8); put("w", 6); put("h", 3)
                })
                put(JSONObject().apply {
                    put("id", "weather"); put("x", 7); put("y", 8); put("w", 6); put("h", 3)
                })
                put(JSONObject().apply {
                    put("id", "football"); put("x", 1); put("y", 11); put("w", 12); put("h", 2)
                })
                put(JSONObject().apply {
                    put("id", "fuel"); put("x", 1); put("y", 13); put("w", 12); put("h", 3)
                })
                put(JSONObject().apply {
                    put("id", "speed"); put("x", 1); put("y", 16); put("w", 5); put("h", 3)
                    put("ta", "center")
                })
                put(JSONObject().apply {
                    put("id", "shortcuts"); put("x", 6); put("y", 16); put("w", 7); put("h", 3)
                    put("ta", "center")
                })
            })
        })

        val lang = if (context != null) LocalizationManager.getLanguage(context) else appLanguage
        val labelsObj = LocalizationManager.getLabels(lang)
        state.put("labels", labelsObj)
        state.put("lang", lang)

        val bg = JSONObject()
        bg.put("mode", bgMode)
        bg.put("dim", 0.4)
        if (bgUrl.isNotBlank()) {
            bg.put("url", bgUrl)
        } else if (songCoverUrl.isNotBlank()) {
            bg.put("url", songCoverUrl)
        }
        state.put("bg", bg)

        val lyricsArr = JSONArray()
        val linesPairArr = JSONArray()
        for (l in currentLyrics) {
            lyricsArr.put(JSONObject().apply {
                put("time", l.timeMs)
                put("text", l.text)
            })
            linesPairArr.put(JSONArray().apply {
                put(l.timeMs)
                put(l.text)
            })
        }

        val musicStatus = when {
            currentLyrics.isNotEmpty() -> "found"
            isFetchingLyrics -> "loading"
            songTitle.isBlank() || songTitle == "Not Playing" -> "waiting"
            else -> "notFound"
        }
        val currentPos = getCurrentPositionMs()
        state.put("music", JSONObject().apply {
            put("title", songTitle)
            put("artist", songArtist)
            put("art", songCoverUrl)
            put("cover", songCoverUrl)
            put("playing", isPlaying)
            put("isPlaying", isPlaying)
            put("positionMs", currentPos)
            put("durationMs", playbackDurationMs)
            put("pos", currentPos / 1000)
            put("dur", playbackDurationMs / 1000)
            put("lines", linesPairArr)
            put("status", musicStatus)
            put("sentAtMs", System.currentTimeMillis())
            put("app", songApp)
            put("lyrics", lyricsArr)
            // Radio can't tell how far into the song it is: the lyrics count from when the song
            // name arrived, so the Lyrics page shows "approx. sync".
            put("approx", songApp == DashMateMediaCardService.RADIO_APP_NAME)
            // Tuned in mid-song: show the lyrics as plain text until the next song starts.
            put("unsynced", !lyricsSyncKnown)
            put("delayMs", lyricsDelayMs(context))
        })
        state.put("positionMs", currentPos)
        state.put("durationMs", playbackDurationMs)

        val weatherObj: JSONObject = if (context != null) {
            try {
                val cached = context.getSharedPreferences("DashMatePrefs", Context.MODE_PRIVATE)
                    .getString("dashmate_weather_cache", null)
                if (cached != null) JSONObject(cached) else defaultWeather()
            } catch (e: Exception) {
                defaultWeather()
            }
        } else {
            defaultWeather()
        }
        state.put("weather", weatherObj)
        // The Forecast card reads {now, hours, days}; the older 6-hour list was never what it expected
        // (the card showed "–°").
        if (weatherObj.has("detail")) {
            state.put("forecast", weatherObj.getJSONObject("detail"))
        } else if (weatherObj.has("forecast")) {
            state.put("forecast", weatherObj.getJSONArray("forecast"))
        }

        val airObj: JSONObject = if (context != null) {
            try {
                val cached = context.getSharedPreferences("DashMatePrefs", Context.MODE_PRIVATE)
                    .getString("dashmate_air_cache", null)
                if (cached != null) JSONObject(cached) else defaultAir()
            } catch (e: Exception) {
                defaultAir()
            }
        } else {
            defaultAir()
        }
        state.put("air", airObj)

        val radarObj: JSONObject = if (context != null) {
            try {
                val cached = context.getSharedPreferences("DashMatePrefs", Context.MODE_PRIVATE)
                    .getString("dashmate_radar_cache", null)
                if (cached != null) JSONObject(cached) else defaultRadar()
            } catch (e: Exception) {
                defaultRadar()
            }
        } else {
            defaultRadar()
        }
        state.put("radar", radarObj)

        val calendarObj: JSONObject = if (context != null) {
            try {
                CalendarManager.getUpcomingEvents(context)
            } catch (e: Exception) {
                defaultCalendar()
            }
        } else {
            defaultCalendar()
        }
        state.put("calendar", calendarObj)

        val parkingSpot = if (context != null) ParkingManager.getSpot(context) else null
        state.put("parking", JSONObject().apply {
            put("saved", parkingSpot != null)
            if (parkingSpot != null) {
                put("lat", parkingSpot.lat)
                put("lng", parkingSpot.lng)
                put("bay", parkingSpot.bay)
                put("level", parkingSpot.level)
                put("notes", parkingSpot.notes)
                put("time", parkingSpot.timestamp)
            }
        })

        val shortcutsArr = JSONArray()
        for (sc in shortcutsList) {
            shortcutsArr.put(sc)
        }
        state.put("shortcuts", shortcutsArr)

        val prayerObj: JSONObject = if (context != null) {
            try {
                // The cache holds raw times; the page and card need next/countdown, so enrich it.
                PrayerTimesManager.cachedEnriched(context) ?: defaultPrayer()
            } catch (e: Exception) {
                defaultPrayer()
            }
        } else {
            defaultPrayer()
        }
        state.put("prayer", prayerObj)
        // Feed the "Next Prayer" media card (these were never updated before).
        prayerObj.optJSONObject("next")?.let { nx ->
            nextPrayerName = nx.optString("name", nextPrayerName)
            nextPrayerTime = nx.optString("time", nextPrayerTime)
            val mins = ((nx.optLong("epochMs") - System.currentTimeMillis()) / 60000).coerceAtLeast(0)
            prayerCountdown = if (mins >= 60) "${mins / 60}h ${mins % 60}m" else "${mins}m"
        }

        // Weekly prices from data.gov.my (null until the first fetch -> page shows "Fuel prices…").
        context?.let { com.dashmate.dashmate.deck.FuelPriceManager.cached(it) }?.let { state.put("fuel", it) }

        val scores = JSONArray().apply {
            put(JSONObject().apply {
                put("home", "Arsenal")
                put("away", "Liverpool")
                put("score", "2 - 1")
                put("time", "74'")
                put("status", "live")
                put("state", "in")
            })
            put(JSONObject().apply {
                put("home", "Real Madrid")
                put("away", "Barcelona")
                put("score", "3 - 2")
                put("time", "FT")
                put("status", "ft")
                put("state", "post")
            })
        }
        state.put("scores", scores)
        state.put("scoresLabel", "Today's Matches")
        state.put("sports", JSONObject().apply { put("games", scores) })

        state.put("ev", JSONObject().apply {
            put("lat", 3.139)
            put("lng", 101.6869)
            put("items", JSONArray().apply {
                put(JSONObject().apply {
                    put("name", "Shell Recharge Pavilion")
                    put("plugs", "CCS2 180kW (2/2 Avail)")
                    put("lat", 3.1488)
                    put("lng", 101.7133)
                    put("status", "up")
                })
                put(JSONObject().apply {
                    put("name", "Gentari Suria KLCC")
                    put("plugs", "CCS2 60kW, Type 2 22kW")
                    put("lat", 3.1579)
                    put("lng", 101.7116)
                    put("status", "up")
                })
            })
        })

        // Flat legacy fields
        state.put("lyrics", lyricsArr)
        state.put("lines", linesPairArr)
        state.put("title", songTitle)
        state.put("artist", songArtist)
        state.put("art", songCoverUrl)
        state.put("playing", isPlaying)
        state.put("positionMs", currentPos)   // live position, not the last saved one
        state.put("durationMs", playbackDurationMs)
        state.put("status", musicStatus)
        state.put("app", songApp)
        state.put("cached", true)
        state.put("sentAtMs", System.currentTimeMillis())
        return state.toString()
    }

    private fun defaultWeather(): JSONObject = JSONObject().apply {
        put("temp", 31)
        put("code", 2)
        put("cond", "Partly Cloudy")
        put("icon", "partly-cloudy-day")
        put("city", "Kuala Lumpur")
        put("wind", "12 km/h")
        put("rain", null as Any?)
    }

    private fun defaultAir(): JSONObject = JSONObject().apply {
        put("aqi", 42)
        put("pm25", 12)
        put("uv", 5.0)
        put("label", "Good")
    }

    private fun defaultRadar(): JSONObject = JSONObject().apply {
        put("host", "https://tilecache.rainviewer.com")
        put("path", "/v2/radar/nowcast")
        put("lat", 3.139)
        put("lon", 101.6869)
        put("time", System.currentTimeMillis() / 1000)
    }

    private fun defaultCalendar(): JSONObject = JSONObject().apply {
        put("access", false)
        put("events", JSONArray())
    }

    private fun defaultPrayer(): JSONObject = JSONObject().apply {
        put("status", "ok")
        put("zone", "WLY01 · Kuala Lumpur, Putrajaya")
        put("zoneName", "Kuala Lumpur, Putrajaya")
        put("date", "Today")

        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kuala_Lumpur"))
        fun epochAt(h: Int, m: Int): Long {
            val c = cal.clone() as Calendar
            c.set(Calendar.HOUR_OF_DAY, h)
            c.set(Calendar.MINUTE, m)
            c.set(Calendar.SECOND, 0)
            c.set(Calendar.MILLISECOND, 0)
            return c.timeInMillis
        }

        fun entry(name: String, time: String, epochMs: Long, obligatory: Boolean) = JSONObject().apply {
            put("name", name)
            put("time", time)
            put("epochMs", epochMs)
            put("obligatory", obligatory)
        }

        val times = JSONArray().apply {
            put(entry("Imsak", "05:39", epochAt(5, 39), false))
            put(entry("Subuh", "05:49", epochAt(5, 49), true))
            put(entry("Syuruk", "06:56", epochAt(6, 56), false))
            put(entry("Zohor", "13:00", epochAt(13, 0), true))
            put(entry("Asar", "16:19", epochAt(16, 19), true))
            put(entry("Maghrib", "18:59", epochAt(18, 59), true))
            put(entry("Isyak", "20:10", epochAt(20, 10), true))
        }
        put("times", times)

        val now = System.currentTimeMillis()
        var next: JSONObject? = null
        var current: JSONObject? = null
        for (i in 0 until times.length()) {
            val t = times.getJSONObject(i)
            if (t.getLong("epochMs") <= now) {
                current = t
            } else if (next == null) {
                next = t
            }
        }
        val nextObj = next ?: run {
            val c = cal.clone() as Calendar
            c.add(Calendar.DAY_OF_YEAR, 1)
            c.set(Calendar.HOUR_OF_DAY, 5)
            c.set(Calendar.MINUTE, 49)
            c.set(Calendar.SECOND, 0)
            c.set(Calendar.MILLISECOND, 0)
            entry("Subuh", "05:49", c.timeInMillis, true)
        }
        val currentObj = current ?: run {
            val c = cal.clone() as Calendar
            c.add(Calendar.DAY_OF_YEAR, -1)
            c.set(Calendar.HOUR_OF_DAY, 20)
            c.set(Calendar.MINUTE, 10)
            c.set(Calendar.SECOND, 0)
            c.set(Calendar.MILLISECOND, 0)
            entry("Isyak", "20:10", c.timeInMillis, true)
        }

        val diffMin = ((nextObj.getLong("epochMs") - now).coerceAtLeast(0L) / 60_000L).toInt()
        val h = diffMin / 60
        val m = diffMin % 60
        val count = if (h > 0) "${h}h ${m}m" else "${m}m"

        put("current", currentObj)
        put("next", nextObj)
        put("time", nextObj.optString("time", "05:49"))
        put("count", count)
        put("countMin", diffMin)
        put("imsak", "05:39")
        put("subuh", "05:49")
        put("syuruk", "06:56")
        put("zohor", "13:00")
        put("asar", "16:19")
        put("maghrib", "18:59")
        put("isyak", "20:10")
    }

    fun notifyStateChanged(context: Context? = null) {
        val json = buildStateJson(context)
        mainHandler.post {
            for (listener in listeners) {
                listener.onStateUpdated(json)
            }
            try {
                DashMateMediaCardService.triggerUpdate()
            } catch (e: Throwable) {
            }
        }
    }

    fun highlightWidget(id: String) {
        executeScript("if (window.ddHighlight) window.ddHighlight('$id');")
    }

    /** Live update of the tall layout while the owner edits it on the phone. */
    fun applyPortraitLayoutLive(layoutJson: String) {
        executeScript("if (window.ddSetLayout) { window.ddSetLayout($layoutJson, true); }")
    }

    fun applyLayoutLive(layoutJson: String) {
        cachedLayoutJson = layoutJson
        val script = "if (window.ddSetLayout) { window.ddSetLayout($layoutJson, false); } " +
            "else if (window.applyLayout) { window.applyLayout($layoutJson); } " +
            "else if (window.ddUpdate) { window.ddUpdate({ layout: $layoutJson }); }"
        executeScript(script)
    }

    fun executeScript(script: String) {
        mainHandler.post {
            for (listener in listeners) {
                listener.onExecuteScript(script)
            }
        }
    }

    // Navigation opens in the car's Waze/Maps when the Deck car session is running (see CarNav).
    fun launchNavigation(context: Context, destination: String) {
        CarNav.toQuery(context, destination)
    }

    fun launchNavigationCoords(context: Context, lat: Double, lng: Double) {
        CarNav.toCoords(context, lat, lng)
    }
}
