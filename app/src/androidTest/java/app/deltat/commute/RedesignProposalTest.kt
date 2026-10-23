@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.deltat.commute

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.*
import java.io.File
import java.time.*
import java.time.format.DateTimeFormatter
import kotlin.math.*

/** REVIEW PROTOTYPE ONLY. Native Material 3 components, isolated in androidTest.
 * No production navigation, storage, GNSS or algorithms are changed. */
class RedesignProposalTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun renderNativeReview() {
        val context = compose.activity
        val data = RealRedesignData(File(context.getExternalFilesDir(null), "redesign-input.json"))
        val model = ReviewData(data)
        val page = mutableStateOf("home")
        val out = File(context.getExternalFilesDir(null), "redesign-proposal-r2").apply { mkdirs() }
        compose.activityRule.scenario.onActivity { it.setContent { ReviewTheme { key(page.value) { ReviewPage(page.value, model) } } } }
        val pages = listOf("home", "history", "detail", "ranking", "stats", "stats-sorted", "stats-detail", "stats-week", "stats-home", "date-range",
            "settings", "settings-more", "routes", "new-route", "record-idle", "recording", "record-finished", "editor", "timing", "timing-online", "waiting", "waiting-online", "complete", "complete-online")
        pages.forEach { id ->
            compose.runOnIdle { page.value = id }; compose.waitForIdle(); Thread.sleep(if(id.endsWith("online")) 4500 else 400)
            val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            File(out, "$id.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            File(out, "$id.txt").writeText(compose.onAllNodes(isRoot(), useUnmergedTree=true).printToString())
            File(out, "captures.jsonl").appendText(JSONObject().put("id",id).put("kind","native design proposal, NOT production implementation")
                .put("source","real diagnostic f1dfa83f, original dates and values; empty/permission/held states are explicit UI scenarios")
                .put("baseline","DT-R2-visual").put("time",OffsetDateTime.now().toString()).toString()+"\n")
        }
        compose.runOnIdle { page.value = "stats" }; compose.waitForIdle()
        compose.onNodeWithText("时间先后").performClick()
        compose.onNodeWithText("用时从快到慢").assertExists().performClick()
        compose.onNodeWithText("用时从快到慢").assertExists()
        compose.onAllNodesWithContentDescription("查看行程",substring=true).onFirst().performClick()
        compose.onNodeWithText("查看完整行程").assertExists()
        File(out,"interaction-check.txt").writeText("Native proposal sort menu and bar-to-bottom-sheet interaction checked. This is design interaction validation, not production feature acceptance.")
    }
}

