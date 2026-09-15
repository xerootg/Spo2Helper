package com.flyfish233.spo2helper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.flyfish233.spo2helper.shared.Reading
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { WatchScreen() } }
    }
}

@Composable
fun WatchScreen() {
    val context = LocalContext.current
    val state by MeasurementBus.state.collectAsState()
    var hasSensorPermission by remember { mutableStateOf(Permissions.hasHeartRate(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { hasSensorPermission = Permissions.hasHeartRate(context) }

    LaunchedEffect(Unit) {
        if (!hasSensorPermission) permissionLauncher.launch(Permissions.toRequest(context))
    }

    val listState = rememberScalingLazyListState()
    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
    ) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
        ) {
            item {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.title3,
                    textAlign = TextAlign.Center,
                )
            }
            item { StateBlock(state) }
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    onClick = { MeasureService.start(context) },
                    enabled = hasSensorPermission && state !is MeasurementState.Measuring,
                    label = { Text(stringResource(R.string.measure_now)) },
                    colors = ChipDefaults.primaryChipColors(),
                )
            }
            if (!hasSensorPermission) {
                item {
                    Chip(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        onClick = { permissionLauncher.launch(Permissions.toRequest(context)) },
                        label = { Text(stringResource(R.string.grant_permissions)) },
                        colors = ChipDefaults.secondaryChipColors(),
                    )
                }
            }
            item {
                Text(
                    text = stringResource(R.string.spo2_hint),
                    style = MaterialTheme.typography.caption2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun StateBlock(state: MeasurementState) {
    when (state) {
        MeasurementState.Idle -> Caption(stringResource(R.string.state_idle))
        MeasurementState.Measuring -> Progress(stringResource(R.string.state_measuring))
        MeasurementState.ReadingSpo2 -> Progress(stringResource(R.string.state_reading_spo2))
        MeasurementState.Sending -> Progress(stringResource(R.string.state_sending))
        is MeasurementState.Failed -> Caption(state.message)
        is MeasurementState.Done -> ReadingBlock(state.reading)
    }
}

@Composable
private fun Progress(label: String) {
    CircularProgressIndicator(modifier = Modifier.padding(4.dp))
    Caption(label)
}

@Composable
private fun Caption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.caption1,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

@Composable
private fun ReadingBlock(reading: Reading) {
    val spo2 = reading.spo2Estimate?.let { stringResource(R.string.spo2_est_fmt, it) }
        ?: stringResource(R.string.spo2_est_unavailable)
    val hr = reading.heartRateBpm?.let { stringResource(R.string.heart_rate_fmt, it) }
        ?: reading.ppgPulseBpm?.let { stringResource(R.string.heart_rate_fmt, it) }
        ?: stringResource(R.string.heart_rate_unavailable)
    val sleep = reading.sleepSpo2Percent?.let { stringResource(R.string.spo2_sleep_fmt, it) }
    Text(text = spo2, style = MaterialTheme.typography.display3, textAlign = TextAlign.Center)
    Text(text = hr, style = MaterialTheme.typography.title3, textAlign = TextAlign.Center)
    if (sleep != null) Caption(sleep)
    reading.note?.let { Caption(it) }
}

private val timeFormat = DateTimeFormatter.ofPattern("EEE HH:mm")

fun formatTime(epochMillis: Long): String =
    timeFormat.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
