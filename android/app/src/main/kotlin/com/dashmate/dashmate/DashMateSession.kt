package com.dashmate.dashmate

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.database.Cursor
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.connection.CarConnection
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.dashmate.dashmate.deck.ParkingAutoSaver
import com.dashmate.dashmate.deck.PrayerAlertManager

class DashMateSession : Session() {

    private var screen: DashMateCarScreen? = null

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                DashMateTheme.carSessionActive = true
                DashMateTheme.set(carContext.isDarkMode)
                CarNav.attach(carContext)
                val app = carContext.applicationContext
                DashMateCarLife.watchConnection(app)
                ParkingAutoSaver.start(app)
                PrayerAlertManager.scheduleAsync(app)
                // Weather / air / radar / fuel were only refreshed from the phone app before.
                DashMateStateBridge.refreshAllServices(app)
                Log.i(DashMateCarLife.TAG, "session onCreate")
            }

            override fun onStart(owner: LifecycleOwner) { Log.i(DashMateCarLife.TAG, "session onStart") }
            override fun onResume(owner: LifecycleOwner) { Log.i(DashMateCarLife.TAG, "session onResume") }
            override fun onPause(owner: LifecycleOwner) { Log.i(DashMateCarLife.TAG, "session onPause") }
            override fun onStop(owner: LifecycleOwner) { Log.i(DashMateCarLife.TAG, "session onStop") }

            override fun onDestroy(owner: LifecycleOwner) {
                Log.i(DashMateCarLife.TAG, "session onDestroy")
                screen = null
                ParkingAutoSaver.onCarSessionEnded(carContext.applicationContext)
                CarNav.detach()
                DashMateTheme.carSessionActive = false
            }
        })
    }

    /** Android Auto flips day/night (headlights, sunset) without restarting the session. */
    override fun onCarConfigurationChanged(newConfiguration: Configuration) {
        super.onCarConfigurationChanged(newConfiguration)
        DashMateTheme.set(carContext.isDarkMode)
    }

    override fun onCreateScreen(intent: Intent): Screen {
        Log.i(
            DashMateCarLife.TAG,
            "onCreateScreen action=${intent.action} data=${intent.data} flags=0x${Integer.toHexString(intent.flags)} " +
                "extras=${intent.extras?.keySet()} cmp=${intent.component?.shortClassName}",
        )
        if (intent.action == CarContext.ACTION_NAVIGATE) DashMateCarLife.forwardNavigation(carContext, intent)
        // The start decision (auto vs owner) is made by the screen from live signals.
        return DashMateCarScreen(carContext, intent).also { screen = it }
    }

    /**
     * The host sends a new intent to a running session when the user taps DashMate's icon
     * (or asks for navigation while DashMate is Android Auto's navigation app).
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.i(DashMateCarLife.TAG, "onNewIntent action=${intent.action} data=${intent.data} extras=${intent.extras?.keySet()}")
        if (intent.action == CarContext.ACTION_NAVIGATE) {
            // Voice "navigate to ..." goes to Waze; it must not pop the deck open.
            DashMateCarLife.forwardNavigation(carContext, intent)
            return
        }
        screen?.requestOpen("icon tap (onNewIntent)")
    }
}

/**
 * Decides whether a car session was started by the owner or by Android Auto itself.
 * Priority: DashMate never opens by itself > one tap opens it.
 *
 * Android Auto starts its navigation app (the last one the owner opened) ~2.4 s after every
 * connect. Only LIVE signals in this process count (a saved time alone is never trusted):
 *  1. a later session in a connection whose first session was handled -> USER;
 *  2. a navigation intent -> AUTO (forwarded to Waze);
 *  3. CarConnection still NOT_CONNECTED (AA is starting us on connect) -> AUTO;
 *  4. this process saw NOT_CONNECTED -> PROJECTION: first session < [AUTO_START_MS] after it ->
 *     AUTO, otherwise USER;
 *  5. fresh process: AA's own connection notification (gearhead, foreground-service flag) that is
 *     active right now: posted < [AUTO_START_MS] ago -> AUTO, otherwise USER; none active -> AUTO;
 *  6. no live signal after [SIGNAL_WAIT_MS] -> AUTO.
 * AUTO = finishCarApp() before any surface / deck / chime / media work; AA shows its app grid.
 */
object DashMateCarLife {
    const val TAG = "DMLife"
    private const val PREFS = "dm_car_life"

