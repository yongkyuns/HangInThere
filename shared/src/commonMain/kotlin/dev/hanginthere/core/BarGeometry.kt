package dev.hanginthere.core

import kotlinx.serialization.Serializable
import kotlin.math.*

/** Geometry is upright unmirrored, top-left image pixels. Only observed edges qualify. */
@Serializable data class BarSegment(val a: Point2D, val b: Point2D) {
    val length get() = hypot(b.x-a.x,b.y-a.y)
    val isValid get() = a.isFinite && b.isFinite && length.isFinite() && length >= 2
    val midpoint get() = Point2D((a.x+b.x)/2,(a.y+b.y)/2)
    val ordered get() = if(a.x<b.x || (a.x==b.x && a.y<=b.y)) this else BarSegment(b,a)
    fun distance(p:Point2D):Double {
        if(!isValid || !p.isFinite) return Double.POSITIVE_INFINITY
        val t=(((p.x-a.x)*(b.x-a.x)+(p.y-a.y)*(b.y-a.y))/(length*length)).coerceIn(0.0,1.0)
        return hypot(p.x-a.x-t*(b.x-a.x),p.y-a.y-t*(b.y-a.y))
    }
    fun perpendicularDistance(p:Point2D)=if(!isValid || !p.isFinite) Double.POSITIVE_INFINITY else abs((b.x-a.x)*(p.y-a.y)-(b.y-a.y)*(p.x-a.x))/length
}
@Serializable data class BarRegion(val minX:Double,val minY:Double,val maxX:Double,val maxY:Double) {
    constructor(a:Point2D,b:Point2D):this(min(a.x,b.x),min(a.y,b.y),max(a.x,b.x),max(a.y,b.y))
    val width get()=maxX-minX
    val height get()=maxY-minY
    fun contains(p:Point2D)=p.isFinite && p.x>=minX && p.x<=maxX && p.y>=minY && p.y<=maxY
    fun isValid(size:ImageSize)=size.isValid && listOf(minX,minY,maxX,maxY).all{it.isFinite()} && minX>=0 && minY>=0 && maxX<=size.width && maxY<=size.height && width>=16 && height>=16
    fun contourPoint(x:Float,y:Float):Point2D? {
        val tolerance=4*1.1920929e-7f
        if(!x.isFinite() || !y.isFinite() || x < -tolerance || x>1+tolerance || y < -tolerance || y>1+tolerance || !listOf(minX,minY,maxX,maxY).all{it.isFinite()} || width<=0 || height<=0) return null
        return Point2D(minX+x.coerceIn(0f,1f).toDouble()*width,minY+(1-y.coerceIn(0f,1f).toDouble())*height)
    }
}
@Serializable data class BarCandidate(val firstEdge:BarSegment,val secondEdge:BarSegment,val geometryScore:Double) {
    val centerline get()=BarSegment(Point2D((firstEdge.a.x+secondEdge.a.x)/2,(firstEdge.a.y+secondEdge.a.y)/2),Point2D((firstEdge.b.x+secondEdge.b.x)/2,(firstEdge.b.y+secondEdge.b.y)/2))
    val upperImageEdge get()=if(abs(centerline.b.x-centerline.a.x)<.2*centerline.length) null else if(firstEdge.midpoint.y<=secondEdge.midpoint.y) firstEdge else secondEdge
}
@Serializable data class BarLineCandidate(val edge:BarSegment,val geometryScore:Double)
@Serializable data class ConfirmedBar(val role:Role,val method:Method,val referenceEdge:BarSegment,val oppositeEdge:BarSegment?=null,val imageSize:ImageSize,val sourceTime:PresentationTime) {
    @Serializable enum class Role { pullUpGrip,leftDipRail,rightDipRail }
    @Serializable enum class Method { guidedContours,manualEdge,automaticContours }
    val isValid:Boolean get() {
        val bounds=BarRegion(Point2D(0.0,0.0),Point2D(imageSize.width,imageSize.height))
        if(!imageSize.isValid || !sourceTime.seconds.isFinite() || !referenceEdge.isValid || referenceEdge.length<24 || !bounds.contains(referenceEdge.a) || !bounds.contains(referenceEdge.b)) return false
        return oppositeEdge?.let {method==Method.guidedContours && it.isValid && bounds.contains(it.a) && bounds.contains(it.b) && BarFitter.pair(referenceEdge,it,24.0)!=null} ?: true
    }
}
class BarFitException(message:String):IllegalArgumentException(message)
object BarFitter {
    const val maximumPoints=200_000
    const val maximumSegments=512
    fun pair(pOriginal:BarSegment,qOriginal:BarSegment,minimumLength:Double):BarCandidate? {
        if(!pOriginal.isValid || !qOriginal.isValid || !minimumLength.isFinite() || minimumLength<2) return null
        val p=pOriginal.ordered; var q=qOriginal.ordered
        val ux=(p.b.x-p.a.x)/p.length; val uy=(p.b.y-p.a.y)/p.length
        val vx=(q.b.x-q.a.x)/q.length; val vy=(q.b.y-q.a.y)/q.length
        val dot=ux*vx+uy*vy
        if(abs(dot)<cos(5*PI/180)) return null
        // Align the undirected edge before projecting shared observed support.
        if(dot<0) q=BarSegment(q.b,q.a)
        fun projection(r:Point2D)=(r.x-p.a.x)*ux+(r.y-p.a.y)*uy
        val q0=projection(q.a);val q1=projection(q.b);val lo=max(0.0,q0);val hi=min(p.length,q1)
        if(hi-lo<minimumLength || q1-q0<=0) return null
        fun point(t:Double)=Point2D(p.a.x+t*ux,p.a.y+t*uy)
        fun onQ(t:Double):Point2D {val k=(t-q0)/(q1-q0);return Point2D(q.a.x+k*(q.b.x-q.a.x),q.a.y+k*(q.b.y-q.a.y))}
        val a=point(lo);val b=point(hi);val c=onQ(lo);val d=onQ(hi)
        val w0=(c.x-a.x)*(-uy)+(c.y-a.y)*ux;val w1=(d.x-b.x)*(-uy)+(d.y-b.y)*ux
        val narrow=min(abs(w0),abs(w1));val wide=max(abs(w0),abs(w1))
        if(w0*w1<=0 || narrow<2 || wide>40 || narrow<.4*wide || hi-lo<wide) return null
        return BarCandidate(BarSegment(a,b),BarSegment(c,d),(hi-lo)*abs(dot))
    }
    fun candidates(contours:List<List<Point2D>>,region:BarRegion,size:ImageSize):List<BarCandidate> {
        requireComplexity(contours,region,size)
        var budget=4_000_000
        val edges=mutableListOf<BarSegment>()
        val minimumLength=max(24.0,.30*max(region.width,region.height))
        contours.forEach { contour ->
            if(contour.size>2 && contour.all{region.contains(it)}) {
                val closed=if(contour.first()==contour.last()) contour else contour+contour.first()
                val points=simplify(closed,1.5){if(--budget<0) throw BarFitException("tooComplex")}
                points.zipWithNext().forEach { (a,b) ->
                    val line=BarSegment(a,b).ordered
                    if(line.isValid && line.length>=minimumLength && !cropBorder(line,region,1.5,false)) edges.add(line)
                    if(edges.size>maximumSegments) throw BarFitException("tooComplex")
                }
            }
        }
        val result=mutableListOf<BarCandidate>()
        for(i in edges.indices) for(j in i+1 until edges.size) pair(edges[i],edges[j],minimumLength)?.let{result.add(it)}
        result.sortWith(compareByDescending<BarCandidate>{it.geometryScore}.thenBy{it.centerline.midpoint.y}.thenBy{it.centerline.midpoint.x})
        val unique=mutableListOf<BarCandidate>()
        result.forEach { c -> if(unique.none { (it.firstEdge.distance(c.firstEdge.midpoint)<1.5 && it.secondEdge.distance(c.secondEdge.midpoint)<1.5) || (it.firstEdge.distance(c.secondEdge.midpoint)<1.5 && it.secondEdge.distance(c.firstEdge.midpoint)<1.5) }) unique.add(c) }
        return unique.take(12)
    }
    internal fun requireComplexity(contours:List<List<Point2D>>,region:BarRegion,size:ImageSize) {if(!region.isValid(size)) throw BarFitException("invalidRegion");if(contours.sumOf{it.size}>maximumPoints) throw BarFitException("tooComplex")}
    internal fun cropBorder(e:BarSegment,r:BarRegion,t:Double,inclusive:Boolean):Boolean {
        fun close(a:Double,b:Double)=if(inclusive) abs(a-b)<=t else abs(a-b)<t
        return (close(e.a.x,r.minX)&&close(e.b.x,r.minX)) || (close(e.a.x,r.maxX)&&close(e.b.x,r.maxX)) || (close(e.a.y,r.minY)&&close(e.b.y,r.minY)) || (close(e.a.y,r.maxY)&&close(e.b.y,r.maxY))
    }
    internal fun simplify(points:List<Point2D>,tolerance:Double,spend:()->Unit):List<Point2D> {
        if(points.size<=2) return points
        val keep=mutableSetOf(0,points.size-1);val stack=mutableListOf(0 to points.size-1)
        while(stack.isNotEmpty()) {
            val (a,b)=stack.removeAt(stack.lastIndex);if(b<=a+1) continue
            val segment=BarSegment(points[a],points[b]);var farthest=a;var distance=tolerance
            for(i in a+1 until b) {spend();val d=if(segment.isValid) segment.distance(points[i]) else hypot(points[i].x-points[a].x,points[i].y-points[a].y);if(d>distance){distance=d;farthest=i}}
            if(farthest!=a){keep.add(farthest);stack.add(a to farthest);stack.add(farthest to b)}
        }
        return keep.sorted().map{points[it]}
    }
}
/**
 * Guided edge fitting bridges only collinear observed fragments and never extrapolates.
 * Clutter retains the strongest 512 observed segments for grouping; the 200,000-point
 * and four-million simplification-operation guards still apply.
 */
