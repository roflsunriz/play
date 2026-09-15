package io.github.playmusic.data.playback

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.AtomicFile
import androidx.core.net.toUri
import androidx.media3.common.Player
import io.github.playmusic.R
import io.github.playmusic.PlayApplication
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class SleepTimerState(
    val deadlineMillis: Long? = null,
    val fading: Boolean = false,
    val canLock: Boolean = false,
    val canSchedule: Boolean = false,
    val lockSupported: Boolean = true,
    val error: Int? = null,
)

/** Uses an OS alarm so an ended queue or a reclaimed playback service cannot lose the timer. */
class SleepTimerManager(context: Context) {
    private val context = context.applicationContext
    private val alarms = this.context.getSystemService(AlarmManager::class.java)
    private val policy = this.context.getSystemService(DevicePolicyManager::class.java)
    private val admin = ComponentName(this.context, SleepTimerAdminReceiver::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(SleepTimerState())
    val state = mutableState.asStateFlow()
    private var record: Record? = null
    private var loaded = false
    private var player: Player? = null
    private var expiry: Job? = null

    init { scope.launch { refresh() } }

    fun attach(player: Player) { this.player = player }
    fun detach(player: Player) { if (this.player === player) this.player = null }

    fun lockPermissionIntent(): Intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
        .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, context.getString(R.string.sleep_timer_lock_explanation))

