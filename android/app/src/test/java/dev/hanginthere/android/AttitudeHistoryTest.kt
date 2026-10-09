package dev.hanginthere.android

import dev.hanginthere.android.camera.AttitudeHistory
import dev.hanginthere.core.PhoneOrientationStability
import org.junit.Assert.*
import org.junit.Test

class AttitudeHistoryTest {
    private val identity = requireNotNull(PhoneOrientationStability.Quaternion.create(0.0, 0.0, 0.0, 1.0))
    @Test fun delayedCameraCallbackUsesCaptureTimeMotion() {
        val history = AttitudeHistory()
        history.add(AttitudeHistory.Entry(identity, 10.0))
        history.add(AttitudeHistory.Entry(identity, 10.06))
        assertEquals(10.0, requireNotNull(history.atOrBefore(10.01)).seconds, 0.0)
        assertNull(history.atOrBefore(9.99)); assertNull(history.atOrBefore(10.7))
    }
    @Test fun historyRejectsReorderingAndBoundsMemoryAndAge() {
        val history = AttitudeHistory(capacity = 2)
        history.add(AttitudeHistory.Entry(identity, 1.0)); history.add(AttitudeHistory.Entry(identity, 1.1))
        history.add(AttitudeHistory.Entry(identity, 1.2)); history.add(AttitudeHistory.Entry(identity, 1.15))
        assertNull(history.atOrBefore(1.0)); assertEquals(1.1, requireNotNull(history.atOrBefore(1.15)).seconds, 0.0)
        history.add(AttitudeHistory.Entry(identity, 5.0)); assertNull(history.atOrBefore(1.2))
        history.clear(); assertNull(history.atOrBefore(5.0))
    }
}