private val RBg=Color(0xFF12161B)
private val RSurface=Color(0xFF1D232B)
private val RHigh=Color(0xFF2A323C)
private val RText=Color(0xFFF1F4F7)
private val RMuted=Color(0xFFAEB9C5)
private val RAccent=Color(0xFFFF594F)
private val RButton=Color(0xFFCE3028)
private val RLine=Color(0xFF46515E)
private val RChart=Color(0xFF9FB9CE)
@Composable private fun ReviewTheme(content:@Composable ()->Unit) {
    MaterialTheme(colorScheme=darkColorScheme(primary=RAccent,onPrimary=Color.White,primaryContainer=Color(0xFF35424E),onPrimaryContainer=RText,
        secondary=RChart,secondaryContainer=Color(0xFF344553),onSecondaryContainer=Color(0xFFDCEAF5),background=RBg,onBackground=RText,
        surface=RBg,onSurface=RText,surfaceVariant=RSurface,onSurfaceVariant=RMuted,outline=RLine,outlineVariant=RLine,
        surfaceContainer=RSurface,surfaceContainerLow=RSurface,surfaceContainerHigh=RHigh,surfaceContainerHighest=RHigh),shapes=Shapes(extraSmall=RoundedCornerShape(4.dp),small=RoundedCornerShape(8.dp),medium=RoundedCornerShape(8.dp),large=RoundedCornerShape(10.dp),extraLarge=RoundedCornerShape(12.dp)),content=content)
}
private data class ReviewTrip(val session:JSONObject,val evaluation:JSONObject) {
    val duration get()=evaluation.getLong("durationNanos")
    val timestamp get()=session.getLong("startedAt")
    val id get()=session.getString("id")
}
private class ReviewData(val source:RealRedesignData) {
    val sample=source.sample()
    val track=source.fixes(sample)
    val passTimes=source.passes(sample).map { it.getLong("elapsedRealtimeNanos") }
    val checkpoints=passTimes.map { t->track.minBy { abs(it.elapsedRealtimeNanos-t) } }
    val liveIndex=track.indexOfLast { it.elapsedRealtimeNanos<=passTimes[2] }.coerceAtLeast(0)
    val liveDuration=track[liveIndex].elapsedRealtimeNanos-passTimes[0]
    val allTrips=source.sessions.mapNotNull { s->s.getJSONArray("evaluations").objects().firstOrNull {
        it.getString("checkpointVersionId")==s.getString("checkpointVersionId") && it.getString("status")=="VALID" && !it.isNull("durationNanos") && s.getString("quality") in listOf("A","B")
    }?.let { ReviewTrip(s,it) } }
    val month=allTrips.filter { it.session.getString("route")=="上班" && dateOf(it.timestamp).monthValue==9 }.sortedBy { it.timestamp }
    val median=month.map { it.duration }.sorted().let { a-> if(a.size%2==1)a[a.size/2] else a[a.size/2-1]+(a[a.size/2]-a[a.size/2-1])/2 }
    val selected=month.last()
    val selectedEvaluation=sample.getJSONArray("evaluations").objects().first { it.getString("checkpointVersionId")==sample.getString("checkpointVersionId") }
}
private fun benchmarkColor(value:Long?,monthly:Boolean):Color = when {
    value==null||value==0L -> RMuted
    value>0 -> Color(0xFFE8C765)
    monthly -> Color(0xFFC29BFF)
    else -> Color(0xFF71D7AB)
}
private fun dateOf(ms:Long)=Instant.ofEpochMilli(ms).atZone(ZoneId.of("Asia/Shanghai"))
private fun time(n:Long?):String { if(n==null)return "—"; val c=n/10_000_000; return "%02d:%02d.%02d".format(c/6000,c/100%60,c%100) }
private fun delta(n:Long)=if(n==0L)"持平" else (if(n<0)"−" else "+")+time(abs(n))
private fun dateLabel(t:Long)=dateOf(t).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
@Composable private fun Small(text:String)=Text(text,style=MaterialTheme.typography.bodySmall,color=RMuted)
@Composable private fun Section(text:String)=Text(text,style=MaterialTheme.typography.titleSmall,modifier=Modifier.padding(top=8.dp,bottom=4.dp))
@Composable private fun Number(text:String,size:Int=36)=Text(text,color=RText,fontSize=size.sp,fontFamily=FontFamily.Monospace,fontWeight=FontWeight.SemiBold,letterSpacing=(-1).sp)
@Composable private fun Panel(content:@Composable ColumnScope.()->Unit) {
    Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=RSurface,contentColor=RText),shape=RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp),content=content)
    }
}
@Composable private fun Action(label:String,icon:ImageVector=Icons.Default.PlayArrow,onClick:()->Unit={}) {
    Button(onClick,Modifier.fillMaxWidth().heightIn(min=52.dp),shape=RoundedCornerShape(8.dp),colors=ButtonDefaults.buttonColors(containerColor=RButton,contentColor=Color.White)) { Icon(icon,null,Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(label) }
}
@Composable private fun Item(title:String,sub:String,icon:ImageVector=Icons.Default.Route,value:String?=null,onClick:()->Unit={}) {
    ListItem(headlineContent={Text(title)},supportingContent={Text(sub)},leadingContent={Icon(icon,null,tint=RMuted)},
        trailingContent={if(value!=null)Text(value,fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.titleMedium) else Icon(Icons.Default.ChevronRight,null)},
        colors=ListItemDefaults.colors(containerColor=RSurface),modifier=Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick=onClick))
}
@Composable private fun Empty(title:String,sub:String,icon:ImageVector=Icons.Default.Route) {
    Column(Modifier.fillMaxWidth().padding(vertical=56.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Surface(shape=RoundedCornerShape(24.dp),color=RHigh) { Icon(icon,null,Modifier.padding(24.dp).size(40.dp),tint=RChart) }
        Text(title,style=MaterialTheme.typography.titleLarge); Text(sub,color=RMuted,style=MaterialTheme.typography.bodyMedium)
    }
}
@Composable private fun RouteChoice(chosen:Int=0,onChoose:(Int)->Unit={}) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) { listOf("上班","下班").forEachIndexed { i,label ->
        SegmentedButton(selected=chosen==i,onClick={onChoose(i)},shape=SegmentedButtonDefaults.itemShape(i,2),label={Text(label)})
    } }
}
@Composable private fun ReviewPage(page:String,data:ReviewData) {
    val main=page in listOf("home","home-empty","home-active","home-permission","history","history-empty","ranking","stats","stats-sorted","stats-detail","stats-empty","stats-loading","stats-week","stats-home","stats-single","date-range","settings","settings-more")
    val title=when { page.startsWith("home")->"Delta T";page.startsWith("stats")||page=="ranking"||page=="date-range"->"个人纪录";page.startsWith("history")->"历史记录";page.startsWith("detail")->"单次详情";page.startsWith("settings")->"设置";page.startsWith("routes")->"路线库";page=="new-route"->"新建路线";page.startsWith("record")->"录制路线";page=="editor"||page=="recalculate"->"编辑计时点";page=="complete"->"本次完成";page=="countdown"->"准备出发";else->"上班" }
    val selected=when {page.startsWith("history")->1;page.startsWith("stats")||page=="ranking"||page=="date-range"->2;page.startsWith("settings")->3;else->0}
    Scaffold(containerColor=RBg,topBar={TopAppBar(title={Text(title)},navigationIcon={if(!main)IconButton({}){Icon(Icons.AutoMirrored.Filled.ArrowBack,"返回")}},actions={
        when { page.startsWith("detail")->IconButton({}){Icon(Icons.Default.MoreVert,"更多操作")};page=="routes"->IconButton({}){Icon(Icons.Default.Add,"新建路线")};page.startsWith("home")->IconButton({}){Icon(Icons.Default.GpsFixed,"定位状态",tint=RMuted)} }
    },colors=TopAppBarDefaults.topAppBarColors(containerColor=RBg))},bottomBar={if(main)NavigationBar(containerColor=RSurface){
        listOf("首页","历史","纪录","设置").forEachIndexed { i,t->NavigationBarItem(selected=selected==i,onClick={},colors=NavigationBarItemDefaults.colors(indicatorColor=Color(0xFF35424E),selectedIconColor=RText,selectedTextColor=RText,unselectedIconColor=RMuted,unselectedTextColor=RMuted),icon={Icon(listOf(Icons.Default.Home,Icons.Default.History,Icons.Default.BarChart,Icons.Default.Settings)[i],null)},label={Text(t)}) }
    }}) { padding ->
        when {
            page in listOf("waiting","timing","timing-held","complete","detecting","stop-dialog","privacy","timing-online","waiting-online","complete-online")->TimingReview(page,data,Modifier.padding(padding))
            page=="countdown"->Column(Modifier.padding(padding).fillMaxSize(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){Small("自动识别最近路线");Number("3",120);Small("驶过 A 后才开始计时");Spacer(Modifier.height(64.dp));OutlinedButton({}){Text("取消准备")}}
            page=="editor"||page=="recalculate"->EditorReview(data,Modifier.padding(padding))
            else->LazyColumn(Modifier.padding(padding).fillMaxSize(),contentPadding=PaddingValues(horizontal=16.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                item { when {
                    page.startsWith("home")->HomeReview(page,data)
                    page.startsWith("history")->HistoryReview(page,data)
                    page.startsWith("detail")->DetailReview(page,data)
                    page.startsWith("stats")||page=="ranking"||page=="date-range"->StatsReview(page,data)
                    page.startsWith("settings")->SettingsReview(page)
                    page.startsWith("routes")->RoutesReview(page)
                    page=="new-route"->NewRouteReview()
                    page.startsWith("record")->RecordingReview(page,data)
                    page=="delete-dialog"->DetailReview("detail",data)
                } }
            }
        }
    }
    when(page) {
        "privacy"->AlertDialog(onDismissRequest={},icon={Icon(Icons.Default.Map,null)},title={Text("启用高德地图？")},text={Text("加载在线路网需要高德地图服务。原始行程保存在本机；SDK 会处理提供地图所需的设备与网络信息。\n\n也可以继续使用简洁轨迹图。")},confirmButton={TextButton({}){Text("同意并启用")}},dismissButton={TextButton({}){Text("继续简洁模式")}})
        "stop-dialog"->AlertDialog(onDismissRequest={},icon={Icon(Icons.Default.StopCircle,null)},title={Text("结束本次计时？")},text={Text("轨迹会保留，本次成绩不计入纪录。")},confirmButton={TextButton({}){Text("结束计时")}},dismissButton={TextButton({}){Text("继续计时")}})
        "delete-dialog"->AlertDialog(onDismissRequest={},icon={Icon(Icons.Default.DeleteOutline,null)},title={Text("删除这条历史记录？")},text={Text("轨迹、分段和诊断数据会一起删除，无法恢复。")},confirmButton={TextButton({}){Text("确认删除")}},dismissButton={TextButton({}){Text("取消")}})
        "recalculate"->AlertDialog(onDismissRequest={},icon={Icon(Icons.Default.Calculate,null)},title={Text("重算历史成绩")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){Text("预演完成后显示可重算与无法重算的数量。");Text("原始轨迹与旧成绩保留。重算完成前继续使用旧计时点。",color=RMuted)}},confirmButton={TextButton({},enabled=false){Text("创建新版本并重算")}},dismissButton={TextButton({}){Text("取消")}})
    }
}

@Composable private fun HomeReview(page:String,data:ReviewData) {
    Column(verticalArrangement=Arrangement.spacedBy(20.dp)) {
        Panel {
            Row(verticalAlignment=Alignment.CenterVertically) { Box(Modifier.width(3.dp).height(16.dp).background(RAccent));Spacer(Modifier.width(8.dp));Small("下一程") }
            Text("准备出发",style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.SemiBold)
            Small("靠近起点后自动识别路线")
            Spacer(Modifier.height(4.dp));Action("一键准备")
        }
        Section("通勤路线")
        Surface(color=RSurface,shape=RoundedCornerShape(8.dp)) { Column {
            data.source.routeNames.forEachIndexed { index,name ->
                val latest=data.allTrips.filter{it.session.getString("route")==name}.maxBy { it.timestamp }
                ListItem(headlineContent={Text(name,fontWeight=FontWeight.Medium)},supportingContent={Text("已就绪 · 点击编辑计时点")},leadingContent={Icon(Icons.Default.Route,null,tint=RMuted)},trailingContent={Icon(Icons.Default.ChevronRight,null,tint=RMuted)},colors=ListItemDefaults.colors(containerColor=RSurface))
                Row(Modifier.fillMaxWidth().padding(start=56.dp,end=16.dp,bottom=18.dp),horizontalArrangement=Arrangement.SpaceBetween){Small("本周最快  —");Small("最近 ${time(latest.duration)}")}
                if(index<data.source.routeNames.lastIndex)HorizontalDivider(Modifier.padding(horizontal=16.dp),color=RLine.copy(alpha=.45f))
            }
        } }
        Row(verticalAlignment=Alignment.CenterVertically) { Icon(Icons.Default.History,null,Modifier.size(16.dp),tint=RMuted);Spacer(Modifier.width(8.dp));Small("本周暂无通勤记录") }
    }
}
@Composable private fun HistoryReview(page:String,data:ReviewData) {
    Surface(color=RSurface,shape=RoundedCornerShape(8.dp)) { Column {
        data.source.sessions.take(8).forEachIndexed { i,s ->
            val row=data.allTrips.firstOrNull { it.id==s.getString("id") }
            ListItem(headlineContent={Text(s.getString("route"),fontWeight=FontWeight.Medium)},overlineContent={Text(dateLabel(s.getLong("startedAt")))},supportingContent={Text(if(row!=null)"有效 · 质量 ${s.getString("quality")}" else "${if(s.getString("status")=="INTERRUPTED")"已中断" else "已结束"} · 不计纪录")},
                leadingContent={Icon(if(row==null)Icons.Default.Info else Icons.Default.Route,null,tint=RMuted)},
                trailingContent={Column(horizontalAlignment=Alignment.End){Text(time(row?.duration),fontFamily=FontFamily.Monospace,fontWeight=FontWeight.SemiBold,fontSize=18.sp);IconButton({}){Icon(Icons.Default.MoreHoriz,"记录菜单")}}},colors=ListItemDefaults.colors(containerColor=RSurface))
            if(i<7)HorizontalDivider(Modifier.padding(horizontal=16.dp),color=RLine.copy(alpha=.4f))
        }
    } }
}
@Composable private fun DetailReview(page:String,data:ReviewData) {
    Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Small("上班 · ${dateLabel(data.sample.getLong("startedAt"))}")
        if(page=="detail-quality")Panel { Icon(Icons.Default.Info,null,tint=RAccent);Text("已中断 · 不计纪录");Small("轨迹仍保留，可查看定位质量与分段情况") }
        else {Number(time(data.selectedEvaluation.longOrNull("durationNanos")),44);Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){Small("有效 · 质量 ${data.sample.getString("quality")}");Small("当前计时点")}
            Row(horizontalArrangement=Arrangement.spacedBy(24.dp)){Small("月 Δ ${data.selectedEvaluation.longOrNull("monthlyDeltaNanos")?.let(::delta)?:"—"}");Small("周 Δ ${data.selectedEvaluation.longOrNull("weeklyDeltaNanos")?.let(::delta)?:"—"}")}}
        ReviewRoute(data,Modifier.fillMaxWidth().height(250.dp),"complete")
        Section("分段成绩")
        SectorRows(data)
        Item("过滤后最高速度","峰值时刻 ${data.sample.longOrNull("maxSpeedWallTimeMillis")?.let(::dateLabel)?:"不可用"}",Icons.Default.Speed,"%.1f km/h".format((data.sample.doubleOrNull("maxSpeedMps")?:0.0)*3.6))
        Item("计时点与数据质量","当前版本 · 原始轨迹 ${data.track.size} 点",Icons.Default.Info)
    }
}
@Composable private fun SectorRows(data:ReviewData) {
    val sectors=data.selectedEvaluation.getJSONArray("sectors").objects()
    Column(Modifier.clip(RoundedCornerShape(10.dp))) { (0..2).forEach { i->
        val s=sectors.firstOrNull { it.getInt("index")==i }
        ListItem(headlineContent={Text(listOf("A → S1","S1 → S2","S2 → B")[i])},supportingContent={Text("月 ${s?.longOrNull("monthlyDeltaNanos")?.let(::delta)?:"—"}   周 ${s?.longOrNull("weeklyDeltaNanos")?.let(::delta)?:"—"}")},leadingContent={Text("%02d".format(i+1),color=RMuted)},trailingContent={Text(time(s?.longOrNull("durationNanos")),fontFamily=FontFamily.Monospace)},colors=ListItemDefaults.colors(containerColor=RSurface))
        if(i<2)HorizontalDivider(color=RLine.copy(alpha=.4f))
    } }
}

