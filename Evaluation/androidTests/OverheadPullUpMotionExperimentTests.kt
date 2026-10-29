package dev.hanginthere.evaluation

import dev.hanginthere.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import kotlin.math.*

class OverheadPullUpMotionExperimentTests {
    private fun pose(ms: Long,rise: Double=0.0,degrees: Double=170.0,
        wristDrop: Double=0.0,omitted: Set<PoseJoint> = emptySet()): PoseResult {
        val height=350-rise;val offset=height/(2*tan(degrees*PI/360))
        val points=listOf(250.0,650.0).flatMapIndexed { i,x ->
            val chain=ArmMeasurement.Side.entries[i].joints
            listOf(Landmark(chain[0],Point2D(x,600-rise),.9),
                Landmark(chain[1],Point2D(x+if(i==0)-offset else offset,(600-rise+250+wristDrop)/2),.9),
                Landmark(chain[2],Point2D(x,250+wristDrop),.9))
        } + listOf(Landmark(PoseJoint.leftHip,Point2D(250.0,1000-rise),.9),
            Landmark(PoseJoint.rightHip,Point2D(650.0,1000-rise),.9))
        return PoseResult(PresentationTime(ms,1000),ImageSize(900.0,1400.0),
            listOf(PoseObservation(points.filterNot { it.joint in omitted })),"authored",1)
    }
    private fun cycle()=(0L..1000L step 40).map { pose(it) } +
        (1040L..1360L step 40).map { pose(it,140.0,100.0) } +
        (1400L..1800L step 40).map { pose(it) }
    private fun run(frames: List<PoseResult>,camera: (PoseResult)->Boolean={true}): List<OverheadPullUpMotionExperiment.Snapshot> {
        val selection=BodyAthleteSelection();val motion=OverheadPullUpMotionExperiment()
        return frames.map { selection.observe(it);motion.observe(selection,"source",camera(it)) }
    }
    @Test fun actualOverheadCycleNeedsNoApparatusReferenceAndStaticHandsDoNotCount() {
        val states=run(cycle());assertEquals(1,states.last().cycles)
        assertEquals(0,states.filter { it.seconds!! <= 1.0 }.maxOf { it.cycles })
        val event=states.mapNotNull { it.event }.single { it.outcome=="imageCycle" }
        assertTrue(event.departure!! <= event.peak!! && event.peak!! < event.seconds)
        assertEquals(0,run((0L..3000L step 40).map { pose(it) }).last().cycles)
    }
    @Test fun handMotionShallowBobsUnbentMotionAndMissingArmsCannotCount() {
        for (fault in listOf("shallow","handsOnly","unbent","missingWrist","missingShoulder","crowd")) {
            val frames=cycle().map { original ->
                if (original.timestamp.value !in 1040L..1360L) original else when(fault) {
                    "shallow" -> pose(original.timestamp.value,10.0,165.0)
                    "handsOnly" -> pose(original.timestamp.value,0.0,100.0,wristDrop=140.0)
                    "unbent" -> pose(original.timestamp.value,140.0,170.0)
                    "missingWrist" -> pose(original.timestamp.value,140.0,100.0,omitted=setOf(PoseJoint.rightWrist))
                    "missingShoulder" -> pose(original.timestamp.value,140.0,100.0,omitted=setOf(PoseJoint.rightShoulder))
                    else -> original.copy(people=original.people+original.people)
                }
            }
            assertEquals(fault,0,run(frames).last().cycles)
        }
    }
    @Test fun cameraLossSourceGapOrContextChangeCannotBridgeACompletion() {
        for (fault in listOf("camera","gap","backend","revision")) {
            val frames=cycle().map { p -> if (p.timestamp.value !in 1120L..1480L) p else when(fault) {
                "backend" -> p.copy(backend="new")
                "revision" -> p.copy(requestRevision=2)
                else -> p
            } }.filterNot { fault=="gap" && it.timestamp.value in 1120L..1480L }
            assertEquals(fault,0,run(frames) { fault!="camera" || it.timestamp.value !in 1120L..1480L }.last().cycles)
        }
    }
    @Test fun sourceRegressionCannotRewindOrCompleteAnAttempt() {
        val selection=BodyAthleteSelection();val motion=OverheadPullUpMotionExperiment()
        val prefix=cycle().filter { it.timestamp.value<=1360 }
        for (p in prefix) {selection.observe(p);motion.observe(selection,"a",true)}
        for (p in prefix) {
            selection.observe(p);val state=motion.observe(selection,"a",true)
            assertFalse(state.sourceAccepted);assertEquals(0,state.cycles)
        }
    }
    @Test fun positiveReleaseNeedsBothActualWristsAndShouldersButNoElbowInference() {
        val selection=BodyAthleteSelection();val motion=OverheadPullUpMotionExperiment()
        for (p in (0L..1000L step 40).map { pose(it) }) {selection.observe(p);motion.observe(selection,"a",true)}
        val held=checkNotNull(motion.measuredSupport)
        val release=pose(1040,wristDrop=700.0,omitted=setOf(PoseJoint.leftElbow,PoseJoint.rightElbow))
        assertTrue(OverheadPullUpMotionExperiment.positiveRelease(release,held))
        assertFalse(OverheadPullUpMotionExperiment.positiveRelease(pose(1040),held))
        assertFalse(OverheadPullUpMotionExperiment.positiveRelease(release.copy(people=release.people.map {
            it.copy(landmarks=it.landmarks.filterNot { p->p.joint==PoseJoint.leftWrist }) }),held))
    }
    @Test fun measuredHorizontalTorsoCannotPrepareAnOverheadWorkout() {
        val frames=cycle().map { p -> p.copy(people=p.people.map { person -> person.copy(landmarks=person.landmarks.map {
            when(it.joint) {
                PoseJoint.leftHip -> it.copy(position=Point2D(700.0,600.0))
                PoseJoint.rightHip -> it.copy(position=Point2D(850.0,600.0))
                else -> it
            }
        }) }) }
        assertEquals(0,run(frames).last().cycles)
    }
    @Test fun episodeCannotBeginAfterItsObservedFirstDeparture() {
        val selection=BodyAthleteSelection();val session=OverheadPullUpSessionExperiment("source")
        for(p in cycle()) { selection.observe(p);session.observe(selection,true) }
        val episode=checkNotNull(session.episode())
        assertEquals(1,episode.events.size)
        assertTrue("Source-owned start ${episode.startedAt} must precede observed departure ${episode.events[0].departure}",
            episode.startedAt<=episode.events[0].departure!!)
    }
    @Test fun shouldersMayRisePastFixedHandsWithoutLosingTheObservedCycle() {
        val frames=(0L..1000L step 40).map { pose(it) } +
            (1040L..1360L step 40).map { pose(it,370.0,35.0) } +
            (1400L..1800L step 40).map { pose(it) }
        assertEquals(1,run(frames).last().cycles)
    }
    @Test fun lowerPreparationRefinesActualSupportScaleBeforeFirstDeparture() {
        val selection=BodyAthleteSelection();val motion=OverheadPullUpMotionExperiment()
        for(p in (0L..1000L step 40).map { pose(it,230.0,110.0) } +
            (1040L..1800L step 40).map { pose(it) }) {
            selection.observe(p);motion.observe(selection,"source",true)
        }
        val actual=checkNotNull(motion.measuredSupport).arms[0].length
        val measured=ArmMeasurement(pose(1800),ArmMeasurement.Side.left).estimate!!
        assertEquals(measured.upperArmPixels+measured.forearmPixels,actual,1e-6)
        for(p in (1840L..2160L step 40).map { pose(it,140.0,100.0,wristDrop=20.0) } +
            (2200L..2600L step 40).map { pose(it) }) {
            selection.observe(p);motion.observe(selection,"source",true)
        }
        selection.observe(pose(2640));assertEquals(1,motion.observe(selection,"source",true).cycles)
    }
    @Test fun categoricalReleaseUsesExistingTorsoCueAndElbowMissingFallbackKeepsOneSecond() {
        for(missingElbows in listOf(false,true)) {
            val selection=BodyAthleteSelection();val owner=OverheadPullUpSessionExperiment("source")
            for(p in cycle()) {selection.observe(p);owner.observe(selection,true)}
            for(ms in listOf(2000L,2100,2200,2249,2250)) {
                val p=pose(ms,wristDrop=700.0,omitted=if(missingElbows)setOf(PoseJoint.leftElbow,PoseJoint.rightElbow) else emptySet())
                selection.observe(p);owner.observe(selection,true)
                assertEquals("at $ms, missingElbows=$missingElbows",ms==2250L && !missingElbows,owner.isFinished)
            }
            if(missingElbows) for(ms in listOf(2400L,2500,2600,2700,2800,2900,3000)) {
                val p=pose(ms,wristDrop=700.0,omitted=setOf(PoseJoint.leftElbow,PoseJoint.rightElbow))
                selection.observe(p);owner.observe(selection,true)
            }
            assertEquals("disengaged",owner.episode()!!.endReason)
            assertEquals(1,owner.completed)
        }
    }
    private fun displacedWrists(p: PoseResult, slots: Set<Int>,horizontal: Boolean=false): PoseResult = p.copy(people=p.people.map { person ->
        person.copy(landmarks=person.landmarks.map { point ->
            val slot=ArmMeasurement.Side.entries.indexOfFirst { it.joints[2]==point.joint }
            if(slot in slots) point.copy(position=if(horizontal)Point2D(point.position.x+60,point.position.y)
                else Point2D(point.position.x,point.position.y+60)) else point
        })
    })
    @Test fun oneNoisyWristRetainsTheSameMeasuredOppositeSupportAcrossCycles() {
        val frames=cycle()+(1840L..2160L step 40).map { pose(it,140.0,100.0) }+
            (2200L..2600L step 40).map { pose(it) }
        val states=run(frames.map { if(it.timestamp.value>=1120) displacedWrists(it,setOf(0)) else it })
        assertEquals("A single wrist estimate must not discard the continuous opposite witness",2,states.last().cycles)
        val events=states.mapNotNull { it.event }.filter { it.outcome=="imageCycle" }
        assertEquals(2,events.size)
        assertTrue(events.all { it.witnessSlots==listOf(1) })
        assertEquals(listOf(0),states.last().revokedArmSlots)
        assertEquals(listOf(1),states.last().supportWitnessSlots)
    }
    @Test fun bothDepartingWristsCannotCompleteAnOldCycle() {
        val frames=cycle().map { if(it.timestamp.value>=1120) displacedWrists(it,setOf(0,1)) else it }
        assertEquals(0,run(frames).last().cycles)
    }
    @Test fun alternatingWristDeparturesCannotHandOffAnOldPeak() {
        val frames=cycle().map { p -> when {
            p.timestamp.value in 1040L..1200L -> displacedWrists(p,setOf(0))
            p.timestamp.value>=1240L -> displacedWrists(p,setOf(1))
            else -> p
        } }
        assertEquals("The first wrist returning cannot renew its revoked support",0,run(frames).last().cycles)
    }
    @Test fun lossOfTheRemainingWitnessCannotBridgeACompletion() {
        val frames=cycle().map { p ->
            val moved=if(p.timestamp.value>=1040) displacedWrists(p,setOf(0)) else p
            if(p.timestamp.value in 1120L..1480L) moved.copy(people=moved.people.map { person ->
                person.copy(landmarks=person.landmarks.filterNot { it.joint==PoseJoint.rightWrist })
            }) else moved
        }
        assertEquals(0,run(frames).last().cycles)
    }
    @Test fun aSingleEndpointWristOutlierCannotFinishWithoutAnAnchoredPeakWitness() {
        val frames=cycle().map { p ->
            val moved=if(p.timestamp.value in 1120L..1360L) displacedWrists(p,setOf(0)) else p
            if(p.timestamp.value==1520L) displacedWrists(moved,setOf(1)) else moved
        }
        val states=run(frames)
        assertEquals(0,states.single { it.seconds==1.52 }.cycles)
        assertEquals(1,states.last().cycles)
        assertEquals(listOf(1),states.mapNotNull { it.event }.single { it.outcome=="imageCycle" }.witnessSlots)
    }
    @Test fun aPersistentVerticalWristOffsetCannotInventTheMissingBilateralReturn() {
        val frames=cycle().map { if(it.timestamp.value>=1120) displacedWrists(it,setOf(0)) else it }
        assertEquals("A good support witness does not replace the opposite actual lower measurement",0,run(frames).last().cycles)
    }
    @Test fun horizontalSupportTranslationDoesNotCancelActualVerticalShoulderCycles() {
        val frames=cycle().map { if(it.timestamp.value>=1120) displacedWrists(it,setOf(0,1),horizontal=true) else it }
        assertEquals("Lateral hand translation along a horizontal support is not vertical support loss",1,run(frames).last().cycles)
    }
    private fun fastFirstCycle(insideCalibration: Boolean=false) =
        (0L..600L step 40).map { pose(it) } +
        (640L..(if(insideCalibration)680L else 1000L) step 40).map { pose(it,140.0,100.0) } +
        ((if(insideCalibration)720L else 1040L)..1400L step 40).map { pose(it) }
    @Test fun firstPullDuringSupportCalibrationKeepsItsActualLowerAndSourceOwnedStart() {
        for(inside in listOf(false,true)) {
            val frames=fastFirstCycle(inside);val selection=BodyAthleteSelection()
            val owner=OverheadPullUpSessionExperiment("fast")
            val states=frames.map { p -> selection.observe(p);owner.observe(selection,true) }
            assertEquals("insideCalibration=$inside",1,states.last().completed)
            val episode=checkNotNull(owner.episode());val event=episode.events.single()
            val observed=frames.map { it.timestamp.seconds }.toSet()
            assertTrue(episode.startedAt in observed)
            assertTrue(episode.startedAt<=event.departure!! && event.departure!!<=event.peak!!)
            assertTrue(event.peak!!<=event.lowerTurn!! && event.lowerTurn!!<=event.seconds)
            assertTrue(listOf(event.departure,event.peak,event.lowerTurn,event.seconds).all { it in observed })
            assertTrue(event.observedCompletion in observed)
            assertTrue(event.observedCompletion!!<=event.seconds)
            assertEquals(0,states.filter { it.motion.seconds!!<.85 }.maxOf { it.completed })
            assertTrue(states.all { it.completed==it.motion.cycles })
            assertEquals(1,states.sumOf { s -> s.motion.events.count { it.outcome=="imageCycle" } })
        }
    }
    @Test fun calibrationCannotBridgeCameraBodySourceEpochOrHandDepartureFaults() {
        for(fault in listOf("camera","missingWrist","backend","clock","epoch","hands")) {
            val selection=BodyAthleteSelection();val owner=OverheadPullUpSessionExperiment("fault")
            for(original in fastFirstCycle(true)) {
                val ms=original.timestamp.value
                val p=when {
                    fault=="missingWrist" && ms==720L -> original.copy(people=original.people.map { person ->
                        person.copy(landmarks=person.landmarks.filterNot { it.joint==PoseJoint.leftWrist }) })
                    fault=="backend" && ms>=720L -> original.copy(backend="different")
                    fault=="clock" && ms==720L -> original.copy(timestamp=PresentationTime(600,1000))
                    fault=="hands" && ms==720L -> displacedWrists(original,setOf(0,1))
                    else -> original
                }
                if(fault=="epoch" && ms==720L)selection.reset()
                selection.observe(p);owner.observe(selection,fault!="camera" || ms!=720L)
            }
            assertEquals(fault,0,owner.completed)
        }
    }
    @Test fun highFirstPullDuringCalibrationCanPassTheOriginalHandHeight() {
        val frames=(0L..600L step 40).map { pose(it) } + listOf(pose(640,185.0,90.0)) +
            (680L..800L step 40).map { pose(it,370.0,35.0) } +
            listOf(pose(840,185.0,90.0)) + (880L..1400L step 40).map { pose(it) }
        assertEquals(1,run(frames).last().cycles)
    }
    @Test fun disablingDepartureDuringCalibrationCannotPublishBufferedMovement() {
        val selection=BodyAthleteSelection();val motion=OverheadPullUpMotionExperiment()
        for(p in fastFirstCycle(true)) {
            selection.observe(p)
            val state=motion.observe(selection,"disabled",true,allowDeparture=false)
            assertEquals(0,state.cycles)
            assertTrue(state.events.none { it.outcome=="imageCycle" })
        }
    }
    @Test fun completedCalibrationCycleKeepsItsSourceOwnerThroughALaterPrivateSupportReset() {
        // All frames remain in the initial support qualification envelope.
        // A deeper actual lower refines support, then one whole observed cycle
        // completes before both hands leave that refined support. No camera,
        // actor, source-time or observation gap supplies synthetic evidence.
        val frames=(0L..600L step 40).map { pose(it) } + listOf(pose(601,wristDrop=-35.0)) +
            (602L..620L).map { pose(it,140.0,100.0) } +
            (621L..745L).map { pose(it,wristDrop=-35.0) } +
            (746L..1000L).map { pose(it,wristDrop=35.0) }
        val selection=BodyAthleteSelection();val owner=OverheadPullUpSessionExperiment("buffered")
        val states=frames.map { p -> selection.observe(p);owner.observe(selection,true) }
        val confirmed=states.single { it.motion.seconds==.85 }
        assertEquals(1,confirmed.motion.events.count { it.outcome=="imageCycle" })
        assertEquals(1,confirmed.completed)
        assertTrue(confirmed.started)
        assertTrue(states.all { it.completed==it.motion.cycles })
        val episode=checkNotNull(owner.episode());val event=episode.events.single()
        assertTrue(episode.startedAt<=event.departure!!)
        assertTrue(frames.any { it.timestamp.seconds==episode.startedAt })
        assertEquals(1,states.last().completed)
        val unavailable=OverheadPullUpSessionExperiment("unconfirmed");val fresh=BodyAthleteSelection()
        for(p in frames) {fresh.observe(p);unavailable.observe(fresh,p.timestamp.value!=850L)}
        assertEquals(0,unavailable.completed)
        assertFalse(unavailable.hasStarted)
    }
    @Test fun fullOriginalSourceFeasibilityWithExplicitAuthoredCamera() {
        val input=System.getenv("OVERHEAD_PULLUP_INPUT") ?: return
        val output=File(checkNotNull(System.getenv("OVERHEAD_PULLUP_OUTPUT")))
        check(!output.exists() || output.listFiles()?.isEmpty()==true) { "Preserve prior replay evidence" }
        output.mkdirs()
        val json=WorkoutRecordCodec.json
        val cases=json.parseToJsonElement(File(input).readText()).jsonObject.getValue("cases").jsonArray
        val summaries=mutableListOf<JsonObject>()
        for (value in cases) {
            val spec=value.jsonObject;val id=spec.getValue("id").jsonPrimitive.content
            require(id.matches(Regex("[a-zA-Z0-9-]+")))
            val source=File(spec.getValue("poses").jsonPrimitive.content)
            fun hash(): String {
                val digest=MessageDigest.getInstance("SHA-256")
                source.inputStream().buffered().use { stream ->
                    val buffer=ByteArray(65536)
                    while(true) { val count=stream.read(buffer);if(count<0)break;digest.update(buffer,0,count) }
                }
                return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            }
            assertEquals(spec.getValue("poseSHA256").jsonPrimitive.content,hash())
            val selection=BodyAthleteSelection();var ordinal=0
            fun newOwner()=OverheadPullUpSessionExperiment("$id-${ordinal++}")
            var owner=newOwner();val episodes=mutableListOf<OverheadPullUpSessionExperiment.Episode>()
            val rotation=spec["cameraMovesAt"]?.jsonPrimitive?.double
            var frames=0;var cycleEvents=0
            File(output,"$id.jsonl").bufferedWriter().use { writer ->
                source.forEachLine { line ->
                    val pose=json.decodeFromString<PoseResult>(line)
                    selection.observe(pose)
                    if (owner.isFinished) owner=newOwner()
                    val moved=rotation?.let { pose.timestamp.seconds>=it } == true
                    val state=owner.observe(selection,!moved,moved)
                    cycleEvents+=state.motion.events.count { it.outcome=="imageCycle" }
                    if (owner.isFinished) owner.episode()?.let(episodes::add)
                    writer.appendLine(buildJsonObject {
                        put("seconds",pose.timestamp.seconds);put("sourceTime",json.encodeToJsonElement(pose.timestamp))
                        put("motion",json.encodeToJsonElement(state.motion));put("started",state.started)
                        put("positiveRelease",state.positiveRelease);put("count",state.completed)
                        put("phase",if(state.started && !owner.isFinished)"running" else "idle")
                        if(state.started)put("activeSetId",owner.episode()!!.id)
                        state.endReason?.let { put("endReason",it) }
                    }.toString());frames++
                }
            }
            assertEquals(id,spec.getValue("frames").jsonPrimitive.int,frames)
            assertEquals(spec.getValue("poseSHA256").jsonPrimitive.content,hash())
            val summary=buildJsonObject {
                put("id",id);put("frames",frames);put("completedEvents",cycleEvents)
                put("episodes",json.encodeToJsonElement(episodes))
                if(owner.hasStarted && !owner.isFinished)put("activeAtEOF",json.encodeToJsonElement(owner.episode()))
                put("scope","Test-only overhead motion/episode feasibility. Authored ready camera, original cached Mac Vision poses, no image references or labels in input. Not actual native camera qualification, durable records, independent test gold or physical iPhone evidence.")
            }
            File(output,"$id-summary.json").writeText(summary.toString());summaries+=summary
        }
        File(output,"summary.json").writeText(JsonArray(summaries).toString())
    }
}
