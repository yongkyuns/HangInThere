package dev.hanginthere.android.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import dev.hanginthere.core.PhoneOrientationStability
import java.io.Closeable

/** Sensor and CameraX timestamps share elapsed realtime; stale/missing sensor values fail closed. */
class PhoneAttitude(context: Context) : SensorEventListener, Closeable {
    data class Sample(val quaternion: PhoneOrientationStability.Quaternion, val seconds: Double)
    private val history = AttitudeHistory()
    private val sensors = context.getSystemService(SensorManager::class.java)
    @Volatile var latest: Sample? = null; private set
    private val sensor = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    fun start() { latest = null; history.clear(); sensor?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } }
    override fun onSensorChanged(event: SensorEvent) {
        val quaternion = FloatArray(4); SensorManager.getQuaternionFromVector(quaternion, event.values)
        latest = Sample(PhoneOrientationStability.Quaternion.create(quaternion[1].toDouble(), quaternion[2].toDouble(), quaternion[3].toDouble(), quaternion[0].toDouble()) ?: return, event.timestamp / 1e9)
        latest?.let { history.add(AttitudeHistory.Entry(it.quaternion, it.seconds)) }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    fun sampleAt(sourceSeconds: Double): Sample? = history.atOrBefore(sourceSeconds)?.let { Sample(it.quaternion, it.seconds) }
    override fun close() { sensors.unregisterListener(this); latest = null; history.clear() }
}
