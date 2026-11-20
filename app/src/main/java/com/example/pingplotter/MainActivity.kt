package com.example.pingplotter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.*

// ---------------- Ping engine (بدون نیاز به روت) ----------------

data class PingResult(val ip: String?, val rtt: Float?, val reached: Boolean)

suspend fun pingOnce(host: String, ttl: Int): PingResult = withContext(Dispatchers.IO) {
    val t0 = System.nanoTime()
    val proc: Process
    try {
        proc = ProcessBuilder("/system/bin/ping", "-c", "1", "-W", "2", "-w", "3", "-t", ttl.toString(), host)
            .redirectErrorStream(true).start()
    } catch (e: Exception) {
        return@withContext PingResult(null, null, false)
    }
    try {
        val out = proc.inputStream.bufferedReader().readText()
        val elapsed = (System.nanoTime() - t0) / 1_000_000f
        val ipRe = Regex("""(\d+\.\d+\.\d+\.\d+)""")
        // reached target: "64 bytes from 8.8.8.8: icmp_seq=1 ttl=117 time=12.3 ms"
        val ok = Regex("""from\s+\S*?\(?(\d+\.\d+\.\d+\.\d+)\)?.*time[=<]([\d.]+)""").find(out)
        if (ok != null) {
            return@withContext PingResult(ok.groupValues[1], ok.groupValues[2].toFloat(), true)
        }
        // intermediate hop: "From 192.168.1.1 icmp_seq=1 Time to live exceeded"
        if (out.contains("exceeded", ignoreCase = true)) {
            val ip = Regex("""[Ff]rom\s+\S*?\(?(\d+\.\d+\.\d+\.\d+)""").find(out)?.groupValues?.get(1)
                ?: ipRe.find(out)?.value
            return@withContext PingResult(ip, elapsed, false)
        }
        PingResult(null, null, false)
    } catch (e: Exception) {
        PingResult(null, null, false)
    } finally {
        proc.destroy()
    }
}

// ---------------- State ----------------

class Hop(val ttl: Int) {
    var ip by mutableStateOf("*")
    val samples = mutableStateListOf<Float?>()

    fun add(r: PingResult) {
        if (r.ip != null) ip = r.ip
        samples.add(r.rtt)
        if (samples.size > 150) samples.removeAt(0)
    }

    private val vals get() = samples.filterNotNull()
    val last get() = samples.lastOrNull()
    val avg get() = vals.takeIf { it.isNotEmpty() }?.average()?.toFloat()
    val min get() = vals.minOrNull()
    val max get() = vals.maxOrNull()
    val loss get() = if (samples.isEmpty()) 0f else samples.count { it == null } * 100f / samples.size
}

class PingVM : ViewModel() {
    var host by mutableStateOf("8.8.8.8")
    var intervalMs by mutableStateOf(1000L)
    var running by mutableStateOf(false)
    var selected by mutableStateOf(0)
    val hops = mutableStateListOf<Hop>()
    private var job: Job? = null

    fun toggle() = if (running) stop() else start()

    fun stop() {
        job?.cancel(); running = false
    }

    fun start() {
        job?.cancel()
        hops.clear()
        running = true
        job = viewModelScope.launch {
            // مرحله ۱: کشف مسیر (traceroute)
            var ttl = 1
            while (ttl <= 30 && isActive) {
                val h = Hop(ttl).also { hops.add(it) }
                val r = pingOnce(host.trim(), ttl)
                h.add(r)
                if (r.reached) break
                ttl++
            }
            selected = hops.lastIndex.coerceAtLeast(0)
            // مرحله ۲: پینگ مداوم همه‌ی هاپ‌ها
            while (isActive) {
                delay(intervalMs)
                coroutineScope {
                    hops.map { h -> async { h.add(pingOnce(host.trim(), h.ttl)) } }.awaitAll()
                }
            }
        }
    }
}

// ---------------- UI ----------------

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface { App() } } }
    }
}

