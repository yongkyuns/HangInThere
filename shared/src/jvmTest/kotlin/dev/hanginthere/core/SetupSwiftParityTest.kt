package dev.hanginthere.core

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.math.abs
import kotlin.test.*

/** The fixture oracle compiles actual production Swift; every policy state is compared. */
class SetupSwiftParityTest {
    @Serializable data class Attitude(val x:Double,val y:Double,val z:Double,val w:Double) {
        val quaternion get()=PhoneOrientationStability.Quaternion.create(x,y,z,w)
    }
    @Serializable data class Step(val pose:PoseResult,val candidates:List<List<BarLineCandidate>>,
        val attitude:Attitude?=null,val translations:List<StaticSceneStability.PatchTranslation>,
        val globalScaleFraction:Double?=null,val eligible:Boolean,val paused:Boolean)
    @Serializable data class Input(val id:String,val exercise:ExerciseCounter.Exercise,
        val side:ArmMeasurement.Side,val expectedAcquired:Boolean,val expectedStarted:Boolean,val steps:List<Step>,
        val expectedIdentity:List<ExerciseCounter.Exercise?>?=null)
    @Serializable data class Snapshot(val exercise:ExerciseCounter.Exercise?=null,
        val apparatusCandidate:ExerciseCounter.Exercise?=null,val identityLocked:Boolean,
        val acquisitionState:String,val referenceStabilityDiagnostic:AutomaticReferenceStability.Diagnostic?=null,
        val referenceEdge:BarSegment?=null,val role:String?=null,
        val countdownState:String,val countdownSeconds:Int?=null,val began:Boolean,
        val phoneState:String,val phoneDelta:Double?=null,val sceneState:String,val movementKind:String?=null,
        val shiftFraction:Double?=null,val scaleFraction:Double?=null,val consensusPatches:Int,val scaleAvailable:Boolean)
    @Serializable data class GeometryInput(val id:String,val contours:List<List<Point2D>>,val region:BarRegion,val size:ImageSize)
    @Serializable data class GeometryOutput(val input:GeometryInput,val lines:List<BarLineCandidate>?=null,
        val pairs:List<BarCandidate>?=null,val lineError:String?=null,val pairError:String?=null)
    private val json=Json{ignoreUnknownKeys=true;explicitNulls=false;encodeDefaults=true}
    private fun run(input:Input):List<Snapshot> {
        val identity=AutomaticExerciseDetection();val apparatus=AutomaticApparatusReference()
        val countdown=HandsFreeSetStart();val phone=PhoneOrientationStability();val scene=StaticSceneStability()
        var bar:ConfirmedBar?=null;var everAcquired=false;var everStarted=false
        val snapshots=input.steps.map {step ->
            val pose=step.pose;val seconds=pose.timestamp.seconds
            val previous=identity.exercise;val previousApparatus=identity.apparatusCandidate
            identity.observe(pose)
            if(previousApparatus!=identity.apparatusCandidate || (previous!=null && identity.exercise==null)) {
                apparatus.reset();countdown.reset();phone.reset();scene.reset();bar=null
            }
            var acquired=false
            if(bar==null && identity.apparatusCandidate==input.exercise && step.attitude?.quaternion!=null && step.candidates.isNotEmpty()) {
                val candidate=apparatus.observe(pose,input.exercise,input.side,step.candidates)
                if(candidate!=null && phone.calibrate(step.attitude.quaternion,seconds) && scene.calibrate(pose.imageSize)) {
                    bar=candidate;acquired=true;everAcquired=true
                }
            }
            if(bar!=null) {
                phone.observe(step.attitude?.quaternion,seconds)
                if(!acquired) scene.observe(step.translations,step.globalScaleFraction,seconds)
            }
            val eligible=step.eligible && identity.exercise==input.exercise && bar!=null && phone.state.allowsLiveSet && scene.state.allowsLiveSet
            val began=if(input.exercise==ExerciseCounter.Exercise.dip) countdown.observeDip(pose,input.side,bar?.referenceEdge,eligible,step.paused) else countdown.observe(seconds,eligible && bar?.let{HandsFreeSetStart.isStartingPosition(pose,it.referenceEdge)}==true,step.paused)
            if(began){assertTrue(identity.lock());everStarted=true}
            Snapshot(identity.exercise,identity.apparatusCandidate,identity.isLocked,apparatus.state.name,
                apparatus.referenceStabilityDiagnostic,
                bar?.referenceEdge,bar?.role?.name,countdown.state.phase.name,countdown.state.seconds,began,
                phone.state.name,phone.latestDeltaDegrees,scene.state.name,scene.movementKind?.name,
                scene.latestShiftFraction,scene.latestScaleFraction,scene.latestConsensusPatches,scene.latestScaleMeasurementAvailable)
        }
        assertEquals(input.expectedAcquired,everAcquired,input.id+" acquisition label")
        assertEquals(input.expectedStarted,everStarted,input.id+" countdown label")
        input.expectedIdentity?.let { assertEquals(it,snapshots.map { snapshot -> snapshot.exercise },input.id+" identity label") }
        return snapshots
    }
    private fun equivalent(expected:JsonElement,actual:JsonElement,path:String) {
        when {
            expected is JsonObject && actual is JsonObject -> {
                val e=expected.filterValues{it!=JsonNull};val a=actual.filterValues{it!=JsonNull}
                assertEquals(e.keys,a.keys,"$path keys")
                e.forEach{(key,value)->equivalent(value,a.getValue(key),"$path.$key")}
            }
            expected is JsonArray && actual is JsonArray -> {
                assertEquals(expected.size,actual.size,"$path length")
                expected.indices.forEach{equivalent(expected[it],actual[it],"$path[$it]")}
            }
            expected is JsonPrimitive && actual is JsonPrimitive && !expected.isString && !actual.isString && expected.doubleOrNull!=null && actual.doubleOrNull!=null ->
                assertTrue(abs(expected.double-actual.double)<=1e-9,"$path: $expected != $actual")
            else -> assertEquals(expected,actual,path)
        }
    }
    @Test fun identityApparatusCountdownAndCameraPoliciesMatchSwiftEveryFrame() {
        val path=System.getenv("SETUP_ORACLE") ?: "../build/setup-oracle/oracle.json"
        val oracle=json.parseToJsonElement(File(path).readText()).jsonObject
        val cases=oracle.getValue("cases").jsonArray
        assertTrue(cases.size>=72,"Setup parity cases reduced")
        val ids=cases.map { it.jsonObject.getValue("input").jsonObject.getValue("id").jsonPrimitive.content }
        for(side in ArmMeasurement.Side.entries) for(kind in listOf("observed", "flexed", "flexed-then-top",
            "alternating-lengths", "lost-pair", "ambiguous-pair", "no-edges", "unpaired", "ambiguous", "above",
            "phone", "scene", "wrong-side", "overhead-partial"))
            assertTrue("dip-single-contact-$kind-$side" in ids, "Missing single-contact setup parity regression")
        for (side in ArmMeasurement.Side.entries) for (mode in listOf("rails", "shared-bar"))
            for (kind in listOf("flexed-acquisition", "flexed-then-top-start", "qualified-identity-through-flexion"))
                assertTrue("dip-$kind-$mode-$side" in ids, "Missing independent dip acquisition/start parity case")
        for(side in ArmMeasurement.Side.entries) for(mode in listOf("rails", "shared-bar"))
            for(stage in listOf("qualified", "acquiring"))
                assertTrue("dip-deep-contact-$mode-$stage-$side" in ids, "Missing live Auto deep-contact transition parity")
        for(side in ArmMeasurement.Side.entries) {
            for(kind in listOf("observed", "wrong-rail"))
                assertTrue("dip-unequal-hand-height-$kind-$side" in ids, "Missing independently observed rail-height regression")
            for(offset in listOf(0,10,39)) assertTrue("dip-handle-at-grip-$offset-$side" in ids,
                "Missing observed handle at grip regression")
            for(kind in listOf("above-grip-rejected","witness-above-grip-rejected"))
                assertTrue("dip-$kind-$side" in ids,"Missing above-grip evidence regression")
        }
        assertTrue(ids.containsAll(listOf("dip-native-overlap-left","dip-native-overlap-right",
            "pullUp-exact-source-dwell","pullUp-one-microsecond-short-source-dwell",
            "pullUp-qualified-identity-through-flexion","pullUp-flexion-then-fresh-hang-start",
            "pullUp-flexion-identity-without-apparatus",
            "dip-wrist-aligned-before-longer-clutter-left","dip-wrist-aligned-before-longer-clutter-right") +
            listOf("pullUp","dip").flatMap { exercise ->
                listOf("exact-dwell","short-dwell","exact-gap","gap-over-limit").map { "$exercise-identity-clock-$it" }
            } +
            listOf("observed","single-edge","short-pair","ambiguous","above-grip","forearm","missing-pair").flatMap { kind ->
                listOf("left","right").map { side -> "dip-shared-bar-$kind-$side" }
            } + listOf("dip-parallel-before-shared-bar-left","dip-parallel-before-shared-bar-right")),
            "Required setup regressions must execute; regenerate a stale setup oracle")
        var frames=0
        cases.forEach {value ->
            val row=value.jsonObject;val input=json.decodeFromJsonElement<Input>(row.getValue("input"))
            val actual=run(input);frames+=actual.size
            equivalent(row.getValue("snapshots"),json.encodeToJsonElement(actual),input.id)
            if(input.id.startsWith("dip-parallel-before-shared-bar-")) {
                val x=if(input.side==ArmMeasurement.Side.left) 300.0 else 700.0
                assertTrue(actual.filter { it.referenceEdge!=null }.all {
                    it.referenceEdge==BarSegment(Point2D(x,545.0),Point2D(x,660.0))
                },input.id+" parallel reference priority")
            }
            if(input.id.startsWith("dip-native-overlap-")) {
                // Same captured native samples also pass through the shipping
                // runtime, including issue priority and automatic start gating.
                val runtime=WorkoutRuntime(side=input.side)
                input.steps.forEach {step ->
                    val seconds=step.pose.timestamp.seconds
                    val snapshot=runtime.observe(WorkoutRuntime.FrameInput(step.pose,step.candidates,
                        step.attitude?.quaternion,seconds,step.translations,step.globalScaleFraction,seconds))
                    assertNull(snapshot.referenceEdge,input.id)
                    assertNull(snapshot.countdownSeconds,input.id)
                    assertEquals(0,snapshot.observedMovements,input.id)
                    assertEquals(LiveSetSession.Phase.idle,snapshot.phase,input.id)
                    if(snapshot.exercise!=null) assertEquals("dipRailsUnclear",snapshot.setupIssue,input.id)
                }
                assertEquals(ExerciseCounter.Exercise.dip,runtime.resolvedExercise,input.id)
            }
        }
        assertTrue(frames>=2000)
        val geometries=oracle.getValue("geometries").jsonArray
        assertTrue(geometries.size>=17,"Setup geometry parity cases reduced")
        val geometryIds=geometries.map { it.jsonObject.getValue("input").jsonObject.getValue("id").jsonPrimitive.content }
        assertTrue(geometryIds.containsAll(listOf("near-vertical-tube-directions","near-vertical-fragment-noise",
            "thin-observed-tube","tapered-shared-support","noisy-disjoint-fragments")),
            "The observed geometry regressions must execute; regenerate a stale setup oracle")
        geometries.forEach {value ->
            val row=value.jsonObject;val input=json.decodeFromJsonElement<GeometryInput>(row.getValue("input"))
            var lines:List<BarLineCandidate>?=null;var pairs:List<BarCandidate>?=null
            var lineError:String?=null;var pairError:String?=null
            try {lines=BarLineFitter.candidates(input.contours,input.region,input.size)} catch(e:BarFitException){lineError=e.message}
            try {pairs=BarFitter.candidates(input.contours,input.region,input.size)} catch(e:BarFitException){pairError=e.message}
            equivalent(row,json.encodeToJsonElement(GeometryOutput(input,lines,pairs,lineError,pairError)),input.id)
        }
        val report=buildJsonObject{put("scope","every-frame setup policies compared with production Swift; analytic and frozen public native observations; policy parity only, no new inference");put("cases",cases.size);put("frames",frames);put("geometryCases",geometries.size);put("matched",true)}
        val output=File(System.getenv("SETUP_PARITY_REPORT") ?: "../build/setup-oracle/kotlin-parity.json")
        output.parentFile.mkdirs();output.writeText(json.encodeToString(report))
    }
}