object BarLineFitter {
    private val observedRank = compareByDescending<BarSegment> { it.length }
        .thenBy { it.ordered.a.x }.thenBy { it.ordered.a.y }
        .thenBy { it.ordered.b.x }.thenBy { it.ordered.b.y }

    private fun retainStrongest(segments: MutableList<BarSegment>) {
        segments.sortWith(observedRank)
        segments.subList(BarFitter.maximumSegments, segments.size).clear()
    }

    private data class Fit(val ux:Double,val uy:Double,val offset:Double,val t0:Double,val t1:Double) {
        val edge get()=BarSegment(Point2D(t0*ux-offset*uy,t0*uy+offset*ux),
                                 Point2D(t1*ux-offset*uy,t1*uy+offset*ux)).ordered
    }
    private data class Group(val points:MutableList<Point2D>,var fit:Fit)
    private fun fit(points:List<Point2D>):Fit? {
        if(points.size<2 || points.any{!it.isFinite}) return null
        val mx=points.sumOf{it.x}/points.size;val my=points.sumOf{it.y}/points.size
        var xx=0.0;var xy=0.0;var yy=0.0
        points.forEach {val dx=it.x-mx;val dy=it.y-my;xx+=dx*dx;xy+=dx*dy;yy+=dy*dy}
        if(xx+yy<=1e-12) return null
        val angle=.5*atan2(2*xy,xx-yy);var ux=cos(angle);var uy=sin(angle)
        if(ux<0 || (abs(ux)<1e-12 && uy<0)){ux=-ux;uy=-uy}
        val offset=points.sumOf{it.x*(-uy)+it.y*ux}/points.size;val ts=points.map{it.x*ux+it.y*uy}
        val t0=ts.min();val t1=ts.max();return if(t1>t0) Fit(ux,uy,offset,t0,t1) else null
    }
    fun candidates(contours:List<List<Point2D>>,region:BarRegion,size:ImageSize):List<BarLineCandidate> {
        BarFitter.requireComplexity(contours,region,size)
        var budget=4_000_000;val segments=mutableListOf<BarSegment>()
        var exceededCapacity = false
        contours.forEach { contour ->
            if (contour.size > 1 && contour.all { region.contains(it) }) {
                BarFitter.simplify(contour, 1.0) {
                    if (--budget < 0) throw BarFitException("tooComplex")
                }.zipWithNext().forEach { (a, b) ->
                    val edge = BarSegment(a, b)
                    if (edge.isValid) segments.add(edge)
                    // Keep observed long edges despite unrelated texture, while
                    // retaining the same 512-segment bound on grouping work.
                    if (segments.size >= 2 * BarFitter.maximumSegments) {
                        exceededCapacity = true
                        retainStrongest(segments)
                    }
                }
            }
        }
        if (segments.size > BarFitter.maximumSegments) {
            exceededCapacity = true
            retainStrongest(segments)
        }
        // Ordinary frames retain their original order; canonical endpoint ties
        // make overflow selection independent of contour enumeration/direction.
        val ranked = if (exceededCapacity) segments.map { it.ordered }.sortedWith(observedRank)
            else segments.sortedByDescending { it.length }
        val major=max(region.width,region.height);val gapLimit=min(45.0,.20*major);val groups=mutableListOf<Group>()
        ranked.forEach {e ->
            var ux=(e.b.x-e.a.x)/e.length;var uy=(e.b.y-e.a.y)/e.length
            if(ux<0 || (abs(ux)<1e-12 && uy<0)){ux=-ux;uy=-uy}
            val points=listOf(e.a,e.b)
            val group=groups.firstOrNull {g->
                // Lines are undirected; x ordering flips direction around vertical.
                val f=g.fit;val dot=abs(ux*f.ux+uy*f.uy).coerceIn(0.0,1.0)
                val offset=points.sumOf{it.x*(-f.uy)+it.y*f.ux}/2
                val ts=points.map{it.x*f.ux+it.y*f.uy};val gap=max(0.0,max(f.t0-ts.max(),ts.min()-f.t1))
                // Preserve tube silhouettes using finite shared support, width,
                // taper and crossing checks; noisy disjoint fragments still join.
                acos(dot)<=8*PI/180 && abs(offset-f.offset)<=5 && gap<=gapLimit &&
                    BarFitter.pair(f.edge,e,2.0)==null
            }
            if(group!=null){group.points.addAll(points);fit(group.points)?.let{group.fit=it}} else fit(points)?.let{groups.add(Group(points.toMutableList(),it))}
        }
        val results=groups.mapNotNull{g -> val edge=g.fit.edge;if(edge.isValid && edge.length>=.25*major && !BarFitter.cropBorder(edge,region,2.0,true)) BarLineCandidate(edge,edge.length) else null}.sortedWith(compareByDescending<BarLineCandidate>{it.geometryScore}.thenBy{it.edge.midpoint.y}.thenBy{it.edge.midpoint.x})
        val unique=mutableListOf<BarLineCandidate>();results.forEach{c -> if(unique.none{it.edge.distance(c.edge.midpoint)<2 && c.edge.distance(it.edge.midpoint)<2}) unique.add(c)}
        return unique.take(12)
    }
}
