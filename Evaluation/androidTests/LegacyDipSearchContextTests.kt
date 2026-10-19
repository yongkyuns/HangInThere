package dev.hanginthere.evaluation

import dev.hanginthere.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Real source observations, not new SDK inference or physical qualification. */
class LegacyDipSearchContextTests {
    private val json = WorkoutRecordCodec.json
    private val cases get() = checkNotNull(javaClass.getResourceAsStream("/legacy-dip-search-context.json")) {
        "Missing packaged evaluation fixture: legacy-dip-search-context.json"
    }.bufferedReader().use { json.parseToJsonElement(it.readText()) }
        .jsonObject.getValue("cases").jsonArray.map { it.jsonObject }
    private inline fun <reified T> JsonObject.value(key: String): T = json.decodeFromJsonElement(getValue(key))
    private fun close(a: BarRegion, b: BarRegion) {
        for ((x, y) in listOf(a.minX to b.minX, a.minY to b.minY, a.maxX to b.maxX, a.maxY to b.maxY))
            assertEquals(x, y, 1e-9)
    }
    private fun selectionMatches(case: JsonObject, prefix: String, arm: PullUpArmMeasurement.Arm, size: ImageSize): BarSegment? {
        val selected = AutomaticDipRails.select(case.value<List<BarLineCandidate>>("${prefix}Candidates"),
            arm.wrist, size, requireUnique = true)
        val reference = case.value<BarSegment?>("${prefix}Selected")
        if (reference == null) assertNull("$prefix context must retain an unresolved selection", selected)
        else {
            assertNotNull("$prefix context lost its frozen source selection", selected)
            assertTrue(AutomaticReferenceStability.sameLine(reference, selected!!, 1e-8))
        }
        return selected
    }

    @Test fun nativeRailAndClutterRegressionsRetainAllCandidatesAndPointRoles() {
        assertEquals(4, cases.size)
        for (case in cases) {
            val pose = case.value<PoseResult>("pose")
            val side = case.value<ArmMeasurement.Side>("side")
            val arm = PullUpArmMeasurement(pose).arms.single { it.side == side }
            val expected = case.value<BarRegion>("outwardRegion")
            val actual = DipRailSearchGeometryBridge().region(arm.shoulder.x, arm.shoulder.y, arm.wrist.x, arm.wrist.y,
                pose.imageSize.width, pose.imageSize.height, arm.estimate.upperArmPixels)!!
            close(expected, actual)
            selectionMatches(case, "original", arm, pose.imageSize)
            val candidates = case.value<List<BarLineCandidate>>("outwardCandidates")
            val selected = selectionMatches(case, "outward", arm, pose.imageSize)
            val reference = case.value<BarSegment?>("outwardSelected")
            if (reference == null) assertNull("New context must not turn clothing/hand ambiguity into support", selected)
            else {
                assertNotNull(selected)
                assertTrue(AutomaticReferenceStability.sameLine(reference, selected!!, 1e-8))
                val original = case.value<BarRegion>("originalRegion")
                assertFalse(original.contains(reference.a) && original.contains(reference.b))
                assertTrue(actual.contains(reference.a) && actual.contains(reference.b))
                val search = AutomaticApparatusReference.search(pose, ExerciseCounter.Exercise.dip)!!
                assertEquals(listOf(side), search.gripSides)
                close(actual, search.regions.single())
                // One actual source frame supplies no synthetic acquisition dwell.
                assertNull(AutomaticSingleDipRail().observe(pose, side, candidates))
            }
            assertEquals(2, DipRailSearchGeometryBridge().policyVersion)
        }
    }

    @Test fun actualMeasuredArmSearchMirrorsWithoutChangingEligibilityOrCentralBar() {
        for (case in cases) {
            val pose = case.value<PoseResult>("pose"); val width = pose.imageSize.width
            val mirrored = pose.copy(people = pose.people.map { person -> person.copy(landmarks = person.landmarks.map {
                it.copy(position = Point2D(width - it.position.x, it.position.y))
            }) })
            val original = AutomaticApparatusReference.search(pose, ExerciseCounter.Exercise.dip)!!
            val actual = AutomaticApparatusReference.search(mirrored, ExerciseCounter.Exercise.dip)!!
            assertEquals(original.gripSides, actual.gripSides)
            for ((a, b) in original.regions.zip(actual.regions))
                close(BarRegion(width - a.maxX, a.minY, width - a.minX, a.maxY), b)
            if (original.gripSides.size == 2) {
                val hands = original.hands; val short = minOf(pose.imageSize.width, pose.imageSize.height)
                close(BarRegion((hands.minOf { it.x } - short * .16).coerceAtLeast(0.0),
                    (hands.minOf { it.y } - short * .08).coerceAtLeast(0.0),
                    (hands.maxOf { it.x } + short * .16).coerceAtMost(width),
                    (hands.maxOf { it.y } + short * .16).coerceAtMost(pose.imageSize.height)), original.regions.last())
            }
        }
    }
}
