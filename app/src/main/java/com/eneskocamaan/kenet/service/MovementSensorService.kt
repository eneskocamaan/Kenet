package com.eneskocamaan.kenet.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import kotlin.math.abs
import kotlin.math.sqrt

class MovementSensorService : Service(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    companion object {
        @Volatile
        var currentMovementScore: Int = 100
            private set
    }

    private var lastUpdate: Long = 0
    private val movementHistory = ArrayDeque<Float>()
    private val HISTORY_SIZE = 50

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val curTime = System.currentTimeMillis()
            if ((curTime - lastUpdate) > 100) {
                lastUpdate = curTime
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val acceleration = sqrt((x*x + y*y + z*z).toDouble()).toFloat()
                val delta = abs(acceleration - 9.8f)
                updateMovementScore(delta)
            }
        }
    }

    private fun updateMovementScore(delta: Float) {
        if (movementHistory.size >= HISTORY_SIZE) movementHistory.removeFirst()
        movementHistory.add(delta)
        val avgDelta = movementHistory.average()
        currentMovementScore = when {
            avgDelta < 0.2 -> 0
            avgDelta < 0.5 -> 10
            avgDelta < 1.0 -> 30
            avgDelta < 2.0 -> 60
            else -> 100
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
    }
}