@Composable private fun StatsReview(page:String,data:ReviewData) {
    var sort by remember { mutableStateOf(if(page=="stats-sorted")"用时从快到慢" else "时间先后") }
    var menu by remember { mutableStateOf(false) }
    var direction by remember { mutableIntStateOf(if(page=="stats-home")1 else 0) }
    val monthRows=data.allTrips.filter { it.session.getString("route")==if(direction==0)"上班" else "下班" }.filter{dateOf(it.timestamp).monthValue==9}.sortedBy{it.timestamp}
    val periodRows=when(page){"stats-week"->monthRows.filter{dateOf(it.timestamp).dayOfMonth>=28};"stats-single"->monthRows.take(1);else->monthRows}
    val median=periodRows.map{it.duration}.sorted().let{a->if(a.size%2==1)a[a.size/2] else a[a.size/2-1]+(a[a.size/2]-a[a.size/2-1])/2}
    var selected by remember(direction) { mutableStateOf(periodRows.last()) }
    var sheet by remember { mutableStateOf(page=="stats-detail") }
    val empty=page=="stats-empty"
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        RouteChoice(direction){direction=it}
        PrimaryTabRow(selectedTabIndex=if(page=="ranking")0 else if(page=="stats-week")2 else 1,containerColor=RBg) { listOf("排行榜","月统计","周统计").forEachIndexed { i,t->Tab(selected=i==(if(page=="ranking")0 else if(page=="stats-week")2 else 1),onClick={},unselectedContentColor=RMuted,text={Text(t)}) } }
        if(page=="ranking") {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("本月","本周","全部","自定义").forEach { t->FilterChip(selected=t=="全部",onClick={},label={Text(t)}) }}
            val ranked=data.allTrips.filter { it.session.getString("route")=="上班" }.sortedBy { it.duration }
            Panel { Small("筛选范围 PB");Number(time(ranked.first().duration),40);Small("${ranked.size} 次有效行程 · 全部")
                val sectors=(0..2).mapNotNull{i->ranked.mapNotNull{t->t.evaluation.getJSONArray("sectors").objects().firstOrNull{e->e.getInt("index")==i}?.longOrNull("durationNanos")}.minOrNull()}
                Small("理论最佳 ${if(sectors.size==3)time(sectors.sum()) else "—"}") }
            ranked.take(5).forEachIndexed { i,t->Item("P${i+1}   ${time(t.duration)}", "${dateLabel(t.timestamp)} · GAP ${delta(t.duration-ranked.first().duration)}",Icons.Default.EmojiEvents) }
        } else {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){IconButton({}){Icon(Icons.Default.ChevronLeft,"上一个月")};Text(if(page=="stats-week")"09.28 — 10.04" else "2026年9月",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium);IconButton({}){Icon(Icons.Default.ChevronRight,"下一个月")}}
            if(page=="stats-loading"){LinearProgressIndicator(Modifier.fillMaxWidth());Empty("正在加载成绩","读取当前路线的有效行程",Icons.Default.BarChart)}
            else if(empty)Empty("这个周期还没有有效行程","切换月份查看已有记录",Icons.Default.BarChart)
            else {
                Panel { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Small("典型用时 · 中位数");Small("${periodRows.size} 次有效行程")};Number(time(median),38) }
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("与典型用时的差值",Modifier.weight(1f),style=MaterialTheme.typography.titleSmall)
                    Box { TextButton({menu=true}) {Icon(Icons.Default.Sort,null,Modifier.size(18.dp));Spacer(Modifier.width(4.dp));Text(sort,style=MaterialTheme.typography.labelMedium)}
                        DropdownMenu(menu,{menu=false}) {listOf("时间先后","用时从快到慢","用时从慢到快").forEach { t->DropdownMenuItem(text={Text(t)},onClick={sort=t;menu=false},leadingIcon={if(t==sort)Icon(Icons.Default.Check,null)})} }
                    }
                }
                val rows=when(sort){"用时从快到慢"->periodRows.sortedBy{it.duration};"用时从慢到快"->periodRows.sortedByDescending{it.duration};else->periodRows}
                val shown=if(sort=="时间先后")rows.takeLast(5) else rows.take(5)
                ReviewChart(shown,median,selected.id,periodRows.maxOf{abs(it.duration-median)}) { selected=it;sheet=true }
                Small("上方较慢 · 下方较快 · 零线为本期中位数")
                Small(if(sort=="时间先后")"最近 5 / ${periodRows.size} 次 · 左右滑动查看全部" else "前 5 / ${periodRows.size} 次 · 按用时排序")
                ListItem(headlineContent={Text("${dateLabel(selected.timestamp)} · ${time(selected.duration)}")},supportingContent={Text("比典型用时${if(selected.duration<median)"快" else "慢"} ${time(abs(selected.duration-median))}")},leadingContent={Icon(Icons.Default.Timer,null)},trailingContent={Icon(Icons.Default.ExpandLess,null)},colors=ListItemDefaults.colors(containerColor=RSurface),modifier=Modifier.clip(RoundedCornerShape(10.dp)).clickable{sheet=true})
                TextButton({}){Text(if(page=="stats-week")"各周典型用时" else "各月典型用时");Icon(Icons.Default.ExpandMore,null)}
            }
        }
    }
    if(page=="date-range") DatePickerDialog(onDismissRequest={},confirmButton={TextButton({}){Text("应用")}},dismissButton={TextButton({}){Text("取消")}}){DateRangePicker(state=rememberDateRangePickerState(initialSelectedStartDateMillis=LocalDate.of(2026,9,1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),initialSelectedEndDateMillis=LocalDate.of(2026,9,30).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()),modifier=Modifier.height(520.dp))}
    if(sheet)ModalBottomSheet(onDismissRequest={sheet=false},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),containerColor=RHigh) {
        Column(Modifier.padding(start=24.dp,end=24.dp,bottom=32.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Small("上班 · ${dateLabel(selected.timestamp)}");Number(time(selected.duration),40)
            Text("比中位数${if(selected.duration<median)"快" else "慢"} ${time(abs(selected.duration-median))}",color=RChart)
            HorizontalDivider(color=RLine)
            selected.evaluation.getJSONArray("sectors").objects().forEach { s->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(listOf("A → S1","S1 → S2","S2 → B")[s.getInt("index")]);Text(time(s.getLong("durationNanos")),fontFamily=FontFamily.Monospace)} }
            Small("有效 · 质量 ${selected.session.getString("quality")} · 当前计时点")
            Action("查看完整行程",Icons.Default.Route)
        }
    }
}
@Composable private fun ReviewChart(rows:List<ReviewTrip>,median:Long,selected:String,periodMaxGap:Long,onSelect:(ReviewTrip)->Unit) {
    val maxGap=periodMaxGap.coerceAtLeast(60_000_000_000)
    val range=ceil(maxGap/60_000_000_000.0).toInt()
    Row(Modifier.fillMaxWidth().height(222.dp)) {
        Column(Modifier.width(40.dp).height(186.dp),verticalArrangement=Arrangement.SpaceBetween){Small("+${range}分");Small("0");Small("−${range}分")}
        Box(Modifier.weight(1f)) {
            Canvas(Modifier.fillMaxWidth().height(186.dp)){listOf(0f,.5f,1f).forEach { ratio->drawLine(if(ratio==.5f)RMuted else RLine.copy(alpha=.4f),Offset(0f,size.height*ratio),Offset(size.width,size.height*ratio),if(ratio==.5f)2.dp.toPx() else 1.dp.toPx())}}
            Row(Modifier.fillMaxWidth()) {rows.forEach { trip->
                val gap=trip.duration-median
                Column(Modifier.weight(1f).semantics{contentDescription="查看行程 ${dateLabel(trip.timestamp)} ${time(trip.duration)} 差值 ${delta(gap)}"}.clickable{onSelect(trip)},horizontalAlignment=Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().height(186.dp)) {
                        val h=(abs(gap).toDouble()/(range*60_000_000_000.0)*92).toFloat().coerceAtLeast(3f)
                        Box(Modifier.align(Alignment.TopCenter).offset(y=(if(gap>=0)93-h else 93f).dp).width(30.dp).height(h.dp).background(if(trip.id==selected)RText else RChart,RoundedCornerShape(4.dp)))
                    }
                    Spacer(Modifier.height(8.dp));Small(dateOf(trip.timestamp).format(DateTimeFormatter.ofPattern("MM-dd")))
                }
            } }
        }
    }
}

