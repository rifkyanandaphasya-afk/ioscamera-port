package com.example.ioscamera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/** Memantau gerakan HP lewat giroskop (rad/s) untuk "steady shot": rana menunggu HP tenang. */
class Steady(ctx: Context) : SensorEventListener {
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyro: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    @Volatile var motion = 0f
    val available: Boolean get() = gyro != null

    fun start() { gyro?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } }
    fun stop() { sm.unregisterListener(this) }

    override fun onSensorChanged(e: SensorEvent) {
        val m = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
        motion = motion * 0.7f + m * 0.3f
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
}
