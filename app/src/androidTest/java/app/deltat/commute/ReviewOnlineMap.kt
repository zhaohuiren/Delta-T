package app.deltat.commute

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.deltat.commute.map.*
import com.amap.api.maps.AMap
import com.amap.api.maps.TextureMapView
import com.amap.api.maps.model.*

/** Screenshot-only style exploration around the current production renderer.
 * Fixed recorded states, not a production collision engine implementation. */
@Composable internal fun ReviewOnlineMap(data:RealRedesignData,modifier:Modifier,phase:String) {
    val context=LocalContext.current
    val sample=remember{data.sample()}
    val track=remember{data.fixes(sample)}
    val passes=remember{data.passes(sample).map{it.getLong("elapsedRealtimeNanos")}}
    val cps=remember{passes.mapIndexed{i,t->val p=track.minBy{kotlin.math.abs(it.elapsedRealtimeNanos-t)};MapCheckpoint(listOf("A","S1","S2","B")[i],i,p.latitude,p.longitude,35.0,false)}}
    val stop=when(phase){"waiting"->passes[0];"complete"->Long.MAX_VALUE;else->passes[2]}
    val actual=track.takeWhile{it.elapsedRealtimeNanos<=stop}.ifEmpty{track.take(1)}
    val scene=MapScene(track,actual,cps,actual.last(),actualPassElapsedNanos=when(phase){"waiting"->emptyList();"complete"->passes;else->passes.take(2)},
        phase=when(phase){"waiting"->MapPhase.PREPARING;"complete"->MapPhase.COMPLETE;else->MapPhase.TIMING},nextCheckpointIndex=2)
    val klass=remember{Class.forName("app.deltat.commute.ui.AmapSceneController")}
    val controller=remember{klass.declaredConstructors.single().apply{isAccessible=true}.newInstance(context.applicationContext, {})}
    fun call(name:String,vararg args:Any?):Any?=klass.declaredMethods.first{it.name==name&&it.parameterCount==args.size}.apply{isAccessible=true}.invoke(controller,*args)
    fun field(name:String):Any?=klass.getDeclaredField(name).apply{isAccessible=true}.get(controller)
    DisposableEffect(controller){call("onResume");onDispose{call("release")}}
    AndroidView(modifier=modifier.clip(RoundedCornerShape(8.dp)),factory={ctx->
        val view=call("createView",ctx) as TextureMapView
        call("update",scene)
        val shade=field("backgroundShade") as Polygon
        shade.fillColor=android.graphics.Color.argb(64,0,0,0)
        val map=field("map") as AMap
        val density=ctx.resources.displayMetrics.density
        view.postDelayed({
            @Suppress("UNCHECKED_CAST")
            val markers=field("checkpointMarkers") as Map<Int,Marker>
            @Suppress("UNCHECKED_CAST")
            val leaders=field("labelLeaders") as Map<Int,Polyline>
            @Suppress("UNCHECKED_CAST")
            val anchors=field("anchorMarkers") as Map<Int,Marker>
            leaders.values.forEach{it.isVisible=false}
            val position=(field("positionMarker") as? Marker)?.let{map.projection.toScreenLocation(it.position)}
            val occupied=mutableListOf<Point>()
            markers.toSortedMap().forEach{(order,marker)->
                val location=marker.position
                val anchor=map.projection.toScreenLocation(location)
                marker.isVisible=false
                if(position!=null && kotlin.math.hypot((anchor.x-position.x).toDouble(),(anchor.y-position.y).toDouble())<20*density)anchors[order]?.isVisible=false
                val options=listOf(-42 to 0,42 to 0,0 to -48,0 to 48,-42 to -48,42 to 48).map{(dx,dy)->Point((anchor.x+dx*density).toInt().coerceIn((26*density).toInt(),view.width-(26*density).toInt()),(anchor.y+dy*density).toInt().coerceIn((28*density).toInt(),view.height-(58*density).toInt()))}
                fun clear(p:Point)=minOf(position?.let{kotlin.math.hypot((p.x-it.x).toDouble(),(p.y-it.y).toDouble())-50*density}?:1e9,occupied.minOfOrNull{kotlin.math.hypot((p.x-it.x).toDouble(),(p.y-it.y).toDouble())-48*density}?:1e9)
                val p=options.firstOrNull{clear(it)>=0}?:options.maxBy{clear(it)};occupied+=p
                val labelPosition=map.projection.fromScreenLocation(p)
                val bitmap=Bitmap.createBitmap((36*density).toInt(),(28*density).toInt(),Bitmap.Config.ARGB_8888)
                val canvas=Canvas(bitmap);val paint=Paint(Paint.ANTI_ALIAS_FLAG)
                paint.color=android.graphics.Color.rgb(35,47,59)
                canvas.drawRoundRect(0f,0f,bitmap.width.toFloat(),bitmap.height.toFloat(),8*density,8*density,paint)
                paint.color=android.graphics.Color.rgb(238,244,249);paint.textSize=12*density;paint.textAlign=Paint.Align.CENTER;paint.typeface=android.graphics.Typeface.DEFAULT_BOLD
                canvas.drawText(cps[order].label,bitmap.width/2f,bitmap.height/2f-(paint.ascent()+paint.descent())/2,paint)
                map.addPolyline(PolylineOptions().add(location,labelPosition).width(density).color(android.graphics.Color.rgb(178,194,207)).zIndex(7f))
                map.addMarker(MarkerOptions().position(labelPosition).anchor(.5f,.5f).icon(BitmapDescriptorFactory.fromBitmap(bitmap)).zIndex(9f))
            }
            (field("positionMarker") as? Marker)?.zIndex=12f
        },1600)
        view
    })
}