@Composable private fun TimingReview(page:String,data:ReviewData,modifier:Modifier) {
    val waiting=page.startsWith("waiting")||page=="privacy"
    val complete=page.startsWith("complete")
    val detecting=page=="detecting"
    Column(modifier.fillMaxSize().padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){
            Row(verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.SatelliteAlt,null,Modifier.size(18.dp),tint=RMuted);Spacer(Modifier.width(6.dp));Small("卫星 —/—")}
            Small(if(complete)"已完成" else if(waiting)"等待起点" else if(detecting)"正在定位" else "S2 · 计时中")
        }
        if(detecting){Panel { CircularProgressIndicator(Modifier.size(28.dp),strokeWidth=3.dp);Text("等待有效定位",style=MaterialTheme.typography.headlineSmall);Small("获得定位后自动识别最近路线") };Spacer(Modifier.weight(1f));OutlinedButton({},Modifier.fillMaxWidth()){Text("取消准备")}}
        else {
            Panel {
                if(waiting){Small("上班 · 等待驶过起点 A");Number("0 m",44);Small("确认驶过 A 后自动开始计时")}
                else {Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Small(if(complete)"本次总用时" else "总用时");Small(if(complete)"COMPLETE" else "LIVE")};Number(time(if(complete)data.sample.getLong("durationNanos") else data.liveDuration),52)
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                        Small("月 Δ ${if(complete)data.selectedEvaluation.longOrNull("monthlyDeltaNanos")?.let(::delta)?:"—" else "—"}")
                        Small("周 Δ ${if(complete)data.selectedEvaluation.longOrNull("weeklyDeltaNanos")?.let(::delta)?:"—" else "—"}")}
                }
            }
            if(!waiting) {
                Surface(color=RSurface,shape=RoundedCornerShape(8.dp)) { Column(Modifier.padding(horizontal=12.dp,vertical=10.dp)) {
                    Row(Modifier.fillMaxWidth().padding(bottom=8.dp)) { Small("分段");Spacer(Modifier.width(6.dp));listOf("用时","月 Δ","周 Δ").forEach { Text(it,Modifier.weight(1f),color=RMuted,fontSize=11.sp,textAlign=androidx.compose.ui.text.style.TextAlign.End) } }
                    (0..2).forEach { i ->
                        val sector=data.selectedEvaluation.getJSONArray("sectors").objects().firstOrNull{it.getInt("index")==i}
                        val active=!complete&&i==1
                        Row(Modifier.fillMaxWidth().height(35.dp),verticalAlignment=Alignment.CenterVertically) {
                            Box(Modifier.width(2.dp).height(16.dp).background(if(active)RAccent else Color.Transparent));Spacer(Modifier.width(6.dp))
                            Text("S${i+1}",Modifier.width(28.dp),fontSize=13.sp,fontWeight=FontWeight.Bold,color=if(active)RAccent else RText)
                            Text(if(complete||i==0)time(sector?.longOrNull("durationNanos")) else if(active)"计时中" else "—",Modifier.weight(1f),fontFamily=FontFamily.Monospace,fontSize=13.sp,color=RText,textAlign=androidx.compose.ui.text.style.TextAlign.End)
                            listOf("monthlyDeltaNanos","weeklyDeltaNanos").forEach { key -> Text(if(complete)sector?.longOrNull(key)?.let(::delta)?:"—" else "—",Modifier.weight(1f),fontFamily=FontFamily.Monospace,fontSize=12.sp,color=benchmarkColor(if(complete)sector?.longOrNull(key) else null,key=="monthlyDeltaNanos"),textAlign=androidx.compose.ui.text.style.TextAlign.End) }
                        }
                        if(i<2)HorizontalDivider(color=RLine.copy(alpha=.3f))
                    }
                } }
            }
            if(page=="timing-held")Row(verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.Info,null,Modifier.size(16.dp),tint=RMuted);Spacer(Modifier.width(8.dp));Small("暂时无法匹配 · 保留最近确认值")}
            if(page.endsWith("online")) ReviewOnlineMap(data.source,Modifier.fillMaxWidth().weight(1f),if(waiting)"waiting" else if(complete)"complete" else "live")
            else ReviewRoute(data,Modifier.fillMaxWidth().weight(1f),if(waiting)"waiting" else if(complete)"complete" else "live")
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Small("最高 ${"%.1f".format((data.sample.doubleOrNull("maxSpeedMps")?:0.0)*3.6)} km/h");Small("过滤后峰值")}
            if(complete)Action("查看本次详情",Icons.Default.Route)
            else if(waiting){OutlinedButton({},Modifier.fillMaxWidth()){Text("更换路线")};TextButton({},Modifier.fillMaxWidth()){Text("取消准备")}}
            else OutlinedButton({},Modifier.fillMaxWidth()){Icon(Icons.Default.StopCircle,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text("结束本次（成绩无效）")}
        }
        if(!complete)Row(Modifier.fillMaxWidth().padding(bottom=12.dp),horizontalArrangement=Arrangement.Center){Icon(Icons.Default.Lock,null,Modifier.size(14.dp),tint=RMuted);Spacer(Modifier.width(6.dp));Small("可锁屏继续 · 驾驶中无需点击")}
    }
}