    fun alarmPermissionIntent(): Intent = Intent(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM else Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData("package:${context.packageName}".toUri())

    suspend fun refresh() = withContext(Dispatchers.Main.immediate) {
        mutex.withLock {
            try {
                load()
                if (record != null && !canSchedule()) {
                    // Android deletes exact alarms when this permission is revoked.
                    persist(null)
                    record = null
                    publish(R.string.sleep_timer_alarm_required)
                } else publish(mutableState.value.error)
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                publish(R.string.sleep_timer_storage_error)
            }
        }
    }

    suspend fun schedule(request: SleepTimerRequest): Boolean = withContext(Dispatchers.Main.immediate + NonCancellable) {
        if (!request.isValid) { publish(R.string.sleep_timer_invalid); return@withContext false }
        expiry?.cancelAndJoin()
        mutex.withLock {
            try {
                load()
                if (!canLock()) { publish(R.string.sleep_timer_lock_required); return@withLock false }
                if (!canSchedule()) { publish(R.string.sleep_timer_alarm_required); return@withLock false }
                val now = System.currentTimeMillis()
                val deadline = request.deadline(now)
                val next = Record(UUID.randomUUID().toString(), deadline,
                    if (request.mode == SleepTimerMode.DURATION) SystemClock.elapsedRealtime() + deadline - now else null,
                    bootCount())
                // Persist before scheduling; the alarm may restart this process after the queue ends.
                persist(next)
                val previous = record
                try {
                    alarms.setExactAndAllowWhileIdle(
                        if (next.elapsedMillis != null) AlarmManager.ELAPSED_REALTIME_WAKEUP else AlarmManager.RTC_WAKEUP,
                        next.elapsedMillis ?: next.deadlineMillis, pending(next.id),
                    )
                } catch (exception: Exception) {
                    persist(previous)
                    throw exception
                }
                record = next
                previous?.let { alarms.cancel(pending(it.id)) }
                publish()
                true
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                publish(if (exception is SecurityException) R.string.sleep_timer_alarm_required else R.string.sleep_timer_storage_error)
                false
            }
        }
    }

    suspend fun cancel(): Boolean = withContext(Dispatchers.Main.immediate + NonCancellable) {
        expiry?.cancelAndJoin()
        mutex.withLock {
            try {
                load()
                val previous = record
                persist(null)
                record = null
                previous?.let { alarms.cancel(pending(it.id)) }
                publish()
                true
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                publish(R.string.sleep_timer_storage_error)
                false
            }
        }
    }

    internal suspend fun expire(id: String) = withContext(Dispatchers.Main.immediate) {
        val currentJob = currentCoroutineContext().job
        val accepted = try {
            mutex.withLock {
                load()
                val scheduled = record
                val due = scheduled != null && if (scheduled.elapsedMillis != null)
                    scheduled.elapsedMillis <= SystemClock.elapsedRealtime() else scheduled.deadlineMillis <= System.currentTimeMillis()
                if (scheduled?.id != id || !due || expiry?.isActive == true) false
                else { expiry = currentJob; mutableState.value = mutableState.value.copy(fading = true); true }
            }
        } catch (exception: Exception) {
            if (exception is CancellationException) throw exception
            publish(R.string.sleep_timer_storage_error)
            false
        }
        if (!accepted) return@withContext
        try {
            val current = player
            val playback = current?.let { target -> object : SleepTimerPlayback {
                override val active: Boolean get() = player === target && if (target is TransitionPlayer)
                    target.hasActiveAudioOrPendingPlayback() else target.playWhenReady &&
                    target.playbackState != Player.STATE_ENDED && target.playbackState != Player.STATE_IDLE
                override var volume: Float
                    get() = if (player === target) target.volume else 1f
                    set(value) { if (player === target) target.volume = value }
                override fun stop() { if (player === target) {
                    if (target is TransitionPlayer) target.stopImmediately() else { target.pause(); target.stop() }
                } }
            } }
            (current as? TransitionPlayer)?.setSleepFading(true)
            try {
                val duration = (context.applicationContext as PlayApplication).container
                    .playbackTransitions.state.value.settings.fadeOutSeconds * 1_000L
                fadeForSleep(playback, duration) { check(canLock()); policy.lockNow() }
            } finally { (current as? TransitionPlayer)?.setSleepFading(false) }
            mutex.withLock {
                persist(null)
                record = null
                publish()
            }
        } catch (exception: Exception) {
            if (exception is CancellationException) throw exception
            mutex.withLock {
                record = null
                try { persist(null) } catch (_: Exception) { /* Report completion failure below, including cleanup failure. */ }
                publish(R.string.sleep_timer_completion_error)
            }
        } finally {
            if (currentJob.isCancelled) withContext(NonCancellable) {
                mutex.withLock {
                    if (record?.id == id) {
                        record = null
                        try { persist(null); publish() }
                        catch (_: Exception) { publish(R.string.sleep_timer_storage_error) }
                    }
                }
            }
            if (expiry === currentJob) expiry = null
            mutableState.value = mutableState.value.copy(fading = false)
        }
    }

    private fun canLock(): Boolean = lockSupported() && policy.isAdminActive(admin)
    private fun lockSupported(): Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_DEVICE_ADMIN) &&
        !context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
    private fun canSchedule(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
    private fun bootCount(): Int = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    private fun publish(error: Int? = null) {
        mutableState.value = SleepTimerState(record?.deadlineMillis, expiry?.isActive == true,
            canLock(), canSchedule(), lockSupported(), error)
    }

    private fun pending(id: String): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, SleepTimerAlarmReceiver::class.java).setData("play-sleep://timer/$id".toUri())
            .putExtra("timerId", id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private suspend fun load() {
        if (loaded) return
        val saved = withContext(Dispatchers.IO) {
            val file = storage()
            if (!file.baseFile.exists()) null else {
                try {
                    val data = JSONObject(file.readFully().toString(Charsets.UTF_8))
                    require(data.getInt("version") == 1)
                    Record(data.getString("id"), data.getLong("deadline"),
                        if (data.has("elapsed")) data.getLong("elapsed") else null, data.getInt("boot"))
                } catch (exception: Exception) {
                    // A broken/unsupported timer must not prevent the user from replacing it.
                    if (exception is CancellationException) throw exception
                    file.delete()
                    throw exception
                }
            }
        }
        // Android removes alarms at reboot. Do not display an alarm that no longer exists.
        record = saved?.takeIf { it.boot == bootCount() }
        if (saved != null && record == null) persist(null)
        loaded = true
    }

    private suspend fun persist(value: Record?) = withContext(Dispatchers.IO) {
        val file = storage()
        if (value == null) {
            file.delete()
            check(!file.baseFile.exists()) { "Cannot clear sleep timer" }
            return@withContext
        }
        val bytes = JSONObject().put("version", 1).put("id", value.id).put("deadline", value.deadlineMillis)
            .put("boot", value.boot).apply { value.elapsedMillis?.let { put("elapsed", it) } }
            .toString().toByteArray(Charsets.UTF_8)
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (exception: Exception) { file.failWrite(output); throw exception }
    }

    private fun storage() = AtomicFile(File(context.noBackupFilesDir, "sleep-timer.json"))
    private data class Record(val id: String, val deadlineMillis: Long, val elapsedMillis: Long?, val boot: Int)
}