    /** AA auto-starts ~2.4 s after connecting; a first session this soon is AA's doing. */
    const val AUTO_START_MS = 15_000L

    /** Used by the media autoplay guard / connect fallback. */
    const val CONNECT_WINDOW_MS = 15_000L

    /** AUTO sessions call finishCarApp() right away (AA shows its app grid). */
    const val FINISH_ON_AUTO_START = true

    enum class Kind { AUTO, USER }

    class StartDecision(val kind: Kind, val reason: String) {
        val open: Boolean get() = kind == Kind.USER
    }

    /** elapsedRealtime when the current connection became PROJECTION (0 = unknown / none). */
    @Volatile
    var projectionSince: Long = 0L
        private set

    /** True when this process saw the current connection begin (not inferred / restored). */
    @Volatile
    private var projectionLive = false

    /** Id of the current connection; bumped on every new connection. */
    private var connectionId = 0L

    /** The connection whose first session has already been classified. */
    private var classifiedConnection = -1L
    /** postTime of the newest AA connection notification any decision has seen (epoch ms). */
    private var lastSeenAaNotif = 0L

    private var watching = false

    /** Latest CarConnection type (null until the first answer arrives). */
    @Volatile
    private var lastType: Int? = null

    private var appCtx: Context? = null

    /** Epoch ms of the current AA connection, persisted so it survives the process being killed. */
    private const val KEY_CONNECTED_AT = "dd_aa_connected_at"

    /** Post time of Android Auto's own notification seen while connected (second signal). */
    private const val KEY_GEARHEAD_AT = "dd_aa_gearhead_notif_at"
    const val GEARHEAD = "com.google.android.projection.gearhead"

    private fun epochToElapsed(epoch: Long): Long =
        maxOf(1L, SystemClock.elapsedRealtime() - (System.currentTimeMillis() - epoch))