/** Native design canvas. Real recorded coordinates, equal-axis projection, explicit
 * simplified-map state. It makes no claim to be the online AMap tile rendering. */
@Composable private fun ReviewRoute(data:ReviewData,modifier:Modifier,phase:String) {
    val points=data.track
    val lat0=points.map{it.latitude}.average()
    val coords=points.map{(it.longitude*cos(Math.toRadians(lat0))) to -it.latitude}
    val minX=coords.minOf{it.first};val maxX=coords.maxOf{it.first};val minY=coords.minOf{it.second};val maxY=coords.maxOf{it.second}
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF303C47))) {
        Canvas(Modifier.fillMaxSize()) {
            val pad=48.dp.toPx();val scale=min((size.width-2*pad)/(maxX-minX),(size.height-2*pad)/(maxY-minY)).coerceAtLeast(1.0)
            val origin=Offset(((size.width-(maxX-minX)*scale)/2).toFloat(),((size.height-(maxY-minY)*scale)/2).toFloat())
            fun project(x:Double,y:Double)=origin+Offset(((x-minX)*scale).toFloat(),((y-minY)*scale).toFloat())
            val projected=coords.map{project(it.first,it.second)}
            val path=Path().apply{projected.forEachIndexed{i,p->if(i==0)moveTo(p.x,p.y) else lineTo(p.x,p.y)}}
            drawPath(path,Color(0xFF202B35),style=androidx.compose.ui.graphics.drawscope.Stroke(7.dp.toPx(),cap=StrokeCap.Round,join=StrokeJoin.Round))
            drawPath(path,Color(0xFF879BAA),style=androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx(),cap=StrokeCap.Round,join=StrokeJoin.Round))
            val currentIndex=when(phase){"waiting"->points.indexOf(data.checkpoints[0]);"complete"->points.lastIndex;else->data.liveIndex}.coerceAtLeast(0)
            if(phase!="waiting")drawPath(Path().apply{projected.take(currentIndex+1).forEachIndexed{i,p->if(i==0)moveTo(p.x,p.y) else lineTo(p.x,p.y)}},RText,style=androidx.compose.ui.graphics.drawscope.Stroke(3.5.dp.toPx(),cap=StrokeCap.Round,join=StrokeJoin.Round))
            val current=projected[currentIndex]
            val occupied=mutableListOf<Offset>()
            data.checkpoints.forEachIndexed { i,p->
                val a=project(p.longitude*cos(Math.toRadians(lat0)),-p.latitude)
                val offsets=listOf(Offset(-40.dp.toPx(),0f),Offset(40.dp.toPx(),0f),Offset(0f,-44.dp.toPx()),Offset(0f,44.dp.toPx()))
                val candidates=offsets.map{a+it}.map{Offset(it.x.coerceIn(30.dp.toPx(),size.width-30.dp.toPx()),it.y.coerceIn(74.dp.toPx(),size.height-44.dp.toPx()))}
                val center=candidates.firstOrNull{c->(c-current).getDistance()>48.dp.toPx()&&occupied.all{(it-c).getDistance()>44.dp.toPx()}}?:candidates.maxBy{(it-current).getDistance()}
                occupied+=center
                drawLine(Color(0xFFB3C3D0),a,center,1.dp.toPx())
                if((a-current).getDistance()>16.dp.toPx())drawCircle(RText,3.dp.toPx(),a)
                drawRoundRect(Color(0xFF202B35),center-Offset(18.dp.toPx(),14.dp.toPx()),androidx.compose.ui.geometry.Size(36.dp.toPx(),28.dp.toPx()),androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()))
                val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.rgb(232,237,242);textSize=12.sp.toPx();textAlign=Paint.Align.CENTER;typeface=android.graphics.Typeface.DEFAULT_BOLD}
                drawContext.canvas.nativeCanvas.drawText(listOf("A","S1","S2","B")[i],center.x,center.y-(paint.ascent()+paint.descent())/2,paint)
            }
            if(phase!="complete") {
                val arrow=Path().apply{moveTo(current.x,current.y-15.dp.toPx());lineTo(current.x-10.dp.toPx(),current.y+11.dp.toPx());lineTo(current.x,current.y+6.dp.toPx());lineTo(current.x+10.dp.toPx(),current.y+11.dp.toPx());close()}
                drawPath(arrow,Color(0xFF65B1FF));drawPath(arrow,Color.White,style=androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
            }
        }
        Surface(Modifier.align(Alignment.TopStart).padding(12.dp),color=Color(0xFF25323D),shape=RoundedCornerShape(8.dp)){Row(Modifier.padding(horizontal=8.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.Layers,null,Modifier.size(14.dp),tint=RMuted);Spacer(Modifier.width(5.dp));Text("简洁地图",fontSize=11.sp,color=RMuted)}}
    }
}

