package com.example.locationdevice

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi

class MainActivity : AppCompatActivity() {

    private lateinit var statusTextView: TextView
    private lateinit var locationTextView: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    private val LOCATION_PERMISSION_REQUEST_CODE = 123

    // Receptor de broadcast para actualizaciones
    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "LOCATION_UPDATE") {
                // Actualizar estado
                intent.getStringExtra("status")?.let { status ->
                    statusTextView.text = status
                }

                // Actualizar ubicación
                if (intent.hasExtra("latitude") && intent.hasExtra("longitude")) {
                    val latitude = intent.getDoubleExtra("latitude", 0.0)
                    val longitude = intent.getDoubleExtra("longitude", 0.0)
                    val accuracy = intent.getFloatExtra("accuracy", 0f)

                    locationTextView.text = "Ubicación: $latitude, $longitude\nPrecisión: $accuracy m"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Inicializar vistas
        statusTextView = findViewById(R.id.statusText)
        locationTextView = findViewById(R.id.locationText)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)

        // Configurar botones
        startButton.setOnClickListener {
            if (checkLocationPermission()) {
                startLocationService()
            } else {
                requestLocationPermission()
            }
        }

        stopButton.setOnClickListener {
            stopLocationService()
        }

        // Registrar receptor de broadcast
        LocalBroadcastManager.getInstance(this).registerReceiver(
            locationReceiver,
            IntentFilter("LOCATION_UPDATE")
        )

        // Verificar permisos inicialmente
        updateButtonStates(!isServiceRunning())
    }

    @OptIn(UnstableApi::class)
    private fun startLocationService() {
        Log.d("MainActivity", "Intentando iniciar el servicio de ubicación")

        val serviceIntent = Intent(this, LocationService::class.java)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Log.d("MainActivity", "Iniciando como foreground service")
                startForegroundService(serviceIntent)
            } else {
                Log.d("MainActivity", "Iniciando como service normal")
                startService(serviceIntent)
            }

            updateButtonStates(false)
            statusTextView.text = "Servicio iniciado"
            Toast.makeText(this, "Servicio de ubicación iniciado", Toast.LENGTH_SHORT).show()
            Log.d("MainActivity", "Servicio iniciado exitosamente")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error al iniciar servicio: ${e.message}", e)
            Toast.makeText(this, "Error al iniciar: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopLocationService() {
        val serviceIntent = Intent(this, LocationService::class.java)
        stopService(serviceIntent)

        updateButtonStates(true)
        statusTextView.text = "Servicio detenido"
        locationTextView.text = "Ubicación: No disponible"
        Toast.makeText(this, "Servicio de ubicación detenido", Toast.LENGTH_SHORT).show()
    }

    private fun updateButtonStates(serviceNotRunning: Boolean) {
        startButton.isEnabled = serviceNotRunning
        stopButton.isEnabled = !serviceNotRunning
    }

    private fun isServiceRunning(): Boolean {
        // Implementación simple
        return false
    }

    private fun checkLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            LOCATION_PERMISSION_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLocationService()
            } else {
                Toast.makeText(this, "Se requiere permiso de ubicación", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(locationReceiver)
        super.onDestroy()
    }
}