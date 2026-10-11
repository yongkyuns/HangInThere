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
        val side:ArmMeasurement.Side,val expectedAcquired:Boolean,val expectedStarted:Boolean,val steps:List<Step>)
    @Serializable data class Snapshot(val exercise:ExerciseCounter.Exercise?=null,
        val apparatusCandidate:ExerciseCounter.Exercise?=null,val identityLocked:Boolean,
        val acquisitionState:String,val referenceEdge:BarSegment?=null,val role:String?=null,
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
                bar?.referenceEdge,bar?.role?.name,countdown.state.phase.name,countdown.state.seconds,began,
                phone.state.name,phone.latestDeltaDegrees,scene.state.name,scene.movementKind?.name,
                scene.latestShiftFraction,scene.latestScaleFraction,scene.latestConsensusPatches,scene.latestScaleMeasurementAvailable)
        }
        assertEquals(input.expectedAcquired,everAcquired,input.id+" acquisition label")
        assertEquals(input.expectedStarted,everStarted,input.id+" countdown label")
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
        assertTrue(cases.size>=54,"Setup parity cases reduced")
        val ids=cases.map { it.jsonObject.getValue("input").jsonObject.getValue("id").jsonPrimitive.content }
        assertTrue(ids.containsAll(listOf("dip-native-overlap-left","dip-native-overlap-right")),
            "The frozen native regressions must execute; regenerate a stale setup oracle")
        var frames=0
        cases.forEach {value ->
            val row=value.jsonObject;val input=json.decodeFromJsonElement<Input>(row.getValue("input"))
            val actual=run(input);frames+=actual.size
            equivalent(row.getValue("snapshots"),json.encodeToJsonElement(actual),input.id)
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