@Composable private fun SettingGroup(content:@Composable ColumnScope.()->Unit) {
    Surface(color=RSurface,shape=RoundedCornerShape(8.dp)) { Column(content=content) }
}
@Composable private fun SettingLine(title:String,sub:String,icon:ImageVector,last:Boolean=false) {
    ListItem(headlineContent={Text(title)},supportingContent={Text(sub)},leadingContent={Icon(icon,null,tint=RMuted)},trailingContent={Icon(Icons.Default.ChevronRight,null,tint=RMuted)},colors=ListItemDefaults.colors(containerColor=RSurface),modifier=Modifier.clickable{})
    if(!last)HorizontalDivider(Modifier.padding(start=56.dp,end=16.dp),color=RLine.copy(alpha=.4f))
}
@Composable private fun SettingsReview(page:String) {
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if(page=="settings") {
            SettingGroup { SettingLine("路线管理","上班、下班和自定义路线",Icons.Default.Route,true) }
            Section("锁屏继续计时")
            Small("开始前检查以下系统设置")
            SettingGroup {
                SettingLine("精确定位权限","已允许",Icons.Default.LocationOn)
                SettingLine("常驻通知权限","已允许",Icons.Default.NotificationsActive)
                SettingLine("系统定位","已开启",Icons.Default.GpsFixed)
                SettingLine("电池优化限制","建议允许不受限制",Icons.Default.BatterySaver)
                SettingLine("ColorOS 后台运行","前往系统应用设置",Icons.Default.Settings,true)
            }
            Section("计时与数据")
            SettingGroup { SettingLine("时间显示","0.01 秒 · 完成成绩严格比较",Icons.Default.Timer);SettingLine("节点提示","短振动 · 声音关闭",Icons.Default.Vibration,true) }
        } else {
            Section("计时")
            SettingGroup { SettingLine("时间显示","0.01 秒 · 实时估算防闪烁",Icons.Default.Timer);SettingLine("节点提示","短振动 · 声音关闭",Icons.Default.Vibration,true) }
            Section("数据与诊断")
            Panel { Row(verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.FileDownload,null,tint=RMuted);Spacer(Modifier.width(12.dp));Text("数据导出",style=MaterialTheme.typography.titleMedium)};Small("导出包含敏感轨迹信息，请妥善保管")
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton({}){Text("JSON 诊断包")};OutlinedButton({}){Text("CSV")}}
            }
            SettingGroup { SettingLine("运行诊断","查看进程退出、定位队列和丢弃记录",Icons.Default.MonitorHeart,true) }
            Section("关于");Small("Delta T 0.6.2-test");Small("个人侧载测试版")
        }
    }
}
@Composable private fun RoutesReview(page:String){Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
    if(page=="routes-empty")Empty("还没有路线","录制一次通勤，建立计时点") else {
        Small("路线内可以自由选择道路")
        listOf("上班","下班").forEach {name->ListItem(headlineContent={Text(name)},supportingContent={Text("A · S1 · S2 · B 已就绪")},leadingContent={Icon(if(name=="上班")Icons.Default.WorkOutline else Icons.Default.HomeWork,null)},trailingContent={IconButton({}){Icon(Icons.Default.MoreVert,"路线操作")}},colors=ListItemDefaults.colors(containerColor=RSurface),modifier=Modifier.clip(RoundedCornerShape(10.dp)))}
    };Action("新建路线",Icons.Default.Add)
}}
@Composable private fun NewRouteReview(){var name by remember{mutableStateOf("周末回家")};Column(verticalArrangement=Arrangement.spacedBy(20.dp)){
    Small("1 命名   →   2 录制   →   3 标记计时点")
    OutlinedTextField(name,{name=it.take(24)},label={Text("路线名称")},placeholder={Text("例如：周末回家")},singleLine=true,supportingText={Text("${name.length}/24")},modifier=Modifier.fillMaxWidth())
    Panel{Icon(Icons.Default.Route,null,tint=RChart);Text("一次录制，之后自动计时",style=MaterialTheme.typography.titleMedium);Small("出发前开始录制，到达并停车后标记四个计时点。点与点之间可选择任何道路。")}
    Button({},Modifier.fillMaxWidth().height(52.dp),enabled=name.isNotBlank(),shape=RoundedCornerShape(8.dp),colors=ButtonDefaults.buttonColors(containerColor=RButton,contentColor=Color.White)){Text("下一步：录制路线")}
}}
@Composable private fun RecordingReview(page:String,data:ReviewData){Column(verticalArrangement=Arrangement.spacedBy(16.dp)){
    Small("上班 · ${if(page=="record-idle")"准备录制" else if(page=="record-finished")"轨迹已保存" else "正在记录完整轨迹"}")
    if(page=="record-idle")Panel{Icon(Icons.Default.Route,null,tint=RChart);Text("准备好再出发",style=MaterialTheme.typography.headlineSmall);Small("驾驶过程中无需操作，到达并停车后结束录制。")}
    else Number(time(if(page=="recording")data.liveDuration else data.sample.getLong("durationNanos")),40)
    ReviewRoute(data,Modifier.fillMaxWidth().height(390.dp),if(page=="recording")"live" else "complete")
    Action(when(page){"record-idle"->"开始记录路线";"record-finished"->"标记 A / S1 / S2 / B";else->"我已停车，结束录制"},if(page=="record-finished")Icons.Default.EditLocationAlt else Icons.Default.RadioButtonChecked)
}}
@Composable private fun EditorReview(data:ReviewData,modifier:Modifier){Column(modifier.padding(horizontal=16.dp).fillMaxSize(),verticalArrangement=Arrangement.spacedBy(12.dp)){
    Small("点击轨迹或拖动选点 · 双指缩放")
    var point by remember{mutableIntStateOf(1)}
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()){listOf("A","S1","S2","B").forEachIndexed{i,t->SegmentedButton(selected=point==i,onClick={point=i},shape=SegmentedButtonDefaults.itemShape(i,4),label={Text(t)})}}
    Box(Modifier.weight(1f)) {ReviewRoute(data,Modifier.fillMaxSize(),"complete");Column(Modifier.align(Alignment.TopEnd).padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){listOf(Icons.Default.Add,Icons.Default.Remove,Icons.Default.CenterFocusStrong).forEach{icon->FilledTonalIconButton({}){Icon(icon,"地图控制")}}}}
    Panel{Small("当前 ${listOf("A","S1","S2","B")[point]}");Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){OutlinedButton({}){Icon(Icons.Default.ChevronLeft,null);Text("5 m")};OutlinedButton({}){Text("5 m");Icon(Icons.Default.ChevronRight,null)}};Small("沿轨迹微调 · 不改变原始轨迹")}
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)){OutlinedButton({},Modifier.weight(1f)){Text("上一步")};Button({},Modifier.weight(1f),shape=RoundedCornerShape(8.dp),colors=ButtonDefaults.buttonColors(containerColor=RButton,contentColor=Color.White)){Text(if(point==3)"保存新版本" else "下一步")}}
    Spacer(Modifier.height(8.dp))
}}
