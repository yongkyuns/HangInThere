package dev.hanginthere.android.ui

import android.os.SystemClock
import android.util.Log
import dev.hanginthere.android.BuildConfig
import dev.hanginthere.android.session.WorkoutRecordStore
import java.util.concurrent.atomic.AtomicLong

/** Content-free lifecycle evidence; no record IDs, paths, values or errors. */
internal data class LibraryLoadEvent(
    val stage: String,
    val owner: Int,
    val composition: Long,
    val request: Int,
    val elapsedNanos: Long,
)

internal class LibraryLoadDiagnostics(store: WorkoutRecordStore) {
    private val owner = System.identityHashCode(store)
    private val composition = nextComposition.incrementAndGet()
    private var request = 0
    private var assignedRequest = 0
    private var published: Pair<String, Int>? = null

    fun beginRequest(): LibraryLoadEvent { request += 1; assignedRequest = request; return event("load.enter") }

    fun assigned(request: Int) { assignedRequest = request }

    fun event(stage: String, request: Int = this.request): LibraryLoadEvent = LibraryLoadEvent(stage, owner, composition,
        request, SystemClock.elapsedRealtimeNanos()).also {
        if (BuildConfig.DEBUG) Log.d(TAG,
            "owner=$owner composition=$composition request=$request stage=$stage at=${it.elapsedNanos}")
    }

    fun publicationRequest(): Int = assignedRequest

    fun publication(status: String, request: Int): LibraryLoadEvent? {
        val key = status to request
        if (published == key) return null
        published = key
        return event("published.$status", request)
    }

    companion object {
        private const val TAG = "WorkoutLibraryLoad"
        private val nextComposition = AtomicLong()

        // The default loader keeps its existing signature and dispatcher. Match
        // IO stages by owner and monotonic interval; do not invent a request ID
        // when overlapping invocations make that association ambiguous.
        fun io(stage: String, store: WorkoutRecordStore) {
            if (BuildConfig.DEBUG) Log.d(TAG,
                "owner=${System.identityHashCode(store)} stage=$stage at=${SystemClock.elapsedRealtimeNanos()}")
        }
    }
}
