package com.flyfish233.spo2helper

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.flyfish233.spo2helper.shared.Protocol
import com.flyfish233.spo2helper.shared.Reading
import com.flyfish233.spo2helper.shared.Spo2Config
import com.flyfish233.spo2helper.shared.Spo2Sample
import com.flyfish233.spo2helper.shared.Spo2Source
import com.flyfish233.spo2helper.shared.WearLink
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ReadingStore.load(this)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                PhoneScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneScreen() {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val latest by ReadingStore.latest.collectAsState()
    val running by MonitorService.running.collectAsState()
    var interval by remember { mutableStateOf(ReadingStore.intervalMinutes(context).toString()) }
    var status by remember { mutableStateOf("") }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LatestCard(latest)

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    scope.launch {
                        val sent = runCatching { WearLink(context).broadcast(Protocol.PATH_MEASURE) }.getOrDefault(0)
                        status = if (sent > 0) resources.getString(R.string.sent_fmt, sent)
                        else resources.getString(R.string.no_watch)
                    }
                },
            ) { Text(stringResource(R.string.send_test)) }
            if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    modifier = Modifier.width(140.dp),
                    value = interval,
                    onValueChange = { interval = it.filter(Char::isDigit).take(4) },
                    label = { Text(stringResource(R.string.interval_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    enabled = !running,
                )
                Spacer(Modifier.width(16.dp))
                if (running) {
                    OutlinedButton(onClick = { MonitorService.stop(context) }) {
                        Text(stringResource(R.string.disable_service))
                    }
                } else {
                    Button(
                        enabled = (interval.toIntOrNull() ?: 0) >= 1,
                        onClick = {
                            ReadingStore.setIntervalMinutes(context, interval.toInt())
                            MonitorService.start(context)
                        },
                    ) { Text(stringResource(R.string.enable_service)) }
                }
            }
            if (running) Text(stringResource(R.string.monitor_running), style = MaterialTheme.typography.bodySmall)

            ChannelsCard(latest)

            PhoneHealthConnectCard()

            Text(stringResource(R.string.limitation), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun LatestCard(reading: Reading?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.latest_title), style = MaterialTheme.typography.titleMedium)
            if (reading == null) {
                Text(stringResource(R.string.no_readings))
                return@Column
            }
            val spo2 = reading.spo2Estimate
            Text(
                spo2?.let { stringResource(R.string.spo2_est_fmt, it) } ?: stringResource(R.string.spo2_est_unavailable),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            val ratio = reading.ratio
            val red = reading.redChannel
            val ir = reading.irChannel
            if (spo2 != null && ratio != null && red != null && ir != null) {
                Text(stringResource(R.string.spo2_est_caption, ratio, red, ir), style = MaterialTheme.typography.bodySmall)
            }
            Text(
                reading.heartRateBpm?.let { stringResource(R.string.heart_rate_fmt, it) }
                    ?: stringResource(R.string.heart_rate_unavailable),
                style = MaterialTheme.typography.titleMedium,
            )
            reading.ppgPulseBpm?.let { Text(stringResource(R.string.heart_rate_ppg_fmt, it), style = MaterialTheme.typography.bodySmall) }
            val sleep = reading.sleepSpo2Percent
            val sleepTime = reading.sleepSpo2Time
            Text(
                if (sleep != null && sleepTime != null) stringResource(R.string.spo2_sleep_fmt, sleep, formatTime(sleepTime))
                else stringResource(R.string.spo2_sleep_unavailable),
                style = MaterialTheme.typography.bodySmall,
            )
            val sensor = reading.sensorName
            val rate = reading.sampleRateHz
            if (sensor != null && rate != null) Text(stringResource(R.string.sensor_fmt, sensor, rate), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.measured_at_fmt, formatTime(reading.measuredAt)), style = MaterialTheme.typography.bodySmall)
            reading.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun ChannelsCard(reading: Reading?) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val saved by ReadingStore.config.collectAsState()
    var red by remember(saved) { mutableStateOf(saved.redChannel.toString()) }
    var ir by remember(saved) { mutableStateOf(saved.irChannel.toString()) }
    var a by remember(saved) { mutableStateOf(saved.a.toString()) }
    var b by remember(saved) { mutableStateOf(saved.b.toString()) }
    var seconds by remember(saved) { mutableStateOf(saved.captureSeconds.toString()) }
    var reference by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }

    fun current(): Spo2Config? {
        val r = red.toIntOrNull() ?: return null
        val i = ir.toIntOrNull() ?: return null
        val ca = a.toDoubleOrNull() ?: return null
        val cb = b.toDoubleOrNull() ?: return null
        val sec = seconds.toIntOrNull()?.coerceIn(5, 60) ?: return null
        return Spo2Config(redChannel = r, irChannel = i, a = ca, b = cb, captureSeconds = sec)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.channels_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.channels_hint), style = MaterialTheme.typography.bodySmall)

            if (reading != null && reading.channels.isNotEmpty()) {
                Text(stringResource(R.string.channels_header), style = MaterialTheme.typography.labelSmall)
                for (ch in reading.channels) {
                    val marker = when (ch.index) {
                        reading.redChannel -> " ← red"
                        reading.irChannel -> " ← IR"
                        else -> ""
                    }
                    Text(
                        stringResource(
                            R.string.channel_row_fmt, ch.index, ch.dc, ch.ac, ch.perfusion * 100, ch.dominantHz, ch.snr
                        ) + marker,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (ch.pulsatile) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(red, { red = it }, stringResource(R.string.red_channel), Modifier.weight(1f))
                NumberField(ir, { ir = it }, stringResource(R.string.ir_channel), Modifier.weight(1f))
                NumberField(seconds, { seconds = it }, stringResource(R.string.capture_seconds), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(a, { a = it }, stringResource(R.string.coef_a), Modifier.weight(1f), decimal = true)
                NumberField(b, { b = it }, stringResource(R.string.coef_b), Modifier.weight(1f), decimal = true)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                NumberField(reference, { reference = it }, stringResource(R.string.reference_spo2), Modifier.weight(1f), decimal = true)
                val ratio = reading?.ratio
                TextButton(
                    enabled = ratio != null && reference.toDoubleOrNull() != null && b.toDoubleOrNull() != null,
                    onClick = {
                        // One-point calibration: shift a so the last reading matches the reference.
                        val cb = b.toDouble()
                        val newA = reference.toDouble() + cb * ratio!!
                        a = "%.1f".format(newA)
                        status = resources.getString(R.string.calibrated_fmt, newA, cb)
                    },
                ) { Text(stringResource(R.string.calibrate)) }
            }
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = current() != null,
                onClick = {
                    val cfg = current() ?: return@Button
                    ReadingStore.saveConfig(context, cfg)
                    scope.launch {
                        val sent = runCatching { WearLink(context).broadcast(Protocol.PATH_CONFIG, cfg.toBytes()) }.getOrDefault(0)
                        status = if (sent > 0) resources.getString(R.string.config_sent) else resources.getString(R.string.no_watch)
                    }
                },
            ) { Text(stringResource(R.string.send_config)) }
            if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun NumberField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    decimal: Boolean = false,
) {
    OutlinedTextField(
        modifier = modifier,
        value = value,
        onValueChange = { text -> onChange(text.filter { it.isDigit() || it == '-' || (decimal && it == '.') }.take(7)) },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        singleLine = true,
    )
}

@Composable
private fun PhoneHealthConnectCard() {
    val context = LocalContext.current
    val source = remember { Spo2Source(context) }
    var available by remember { mutableStateOf(source.isAvailable()) }
    var granted by remember { mutableStateOf(false) }
    var sample by remember { mutableStateOf<Spo2Sample?>(null) }
    var loaded by remember { mutableStateOf(false) }

    suspend fun refresh() {
        available = source.isAvailable()
        granted = source.hasPermission()
        sample = if (granted) runCatching { source.latest() }.getOrNull() else null
        loaded = true
    }

    val scope = rememberCoroutineScope()
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { scope.launch { refresh() } }
    LaunchedEffect(Unit) { refresh() }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.phone_hc_title), style = MaterialTheme.typography.titleMedium)
            when {
                !available -> Text(stringResource(R.string.phone_hc_missing))
                !granted -> OutlinedButton(onClick = { permissionLauncher.launch(arrayOf(source.readPermission)) }) {
                    Text(stringResource(R.string.phone_hc_grant))
                }
                sample != null -> Text(
                    stringResource(
                        R.string.phone_hc_value_fmt, sample!!.percent, formatTime(sample!!.time.toEpochMilli())
                    ),
                    style = MaterialTheme.typography.headlineSmall,
                )
                loaded -> Text(stringResource(R.string.phone_hc_empty))
            }
        }
    }
}

private val timeFormat = DateTimeFormatter.ofPattern("EEE d MMM HH:mm")

fun formatTime(epochMillis: Long): String =
    timeFormat.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
