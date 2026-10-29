package dev.hanginthere.core

import kotlin.math.abs

/** Source clocks for hand inference and body observations remain separate. Repeated
 * cached hand results cannot earn confirmation or observed hand time. */
class WorkoutGripAnalysis(private val setId: String) {
    private class HandState {
        var stable=HandGrip.unknown;private set
        private var candidate=HandGrip.unknown
        private var last:Double?=null
        private val support=mutableListOf<Double>()
        fun observe(value:HandGrip,time:Double) {
            if(last?.let { time-it>maximumObservationGapSeconds }==true) reset()
            if(support.lastOrNull()?.let { time-it>maximumUnsupportedSeconds }==true)reset()
            last=time
            while(support.firstOrNull()?.let { time-it>supportWindowSeconds }==true)support.removeAt(0)
            stable=HandGrip.unknown
            if(value==HandGrip.unknown)return
            if(candidate!=value) {candidate=value;support.clear()}
            support+=time
            if(support.size>32)support.removeAt(0)
            if(support.size>=3 && time-support.first()>=confirmationSeconds)stable=value
        }
        fun reset(){stable=HandGrip.unknown;candidate=HandGrip.unknown;support.clear();last=null}
    }
    private val left=HandState();private val right=HandState()
    private var lastBody:Double?=null
    private var bodyUnavailable=false
    private var lastHand:Double?=null
    private var lastHandValid=false
    private var modelId:String?=null
    private var inferenceBackend:String?=null
    private var notBefore:Double?=null
    private var strictlyAfterCutoff=false
    private var lastSize:ImageSize?=null
    private var lastBar:BarSegment?=null
    private var viewAt:Double?=null
    private var nearFrontal=false
    private var widthCutoff:Double?=null
    private val widthMeasurement=WorkoutGripWidth()
    private var previousWidthValid=false
    private var orientation=emptyList<GripOrientationSpan>()
    private var widths=emptyList<GripWidthSpan>()
    private var orientationOrdinal=0
    private var widthOrdinal=0
    private var truncated=false
    var current=GripEstimate(reason="waitingForGripEvidence");private set
    val hasObservation get()=lastHand!=null
    internal val confirmedOrientationSeconds get() = lastHand?.takeIf {
        lastHandValid && current.orientation in WorkoutSetGrip.confirmedOrientations
    }
    val record get()=WorkoutGripRecord(orientation,widths,current,truncated)
    val delta get()=record.copy(orientationSpans=orientation.takeLast(2),widthSpans=widths.takeLast(2))
    fun observe(pose:PoseResult,bar:BarSegment?,phase:ExerciseCounter.Phase,usable:Boolean,sourceSeconds:Double=pose.timestamp.seconds) {
        val time=sourceSeconds
        if(lastBody==null && time.isFinite())notBefore=time
        if(!time.isFinite() || lastBody?.let { time<=it }==true) {interrupt();return}
        if(lastBody?.let { time-it>ExerciseCounter.maximumGapSeconds }==true || lastSize?.let { it!=pose.imageSize }==true ||
            lastBar?.let { it!=bar }==true) interrupt(time)
        val widthFrame=pose.gripObservation?.takeIf { frame ->
            frame.imageSize==pose.imageSize && frame.seconds.isFinite() && frame.seconds<=time &&
                time-frame.seconds<=WorkoutGripGeometry.maximumAgeSeconds && frame.seconds>(widthCutoff ?: Double.NEGATIVE_INFINITY) &&
                (modelId==null || modelId==frame.modelId) && (inferenceBackend==null || inferenceBackend==frame.backend) &&
                WorkoutGripGeometry.bodyBasis(frame)?.let { WorkoutGripGeometry.supportsView(it) }==true &&
                listOf(PoseJoint.leftShoulder,PoseJoint.rightShoulder,PoseJoint.leftWrist,PoseJoint.rightWrist).all { joint ->
                    val current=pose.people.singleOrNull()?.landmark(joint,.5)?.position
                    val original=frame.trackedBody.singleOrNull { it.joint.name==joint.name }?.position
                    current!=null && original!=null && kotlin.math.hypot(current.x-original.x,current.y-original.y)<=minOf(pose.imageSize.width,pose.imageSize.height)*.12
                }
        }
        val widthBodyUsable=WorkoutGripWidth.hasCurrentSupport(pose,bar) && (usable || widthFrame!=null)
        if(!usable) {
            // Missing elbow/arm geometry invalidates counting and hand evidence.
            // Current supported wrists/shoulders can still measure spacing.
            // A new, athlete-matched body result may qualify width even when
            // its hand timestamp cannot qualify counting/hand evidence.
            if(widthBodyUsable && widthFrame!=null) {viewAt=widthFrame.seconds;nearFrontal=true}
            val retainWidth=widthBodyUsable && widthFrame!=null
            if(!bodyUnavailable)interruptObservation(time,retainWidth) else advanceCutoff(time)
        }
        bodyUnavailable=!usable
        if(pose.gripObservation==null && lastHand==null) {
            lastSize=pose.imageSize;lastBar=bar;lastBody=time
            val unavailable=if(usable)"handObservationUnavailable" else "bodyTrackingUnavailable"
            if(current.reason!=unavailable)current=GripEstimate(reason=unavailable)
            return
        }
        val dt=lastBody?.let { time-it }.takeIf { it!=null && it>0 && it<=ExerciseCounter.maximumGapSeconds }
        lastSize=pose.imageSize;lastBar=bar
        var reason:String?=null
        val frame=pose.gripObservation
        if(frame!=null && (modelId!=null && modelId!=frame.modelId || inferenceBackend!=null && inferenceBackend!=frame.backend)) {
            interrupt(time)
            modelId=frame.modelId;inferenceBackend=frame.backend
        }
        if(frame!=null && frame.imageSize==pose.imageSize && frame.seconds<=time && (frame.seconds>(notBefore ?: time) || !strictlyAfterCutoff && frame.seconds==(notBefore ?: time)) && time-frame.seconds<=WorkoutGripGeometry.maximumAgeSeconds &&
            (lastHand==null || frame.seconds>lastHand!!)) {
            modelId=frame.modelId;inferenceBackend=frame.backend
            val oldHand=lastHand
            val raw=WorkoutGripGeometry.orientation(frame)
            val basis=WorkoutGripGeometry.bodyBasis(frame)
            nearFrontal=basis?.let { WorkoutGripGeometry.supportsView(it) }==true;viewAt=frame.seconds
            // Require contact evidence from the original inference frame, not a
            // newer pose that happened to arrive when inference completed.
            val contact=bar?.isValid==true && listOf(GripBodyJoint.leftWrist,GripBodyJoint.rightWrist).all { joint ->
                frame.trackedBody.singleOrNull { it.joint==joint }?.let { bar.distance(it.position)<=minOf(pose.imageSize.width,pose.imageSize.height)*.085 }==true
            }
            val valid=usable && contact && basis!=null && nearFrontal
            // Side-view loss invalidates confirmation immediately. Returning to
            // a supported view must earn new hand evidence, not reuse support
            // accumulated before the athlete/camera rotated.
            if(!nearFrontal) {left.reset();right.reset()}
            left.observe(if(valid)raw.left else HandGrip.unknown,frame.seconds)
            right.observe(if(valid)raw.right else HandGrip.unknown,frame.seconds)
            reason=if(!valid)raw.reason ?: "gripContactUnavailable" else raw.reason
            val handDt=oldHand?.let { frame.seconds-it }.takeIf { it!=null && it>0 && it<=maximumObservationGapSeconds && lastHandValid && valid }
            appendOrientation(frame.seconds,left.stable,right.stable,handDt ?: 0.0,reason,frame.modelId)
            lastHand=frame.seconds;lastHandValid=valid
        }
        val handFresh=lastHand?.let { time-it<=WorkoutGripGeometry.maximumAgeSeconds }==true
        val viewFresh=viewAt?.let { time-it<=WorkoutGripGeometry.maximumAgeSeconds }==true
        if(!usable || !handFresh) {
            left.reset();right.reset();lastHandValid=false
            reason=if(!usable)"bodyTrackingUnavailable" else "handObservationUnavailable"
        }
        val measuredWidth=widthMeasurement.observe(pose,bar,phase,widthBodyUsable,viewFresh && nearFrontal,time,
            allowCalibrationPause=usable && viewFresh && nearFrontal)
        val stableWidth=measuredWidth.width
        val ratio=measuredWidth.projectedSpanRatio
        val widthReason=measuredWidth.reason
        val widthValid=ratio!=null && stableWidth!=GripWidth.unknown
        appendWidth(time,stableWidth,ratio,if(widthValid && previousWidthValid)dt ?: 0.0 else 0.0,widthReason)
        previousWidthValid=widthValid
        val overall=GripEstimate.combine(left.stable,right.stable)
        val viewReason=if(usable && viewFresh && !nearFrontal && frame?.let { WorkoutGripGeometry.bodyBasis(it)!=null }==true)"gripViewUnsupported" else null
        current=GripEstimate(left.stable,right.stable,stableWidth,ratio,overall,
            viewReason ?: reason ?: if(overall==GripOrientation.unknown)"handOrientationAmbiguous" else widthReason,modelId,
            inferenceBackend=inferenceBackend,widthPolicyVersion=WorkoutGripWidth.policyVersion,widthReason=widthReason)
        lastBody=time
    }
    fun interrupt(cutoff:Double?=lastBody) {
        interruptObservation(cutoff,false)
    }
    private fun interruptObservation(cutoff:Double?,retainWidth:Boolean) {
        advanceCutoff(cutoff)
        left.reset();right.reset();lastHandValid=false
        if(!retainWidth) {
            viewAt=null;nearFrontal=false;widthMeasurement.interrupt()
            cutoff?.takeIf { it.isFinite() }?.let { widthCutoff=maxOf(widthCutoff ?: it,it) }
        }
        previousWidthValid=false
        current=GripEstimate(reason="observationInterrupted",modelId=modelId)
        // Break intervals explicitly. High-water timestamps are retained.
        lastHand?.let { appendOrientation(it,HandGrip.unknown,HandGrip.unknown,0.0,"observationInterrupted",modelId) }
        if(hasObservation)lastBody?.let { appendWidth(it,GripWidth.unknown,null,0.0,"observationInterrupted") }
    }
    private fun advanceCutoff(cutoff:Double?) {
        // Keep rejecting every pending pre-loss result without adding a new
        // interruption boundary on every frame of the same tracking outage.
        cutoff?.takeIf { it.isFinite() }?.let { notBefore=maxOf(notBefore ?: it,it);strictlyAfterCutoff=true }
    }
    private fun appendOrientation(time:Double,l:HandGrip,r:HandGrip,seconds:Double,reason:String?,model:String?) {
        val previous=orientation.lastOrNull()
        val same=previous!=null && previous.left==l && previous.right==r && previous.reason==reason && previous.modelId==model && previous.inferenceBackend==inferenceBackend && time-previous.endedAt<=maximumObservationGapSeconds
        val credit=if(l!=HandGrip.unknown && r!=HandGrip.unknown && same)seconds else 0.0
        orientation=if(same)orientation.dropLast(1)+previous!!.copy(endedAt=time,observedSeconds=previous.observedSeconds+credit)
            else orientation+GripOrientationSpan("$setId:grip:${++orientationOrdinal}",time,time,l,r,0.0,reason,model,inferenceBackend)
        if(orientation.size>maximumSpans){orientation=orientation.takeLast(maximumSpans);truncated=true}
    }
    private fun appendWidth(time:Double,width:GripWidth,ratio:Double?,seconds:Double,reason:String?) {
        val previous=widths.lastOrNull()
        val same=previous!=null && previous.width==width && previous.reason==reason && time-previous.endedAt<=ExerciseCounter.maximumGapSeconds
        widths=if(same)widths.dropLast(1)+previous!!.copy(endedAt=time,projectedSpanRatio=ratio,observedSeconds=previous.observedSeconds+seconds)
            else widths+GripWidthSpan("$setId:width:${++widthOrdinal}",time,time,width,ratio,0.0,reason)
        if(widths.size>maximumSpans){widths=widths.takeLast(maximumSpans);truncated=true}
    }
    fun summarize(start:Double,end:Double):GripEstimate=summary(record,start,end)
    companion object {
        const val maximumSpans=128
        const val confirmationSeconds=.5
        const val maximumObservationGapSeconds=.4
        const val maximumUnsupportedSeconds=.6
        const val supportWindowSeconds=1.0
        fun summary(record:WorkoutGripRecord?,start:Double,end:Double):GripEstimate {
            if(record==null || !start.isFinite() || !end.isFinite() || end<=start)return GripEstimate(reason="variationUnavailable",policyVersion=record?.policyVersion ?: WorkoutGripGeometry.policyVersion)
            val duration=end-start
            fun overlap(a:Double,b:Double)=maxOf(0.0,minOf(end,b)-maxOf(start,a))
            fun credit(a:Double,b:Double,observed:Double)=if(b>a)overlap(a,b)*(observed/(b-a)).coerceIn(0.0,1.0) else 0.0
            val hands=record.orientationSpans.filter { it.left!=HandGrip.unknown && it.right!=HandGrip.unknown && credit(it.startedAt,it.endedAt,it.observedSeconds)>0 }
            val widths=record.widthSpans.filter { it.width!=GripWidth.unknown && credit(it.startedAt,it.endedAt,it.observedSeconds)>0 }
            val hc=(hands.sumOf { credit(it.startedAt,it.endedAt,it.observedSeconds) }/duration).coerceIn(0.0,1.0)
            val wc=(widths.sumOf { credit(it.startedAt,it.endedAt,it.observedSeconds) }/duration).coerceIn(0.0,1.0)
            val types=hands.map { GripEstimate.combine(it.left,it.right) }.toSet()
            val bands=widths.map { it.width }.toSet()
            val reliable=hc>=.8 && hands.map { it.modelId to it.inferenceBackend }.toSet().size==1
            val l=if(reliable)hands.map { it.left }.distinct().singleOrNull() ?: HandGrip.unknown else HandGrip.unknown
            val r=if(reliable)hands.map { it.right }.distinct().singleOrNull() ?: HandGrip.unknown else HandGrip.unknown
            val orientation=if(!reliable)GripOrientation.unknown else if(types.size>1 || l==HandGrip.unknown || r==HandGrip.unknown)GripOrientation.varied else types.single()
            val width=if(wc<.8)GripWidth.unknown else bands.singleOrNull() ?: GripWidth.varied
            return GripEstimate(l,r,width,widths.mapNotNull { it.projectedSpanRatio }.takeIf { it.isNotEmpty() }?.average(),orientation,
                if(!reliable || wc<.8)"variationCoverageLow" else null,hands.mapNotNull { it.modelId }.distinct().singleOrNull(),hc,wc,
                policyVersion=record.policyVersion,inferenceBackend=hands.mapNotNull { it.inferenceBackend }.distinct().singleOrNull(),
                widthPolicyVersion=record.latest.widthPolicyVersion,widthReason=if(wc<.8)"variationCoverageLow" else null)
        }
    }
}
