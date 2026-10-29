package dev.hanginthere.evaluation

import dev.hanginthere.core.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.Assert.*
import java.io.File

class BodyDipProductionReplayTests {
    /** Explicit development replay. Saved native body observations are reused;
     * camera.ready is authored isolation, never physical-camera qualification.
     * Frozen reference windows score outputs only and cannot affect detection.
     */
    @Test fun compareProductionMethodAndSetOwnerWithFrozenBodyExperiment() {
        val path=System.getenv("BODY_DIP_POSES")
        org.junit.Assume.assumeTrue("Opt-in cached native body-dip observations are required",path!=null)
        val output=checkNotNull(System.getenv("BODY_DIP_REPORT"))
        val json=WorkoutRecordCodec.json
        val poses=File(requireNotNull(path)).readLines().filter { it.isNotBlank() }.map { json.decodeFromString<PoseResult>(it) }
        val experiment=DipBodyMotionExperiment()
        val motion=BodyDipMotion()
        val rows=poses.map { p ->
            val old=experiment.observe(p);val current=motion.observe(p,BodyDipMotion.Camera.ready)
            assertEquals("Phase at ${p.timestamp.seconds}",old.phase.name,current.phase.name)
            assertEquals("Count at ${p.timestamp.seconds}",old.cycles,current.cycles)
            assertEquals(old.event?.counted,current.event?.takeIf { it.outcome==BodyDipMotion.Outcome.imageCycle }?.seconds)
            current
        }
        val snapshots=mutableListOf<BodyDipWorkout.Snapshot>()
        val sets=mutableListOf<WorkoutSetRecord>()
        var owner=BodyDipWorkout("source-set-1")
        for(p in poses) {
            val snapshot=owner.observe(p,BodyDipMotion.Camera.ready)
            snapshots+=snapshot
            if(owner.isFinished) { sets+=owner.record();owner=BodyDipWorkout("source-set-${sets.size+1}") }
        }
        if(owner.hasStarted) { owner.finish("sourceEnded");sets+=owner.record() }
        sets.forEach { set -> WorkoutLibrary.validate(WorkoutLibraryState(entries=listOf(
            WorkoutLibraryEntry(WorkoutLibrary.compact(set))))) }
        val referencePath=System.getenv("BODY_DIP_REFERENCE")
        val controls=referencePath?.let {
            val reference=json.parseToJsonElement(File(it).readText()).jsonObject
            val expected=reference.getValue("expectedCycles").jsonPrimitive.int
            assertEquals(expected,rows.last().cycles)
            assertEquals(expected,sets.sumOf { it.completedCount })
            reference["expectedSets"]?.jsonPrimitive?.int?.let { assertEquals(it,sets.size) }
            reference["expectedEndReason"]?.jsonPrimitive?.content?.let { reason ->
                assertTrue(sets.all { it.endReason==reason })
            }
            reference.getValue("zeroCountWindows").jsonArray.map { window ->
                val w=window.jsonObject
                val a=w.getValue("start").jsonPrimitive.double;val b=w.getValue("end").jsonPrimitive.double
                val isolated=BodyDipWorkout("control")
                val source=poses.filter { it.timestamp.seconds in a..b }
                source.forEach { isolated.observe(it,BodyDipMotion.Camera.ready,false) }
                assertEquals(0,isolated.record().completedCount)
                val events=rows.count { r -> r.seconds?.let { it in a..b }==true && r.event?.outcome==BodyDipMotion.Outcome.imageCycle }
                assertEquals(0,events)
                buildJsonObject { put("name",w.getValue("name"));put("frames",source.size)
                    put("wholeRecordingCycles",events);put("isolatedCycles",isolated.record().completedCount)
                    put("isolatedAutomaticStart",isolated.hasStarted) }
            }
        } ?: emptyList()
        val report=buildJsonObject {
            put("method",BodyDipMotion.countMethod);put("version",BodyDipMotion.policyVersion)
            put("scope","Cached native development poses; authored ready camera, no new inference or live camera qualification. Source is exposed development data.")
            put("frames",poses.size);put("originalExperimentCountsAndPhasesIdentical",true)
            put("completedCycles",sets.sumOf { it.completedCount });put("sets",json.encodeToJsonElement(sets))
            put("snapshots",json.encodeToJsonElement(snapshots));put("negativeControls",JsonArray(controls))
        }
        File(output).writeText(Json { prettyPrint=true }.encodeToString(report))
    }
}