    private fun newConnection(since: Long, why: String) {
        connectionId++
        projectionLive = false
        projectionSince = since
        lastType = CarConnection.CONNECTION_TYPE_PROJECTION
        if (since > 0L) {
            val epoch = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - since)
            appCtx?.let { prefs(it).edit().putLong(KEY_CONNECTED_AT, epoch).apply() }
        }
        Log.i(TAG, "new connection #$connectionId ($why)")
    }

    private fun clearPersisted() {
        appCtx?.let { prefs(it).edit().putLong(KEY_CONNECTED_AT, 0L).putLong(KEY_GEARHEAD_AT, 0L).apply() }
    }

    /** AA removed its connection notification: the connection ended. */
    fun onAaNotificationRemoved() {
        Log.i(TAG, "AA connection notification removed")
        clearPersisted()
    }

    /** DashMateMediaListenerService saw Android Auto's own notification (posted at connect). */
    fun noteGearheadNotification(ctx: Context, postTime: Long) {
        if (lastType == CarConnection.CONNECTION_TYPE_NOT_CONNECTED) return // tips etc. while not in the car
        val p = prefs(ctx)
        // Newer than any connect we know of: AA (re)connected while DashMate's process was dead.
        if (postTime > maxOf(p.getLong(KEY_GEARHEAD_AT, 0L), p.getLong(KEY_CONNECTED_AT, 0L))) {
            p.edit().putLong(KEY_GEARHEAD_AT, postTime).apply()
            Log.i(TAG, "AA notification posted at $postTime recorded")
        }
    }

    /** Watches AA connect/disconnect for the life of the process (the process outlives sessions). */
    fun watchConnection(app: Context) {
        if (watching) return
        watching = true
        appCtx = app.applicationContext
        try {
            CarConnection(app).type.observeForever { type ->
                val prev = lastType
                Log.i(TAG, "car connection type $prev -> $type")
                when {
                    type == CarConnection.CONNECTION_TYPE_NOT_CONNECTED -> {
                        lastType = type
                        projectionSince = 0L
                        projectionLive = false
                        clearPersisted()
                    }
                    prev == CarConnection.CONNECTION_TYPE_PROJECTION -> lastType = type
                    prev == null -> {
                        // AA was already connected when this process started: the start time is
                        // unknown here (AA's notification tells it, see decide()).
                        newConnection(0L, "already connected at process start")
                    }
                    else -> {
                        newConnection(SystemClock.elapsedRealtime(), "NOT_CONNECTED -> PROJECTION")
                        projectionLive = true
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "CarConnection watch failed: ${e.message}")
        }
    }

    /**
     * Another hint that AA just connected (its first media-browser bind after a gap). Used only
     * when CarConnection has not reported the connection (late or unsupported update).
     */
    fun noteConnectSignal(source: String) {
        if (lastType == CarConnection.CONNECTION_TYPE_PROJECTION) return
        val sawDisconnected = lastType == CarConnection.CONNECTION_TYPE_NOT_CONNECTED
        newConnection(SystemClock.elapsedRealtime(), "connect signal from $source")
        if (sawDisconnected) projectionLive = true   // this process watched the transition
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun markExit(ctx: Context) {
        prefs(ctx).edit().putLong("exited_at", System.currentTimeMillis()).apply()
    }

    fun markOpened(ctx: Context) {
        prefs(ctx).edit().putLong("opened_at", System.currentTimeMillis()).apply()
    }

    /** Largest full-screen surface seen at this dpi (the dashboard panel is much smaller). */
    fun fullArea(ctx: Context, dpi: Int): Long = prefs(ctx).getLong("full_area_$dpi", 0L)

    fun rememberFullArea(ctx: Context, dpi: Int, area: Long) {
        if (area > fullArea(ctx, dpi)) prefs(ctx).edit().putLong("full_area_$dpi", area).apply()
    }

    /** How long a new session may wait for a live signal before it is handed back (AUTO). */
    const val SIGNAL_WAIT_MS = 1_500L

    /**
     * Classifies a brand-new session from LIVE signals only. Returns null while they are still
     * missing (the screen retries for [SIGNAL_WAIT_MS], then calls [timeoutDecision]).
     * Priority: never auto-open > one tap. Saved times are never trusted on their own.
     */
    fun tryDecide(ctx: Context, intent: Intent, attempt: Int): StartDecision? {
        val typeBefore = lastType
        val aaNotif = DashMateMediaListenerService.aaConnectionPostTime() // null = listener not ready
        val d = decide(intent, aaNotif) ?: run {
            if (attempt == 0) log("WAIT", "no live signal yet", typeBefore, aaNotif, ctx)
            return null
        }
        log(d.kind.name, d.reason, typeBefore, aaNotif, ctx)
        return d
    }

    fun timeoutDecision(ctx: Context): StartDecision {
        claim(SystemClock.elapsedRealtime(), "no live signal within ${SIGNAL_WAIT_MS}ms")
        val d = StartDecision(Kind.AUTO, "no live signal within ${SIGNAL_WAIT_MS}ms")
        log(d.kind.name, d.reason, lastType, DashMateMediaListenerService.aaConnectionPostTime(), ctx)
        return d
    }

    private fun log(kind: String, reason: String, type: Int?, aaNotif: Long?, ctx: Context) {
        val now = SystemClock.elapsedRealtime()
        val connAge = if (projectionSince > 0L) now - projectionSince else -1L
        val notifAge = if (aaNotif != null && aaNotif > 0L) System.currentTimeMillis() - aaNotif else -1L
        val procAge = now - Process.getStartElapsedRealtime()
        Log.i(
            TAG,
            "start decision: $kind ($reason) connection #$connectionId [type=$type live=$projectionLive " +
                "connAge=${connAge}ms aaNotif=${aaNotif ?: "listener-off"} aaNotifAge=${notifAge}ms " +
                "saved=${prefs(ctx).getLong(KEY_CONNECTED_AT, 0L)} procAge=${procAge}ms]",
        )
    }

    /** Marks the current connection's first session as classified (creating the connection if needed). */
    private fun claim(since: Long, why: String) {
        if (lastType != CarConnection.CONNECTION_TYPE_PROJECTION || connectionId == 0L) {
            newConnection(since, why)
        } else if (projectionSince == 0L && since > 0L) {
            projectionSince = since
        }
        classifiedConnection = connectionId
    }

    private fun decide(intent: Intent, aaNotif: Long?): StartDecision? {
        val now = SystemClock.elapsedRealtime()
        // 1. Voice "navigate to ..." never opens the deck (checked first: a later session can be one too).
        if (intent.action == CarContext.ACTION_NAVIGATE) {
            claim(now, "navigation intent")
            return StartDecision(Kind.AUTO, "navigation intent (handed to Waze)")
        }
        // 1b. A quick unplug/replug or AA restart can skip the NOT_CONNECTED update, so the observer
        //     sees PROJECTION -> PROJECTION. A FRESH AA connection notification that is newer than any
        //     we have seen means a new connection anyway: AA is starting us on its own -> AUTO.
        if (aaNotif != null && aaNotif > 0L) {
            val age = System.currentTimeMillis() - aaNotif
            val isNewer = aaNotif > lastSeenAaNotif + 2_000L
            val seenBefore = lastSeenAaNotif > 0L
            lastSeenAaNotif = maxOf(lastSeenAaNotif, aaNotif)
            if (isNewer && seenBefore && age < AUTO_START_MS && classifiedConnection == connectionId) {
                newConnection(epochToElapsed(aaNotif), "fresh AA notification (missed disconnect)")
                projectionLive = true
                classifiedConnection = connectionId
                return StartDecision(Kind.AUTO, "first session, AA reconnected ${age}ms ago (missed disconnect)")
            }
        }
        // 2. A later session in a connection whose first session was already handled: the owner.
        if (lastType == CarConnection.CONNECTION_TYPE_PROJECTION && connectionId > 0L &&
            classifiedConnection == connectionId
        ) {
            return StartDecision(Kind.USER, "later session in this connection")
        }
        // 3. Live NOT_CONNECTED still showing: AA connected moments ago and is starting us.
        if (lastType == CarConnection.CONNECTION_TYPE_NOT_CONNECTED) {
            newConnection(now, "session before the connection signal")
            projectionLive = true
            classifiedConnection = connectionId
            return StartDecision(Kind.AUTO, "first session, before the connection signal")
        }
        // 4. This process saw the connection begin (NOT_CONNECTED -> PROJECTION, live).
        if (lastType == CarConnection.CONNECTION_TYPE_PROJECTION && projectionLive && projectionSince > 0L) {
            classifiedConnection = connectionId
            val age = now - projectionSince
            return if (age < AUTO_START_MS) {
                StartDecision(Kind.AUTO, "first session, ${age}ms after the live connect")
            } else {
                StartDecision(Kind.USER, "first session, ${age}ms after the live connect")
            }
        }
        // 5. Fresh process: Android Auto's own connection notification (active right now).
        if (aaNotif != null) {
            if (aaNotif == 0L) {
                claim(now, "no AA connection notification")
                return StartDecision(Kind.AUTO, "first session, no active AA connection notification")
            }
            val age = System.currentTimeMillis() - aaNotif
            claim(epochToElapsed(aaNotif), "AA connection notification")
            return if (age < AUTO_START_MS) {
                StartDecision(Kind.AUTO, "first session, AA connected ${age}ms ago (its notification)")
            } else {
                StartDecision(Kind.USER, "first session, AA connected ${age}ms ago (its notification)")
            }
        }
        return null // wait for the listener / CarConnection
    }

    /** "Navigate to ..." sent to DashMate goes to Waze / Google Maps (CarNav). */
    fun forwardNavigation(ctx: Context, intent: Intent) {
        val uri: Uri = intent.data ?: return
        val ssp = uri.schemeSpecificPart ?: return
        val q = Regex("(?:^|[?&])q=([^&]*)").find(ssp)?.groupValues?.get(1)?.let { Uri.decode(it) }?.trim()
        val app = ctx.applicationContext
        val coords = (if (q.isNullOrEmpty()) ssp.substringBefore('?') else q).split(',')
        val lat = coords.getOrNull(0)?.trim()?.toDoubleOrNull()
        val lng = coords.getOrNull(1)?.trim()?.substringBefore('(')?.trim()?.toDoubleOrNull()
        when {
            lat != null && lng != null && coords.size == 2 && (lat != 0.0 || lng != 0.0) -> CarNav.toCoords(app, lat, lng)
            !q.isNullOrEmpty() -> CarNav.toQuery(app, q)
            else -> Log.i(TAG, "navigation intent without a destination: $uri")
        }
    }
}

/**
 * Starts [DashMateCarLife.watchConnection] when the process starts (content providers are created
 * before anything else), so the moment Android Auto connects is known before AA binds the car app.
 * The process is usually alive already (notification listener), long before the phone connects.
 */
class DashMateCarLifeInit : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.applicationContext?.let { DashMateCarLife.watchConnection(it) }
        return true
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<out String>?): Int = 0
}
