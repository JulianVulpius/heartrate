package com.example.heartrate_app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DeltaDataType
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.*
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    private var currentBpm by mutableStateOf(0.0)
    private var isMeasuring by mutableStateOf(false)
    private var isPermissionGranted by mutableStateOf(false)

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient()
    private var streamingJob: Job? = null

    private var serverIp by mutableStateOf("192.168.1.29") // Default IPv4-Address
    private var serverPort by mutableStateOf("8001")
    private var isEnergySaving by mutableStateOf(false)
    private var showSettings by mutableStateOf(false)

    private val measureCallback = object : MeasureCallback {
        override fun onAvailabilityChanged(dataType: DeltaDataType<*, *>, availability: Availability) {
            Log.d("HeartRate", "Sensor Status: $availability")
        }

        override fun onDataReceived(data: DataPointContainer) {
            val heartRatePoints = data.getData(DataType.HEART_RATE_BPM)
            heartRatePoints.lastOrNull()?.let { point ->
                currentBpm = point.value
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        isPermissionGranted = granted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        checkPermission()

        setContent {
            var isLoading by remember { mutableStateOf(true) }

            LaunchedEffect(Unit) {
                delay(1500)
                isLoading = false
            }

            LaunchedEffect(isEnergySaving) {
                if (isEnergySaving) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colors.background
                ) {
                    if (isLoading) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Heartrate Live")
                        }
                    } else if (showSettings) {

                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text("Server IP", style = MaterialTheme.typography.caption)
                            OutlinedTextField(
                                value = serverIp,
                                onValueChange = { serverIp = it },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Switch(
                                    checked = isEnergySaving,
                                    onCheckedChange = { isEnergySaving = it }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Power Saving", style = MaterialTheme.typography.body2)
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Text("Port", style = MaterialTheme.typography.caption)
                            OutlinedTextField(
                                value = serverPort,
                                onValueChange = { serverPort = it },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            Button(onClick = { showSettings = false }) {
                                Text("Save")
                            }
                        }

                    } else {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            if (isPermissionGranted) {

                                Box(
                                    modifier = Modifier.height(30.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (!isMeasuring) {
                                        Text(
                                            text = "⚙️",
                                            fontSize = 24.sp,
                                            modifier = Modifier.clickable { showSettings = true }
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Box(
                                    modifier = Modifier.height(70.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isEnergySaving && isMeasuring) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(
                                                text = "Measuring...",
                                                style = MaterialTheme.typography.body1,
                                                color = MaterialTheme.colors.secondary
                                            )
                                            Text(
                                                text = "Display dims soon",
                                                style = MaterialTheme.typography.caption,
                                                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                                            )
                                        }
                                    } else {
                                        Text(
                                            text = if (currentBpm > 0) "${currentBpm.toInt()} BPM" else "-- BPM",
                                            style = MaterialTheme.typography.h3,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                Button(onClick = { toggleMeasurement() }) {
                                    Text(if (isMeasuring) "Stop" else "Start")
                                }

                            } else {
                                Text("Sensor permission missing.")
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(onClick = { checkPermission() }) {
                                    Text("Allow")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun checkPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS)
            == PackageManager.PERMISSION_GRANTED) {
            isPermissionGranted = true
        } else {
            permissionLauncher.launch(Manifest.permission.BODY_SENSORS)
        }
    }

    private fun toggleMeasurement() {
        val measureClient = HealthServices.getClient(this).measureClient
        if (isMeasuring) {
            stopStreamingTimer()
            measureClient.unregisterMeasureCallbackAsync(DataType.HEART_RATE_BPM, measureCallback)
            isMeasuring = false
            disconnectWebSocket()
            currentBpm = 0.0
        } else {
            measureClient.registerMeasureCallback(DataType.HEART_RATE_BPM, measureCallback)
            isMeasuring = true
            connectWebSocket()
            startStreamingTimer()
        }
    }

    private fun startStreamingTimer() {
        streamingJob?.cancel()
        streamingJob = lifecycleScope.launch {
            while (isActive) {
                if (currentBpm > 0) {
                    sendDataToWebSocket(currentBpm)
                }
                delay(1000)
            }
        }
    }

    private fun stopStreamingTimer() {
        streamingJob?.cancel()
        streamingJob = null
    }

    private fun connectWebSocket() {
        val wsUrl = "ws://$serverIp:$serverPort/ws/heartrate"

        val request = Request.Builder().url(wsUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("WebSocket", "Connected to $wsUrl")

                try {
                    val initJson = JSONObject()
                    initJson.put("action", "session_start")
                    initJson.put("timestamp", System.currentTimeMillis())
                    webSocket?.send(initJson.toString())
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("WebSocket", "Error: ${t.message}")
            }
        })
    }

    private fun sendDataToWebSocket(bpm: Double) {
        if (webSocket != null) {
            try {
                val json = JSONObject()
                json.put("timestamp", System.currentTimeMillis())
                json.put("bpm", bpm)
                webSocket?.send(json.toString())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun disconnectWebSocket() {
        webSocket?.close(1000, "Measurement stopped")
        webSocket = null
    }

    override fun onDestroy() {
        super.onDestroy()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        stopStreamingTimer()
        if (isMeasuring) {
            HealthServices.getClient(this).measureClient
                .unregisterMeasureCallbackAsync(DataType.HEART_RATE_BPM, measureCallback)
        }
        disconnectWebSocket()
    }
}