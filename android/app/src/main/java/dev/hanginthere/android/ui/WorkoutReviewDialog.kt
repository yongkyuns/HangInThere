package dev.hanginthere.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.hanginthere.core.*
import java.util.Locale
import java.util.Date
import java.text.DateFormat

/** Read-only: presentation does not send pause/configuration controls to inference. */
@Composable
fun WorkoutReviewDialog(current: List<RepRecord>, history: List<WorkoutSetRecord>, error: String?, onClose: () -> Unit) {
    var selected by remember { mutableStateOf<String?>(null) }
    val completedCurrent = current.filter { it.isCompleteForAnalytics }
    val completedHistory = history.filter { it.completedCount > 0 }
    val reps = if (current.isEmpty()) completedHistory.firstOrNull()?.completedReps.orEmpty() else completedCurrent
    val all = (completedCurrent + completedHistory.flatMap { it.completedReps }).distinctBy { it.id }
    val rep = all.firstOrNull { it.id == selected } ?: reps.lastOrNull()
    AlertDialog(onDismissRequest = onClose, title = { Text("Workout analysis") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (reps.isEmpty() && rep == null) Text("Complete a pull-up or dip to see its timing and angle measurements.")
            if (reps.isNotEmpty()) {
                Text("Rep timing", style = MaterialTheme.typography.titleMedium)
                Text("Blue: outward · orange: return · tap a rep to inspect")
                Text("Only completed reps are included; unfinished attempts are excluded.")
                val maximum = maxOf(1.0, reps.maxOf { (it.outboundSeconds ?: 0.0) + (it.returnSeconds ?: 0.0) })
                reps.forEach { item ->
                    Text("Rep ${item.ordinal} · ${seconds(item.outboundSeconds)} / ${seconds(item.returnSeconds)}",
                        Modifier.clickable { selected = item.id })
                    Canvas(Modifier.fillMaxWidth().height(14.dp).clickable { selected = item.id }.semantics {
                        contentDescription = "Rep ${item.ordinal}, outward ${seconds(item.outboundSeconds)}, return ${seconds(item.returnSeconds)}"
                    }) {
                        val outward = (item.outboundSeconds ?: 0.0) / maximum * size.width
                        val back = (item.returnSeconds ?: 0.0) / maximum * size.width
                        drawLine(Color.Cyan, Offset.Zero.copy(y = size.height / 2), Offset(outward.toFloat(), size.height / 2), size.height, StrokeCap.Butt)
                        drawLine(Color(0xFFFF9800), Offset(outward.toFloat(), size.height / 2), Offset((outward + back).toFloat(), size.height / 2), size.height, StrokeCap.Butt)
                    }
                }
            }
            rep?.let { item ->
                Text("Rep ${item.ordinal} · ${item.outcome}", style = MaterialTheme.typography.titleMedium)
                if(item.samples.isEmpty()) Text("Detailed motion is unavailable. The rep summary is preserved.") else AnglePlot(item)
                Text("Time to extreme: ${seconds(item.outboundSeconds)}\nReturn: ${seconds(item.returnSeconds)}\n" +
                    "Left mean / range: ${angles(item.left)}\nRight mean / range: ${angles(item.right)}\n" +
                    "Observed duration: ${item.durationCoverage?.let { "${(it * 100).toInt()}%" } ?: "Unavailable"}")
                if (item.curveTruncated && item.samples.isNotEmpty()) Text("The curve shows recent motion; averages use all observed intervals.")
            }
            if (completedHistory.isNotEmpty()) Text("Saved sets", style = MaterialTheme.typography.titleMedium)
            completedHistory.forEach { set ->
                val ending=when(set.endReason){"disengaged" -> "Automatically completed";"manual" -> "Ended manually";else -> "Interrupted"}
                Text("${set.exercise.title} · ${set.completedCount} ${if(set.completedCount==1) "rep" else "reps"} · $ending", style = MaterialTheme.typography.titleSmall)
                set.trackedAtEpochMilliseconds?.let { time ->
                    Text(remember(time) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(Date(time)) }, style = MaterialTheme.typography.bodySmall)
                }
                set.completedReps.forEach { item -> Text("Rep ${item.ordinal} · ${seconds(item.outboundSeconds)} / ${seconds(item.returnSeconds)}",
                    Modifier.clickable { selected = item.id }) }
            }
            Text("Angles are projected camera measurements. Depth, lockout and exercise form are not verified.")
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text("Done") } })
}
@Composable
private fun AnglePlot(rep: RepRecord) {
    Text("Elbow angle (0–180°) over seconds · left blue / right orange")
    Canvas(Modifier.fillMaxWidth().height(180.dp).semantics { contentDescription = "Left and right elbow angles for rep ${rep.ordinal}; missing intervals are gaps" }) {
        val duration = maxOf(.001, rep.closedAt - rep.startedAt)
        fun point(sample: MotionSample, angle: Double) = Offset(((sample.seconds - rep.startedAt) / duration * size.width).toFloat(), ((1 - angle / 180) * size.height).toFloat())
        for (right in listOf(false, true)) {
            var previous: Pair<MotionSample, Double>? = null
            rep.samples.forEach { sample ->
                val angle = if (right) sample.rightDegrees else sample.leftDegrees
                if (!sample.usable || angle == null) previous = null
                else {
                    previous?.let { (old, value) -> if (sample.seconds - old.seconds <= ExerciseCounter.maximumGapSeconds)
                        drawLine(if (right) Color(0xFFFF9800) else Color.Cyan, point(old, value), point(sample, angle), 2.dp.toPx(), StrokeCap.Round) }
                    previous = sample to angle
                }
            }
        }
    }
    Text("0 s → ${seconds(rep.closedAt - rep.startedAt)}")
}
private fun seconds(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.2f s", it) } ?: "Unavailable"
private fun angles(value: AngleMetrics): String = if (value.mean != null && value.minimum != null && value.maximum != null)
    String.format(Locale.ROOT, "%.0f° / %.0f–%.0f°", value.mean, value.minimum, value.maximum) else "Unavailable"