@Composable
fun App(vm: PingVM = viewModel()) {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = vm.host, onValueChange = { vm.host = it },
                label = { Text("Host / IP") }, singleLine = true,
                modifier = Modifier.weight(1f), enabled = !vm.running
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { vm.toggle() }) { Text(if (vm.running) "Stop" else "Start") }
        }
        Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Interval: ${vm.intervalMs} ms", fontSize = 12.sp)
            Slider(
                value = vm.intervalMs.toFloat(), onValueChange = { vm.intervalMs = it.toLong() },
                valueRange = 200f..5000f, modifier = Modifier.padding(start = 8.dp)
            )
        }

        // نمودار بزرگ هاپ انتخاب‌شده
        vm.hops.getOrNull(vm.selected)?.let { h ->
            Text(
                "Hop ${h.ttl}  ${h.ip}   avg ${fmt(h.avg)} ms   loss ${"%.0f".format(h.loss)}%",
                fontSize = 13.sp, modifier = Modifier.padding(vertical = 4.dp)
            )
            Chart(h.samples, Modifier.fillMaxWidth().height(180.dp).background(Color(0xFF1B1B1F)))
        }

        Spacer(Modifier.height(8.dp))
        HeaderRow()
        LazyColumn {
            itemsIndexed(vm.hops) { i, h ->
                Row(
                    Modifier.fillMaxWidth().clickable { vm.selected = i }
                        .background(if (i == vm.selected) Color(0x33FFFFFF) else Color.Transparent)
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Cell("${h.ttl}", 0.06f)
                    Cell(h.ip, 0.24f)
                    Cell("%.0f%%".format(h.loss), 0.10f, if (h.loss > 0) Color(0xFFFF6B6B) else Color.Unspecified)
                    Cell(fmt(h.last), 0.10f)
                    Cell(fmt(h.avg), 0.10f)
                    Cell(fmt(h.max), 0.10f)
                    Chart(h.samples, Modifier.weight(0.30f).height(28.dp), thin = true)
                }
            }
        }
    }
}

@Composable
fun HeaderRow() = Row(Modifier.fillMaxWidth()) {
    Cell("#", 0.06f); Cell("IP", 0.24f); Cell("Loss", 0.10f)
    Cell("Last", 0.10f); Cell("Avg", 0.10f); Cell("Max", 0.10f); Cell("Graph", 0.30f)
}

@Composable
fun RowScope.Cell(text: String, w: Float, color: Color = Color.Unspecified) =
    Text(text, Modifier.weight(w), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = color, maxLines = 1)

fun fmt(v: Float?) = v?.let { "%.0f".format(it) } ?: "-"

@Composable
fun Chart(samples: List<Float?>, modifier: Modifier, thin: Boolean = false) {
    Canvas(modifier) {
        if (samples.size < 2) return@Canvas
        val maxV = maxOf(50f, samples.filterNotNull().maxOrNull() ?: 50f)
        val dx = size.width / (samples.size - 1)
        val path = Path()
        var started = false
        samples.forEachIndexed { i, v ->
            val x = i * dx
            if (v == null) {
                // پکت از دست رفته: خط قرمز عمودی
                drawLine(Color(0xFFFF4D4D), Offset(x, 0f), Offset(x, size.height), strokeWidth = if (thin) 1f else 2f)
                started = false
            } else {
                val y = size.height - (v / maxV) * size.height
                val color = when {
                    v < 50 -> Color(0xFF4CD964); v < 150 -> Color(0xFFFFCC00); else -> Color(0xFFFF9500)
                }
                if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
                if (!thin) drawCircle(color, 3f, Offset(x, y))
            }
        }
        drawPath(path, Color(0xFF64B5F6), style = Stroke(width = if (thin) 1.5f else 3f))
        if (!thin) {
            drawLine(Color.Gray, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1f)
            // مقدار ماکسیمم محور
        }
    }
}
