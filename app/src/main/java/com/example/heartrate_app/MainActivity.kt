package com.example.heartrate_app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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

    // WICHTIG: Hier muss die echte IPv4-Adresse mit Port 8001 stehen!
    private val WEBSOCKET_URL = "ws://192.168.1.29:8001/ws/heartrate"
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient()

    private var streamingJob: Job? = null

    private val measureCallback = object : MeasureCallback {
        override fun onAvailabilityChanged(dataType: DeltaDataType<*, *>, availability: Availability) {
            Log.d("HeartRate", "Sensor Status: $availability")
        }

        override fun onDataReceived(data: DataPointContainer) {
            val heartRatePoints = data.getData(DataType.HEART_RATE_BPM)
            heartRatePoints.lastOrNull()?.let { point ->
                currentBpm = point.value
                Log.d("HeartRate", "Sensor Update: $currentBpm")
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
                    } else {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            if (isPermissionGranted) {
                                Text(
                                    text = if (currentBpm > 0) "${currentBpm.toInt()} BPM" else "-- BPM",
                                    style = MaterialTheme.typography.h3
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(onClick = { toggleMeasurement() }) {
                                    Text(if (isMeasuring) "Stop" else "Start")
                                }
                            } else {
                                Text("Sensor-Erlaubnis fehlt.")
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(onClick = { checkPermission() }) {
                                    Text("Erlauben")
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
                delay(1000) // Exakt 1000 Millisekunden (1 Sekunde) warten
            }
        }
    }

    private fun stopStreamingTimer() {
        streamingJob?.cancel()
        streamingJob = null
    }

    private fun connectWebSocket() {
        val request = Request.Builder().url(WEBSOCKET_URL).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("WebSocket", "Verbunden")
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("WebSocket", "Fehler: ${t.message}")
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
        webSocket?.close(1000, "Messung gestoppt")
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