package com.example.locationdevice

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.gms.location.*
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileWriter
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

class LocationService : Service() {

    private val TAG = "LocationServiceTAG"

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    // Cliente MQTT
    private var mqttClient: MqttClient? = null
    private val clientId = "AndroidDevice_${UUID.randomUUID().toString().substring(0, 8)}"
    private val isConnecting = AtomicBoolean(false)
    private var isConnected = false
    private var useLocalStorage = true // Comenzar en modo local por defecto

    // Configuración MQTT para AWS IoT
    private val iotEndpoint = "d025874830hkteiu1u9o9-ats.iot.us-east-1.amazonaws.com"
    private val topic = "devices/location"

    // Nombres de archivos de certificados
    private val caCertFileName = "AmazonRootCA1.pem"
    private val clientCertFileName = "5380b2554d73d1c2afe68cbc61e71f02d6fb314b1d660423b70f50eaa2c3cac8-certificate.pem.crt"
    private val privateKeyFileName = "5380b2554d73d1c2afe68cbc61e71f02d6fb314b1d660423b70f50eaa2c3cac8-private.pem.key"

    private val NOTIFICATION_ID = 12345
    private val CHANNEL_ID = "location_service_channel"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val locationUpdateRunnable = mutableListOf<Runnable>()

    // Parámetros de ubicación real
    private val UPDATE_INTERVAL = 5000L  // 5 segundos entre actualizaciones
    private val FASTEST_INTERVAL = 3000L // No solicitar más rápido que esto
    private val SMALLEST_DISPLACEMENT = 5f // 5 metros mínimo para actualizar

    override fun onCreate() {
        super.onCreate()
        try {
            Log.d(TAG, "🔵 Servicio onCreate iniciado")

            // Verificar permisos
            if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "❌ ERROR: No tenemos permisos de ubicación")
                stopSelf()
                return
            } else {
                Log.d(TAG, "✅ Permisos de ubicación OK")
            }

            // Inicializar cliente de ubicación
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

            // Configurar callback de ubicación
            setupLocationCallback()

            // Iniciar como servicio en primer plano con notificación
            startForeground(NOTIFICATION_ID, createNotification("Iniciando servicio de ubicación..."))

            // Listar archivos en assets para debug
            listAssetFiles()

            // Inicializar conexión MQTT
            initMqttClient()

            // Iniciar actualizaciones de ubicación reales
            startLocationUpdates()

            Log.d(TAG, "🟢 Servicio iniciado completamente")
        } catch (e: Exception) {
            Log.e(TAG, "❌ ERROR FATAL en onCreate: ${e.message}", e)
            e.printStackTrace()
        }
    }

    private fun listAssetFiles() {
        try {
            Log.d(TAG, "Listando archivos en assets:")
            val assetFiles = applicationContext.assets.list("")
            assetFiles?.forEach { Log.d(TAG, "- $it") }
        } catch (e: Exception) {
            Log.e(TAG, "Error listando assets: ${e.message}")
        }
    }

    private fun initMqttClient() {
        Thread {
            try {
                Log.d(TAG, "🔄 Inicializando cliente MQTT Paho para AWS IoT...")

                // Preparar directorio de certificados
                val certsDir = File(filesDir, "mqtt_certs")
                if (!certsDir.exists()) {
                    certsDir.mkdirs()
                }

                // Extraer certificados de assets a archivos
                val caFile = File(certsDir, "ca.pem")
                val clientCertFile = File(certsDir, "client.crt")
                val privateKeyFile = File(certsDir, "private.key")

                // Copiar certificados
                assets.open(caCertFileName).use { input ->
                    FileOutputStream(caFile).use { output -> input.copyTo(output) }
                }

                assets.open(clientCertFileName).use { input ->
                    FileOutputStream(clientCertFile).use { output -> input.copyTo(output) }
                }

                assets.open(privateKeyFileName).use { input ->
                    FileOutputStream(privateKeyFile).use { output -> input.copyTo(output) }
                }

                // Crear SSLContext con los certificados
                val sslContext = createSSLContext(clientCertFile, privateKeyFile, caFile)

                // Configurar opciones MQTT
                val options = MqttConnectOptions().apply {
                    isCleanSession = true
                    connectionTimeout = 60
                    keepAliveInterval = 30
                    socketFactory = sslContext.socketFactory

                    // Configurar Last Will Testament
                    setWill(
                        "$topic/status",
                        """{"deviceId":"$clientId","status":"disconnected"}""".toByteArray(),
                        0,
                        false
                    )
                }

                // URL del broker AWS IoT
                val awsIotBroker = "ssl://$iotEndpoint:443"
                Log.d(TAG, "Conectando a AWS IoT: $awsIotBroker")

                // Crear cliente MQTT
                mqttClient = MqttClient(awsIotBroker, clientId, MemoryPersistence())

                // Configurar callbacks
                mqttClient?.setCallback(object : MqttCallback {
                    override fun connectionLost(cause: Throwable?) {
                        Log.e(TAG, "Conexión AWS IoT perdida: ${cause?.message}")
                        isConnected = false

                        // Activar modo local
                        activateLocalMode("Conexión perdida")

                        // Programar un intento de reconexión con retraso
                        val delay = 10000L  // 10 segundos
                        Handler(Looper.getMainLooper()).postDelayed({
                            if (mqttClient?.isConnected != true) {
                                Log.d(TAG, "Intentando reconexión automática a AWS IoT...")
                                connectMqtt(options)
                            }
                        }, delay)
                    }

                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        if (topic != null && message != null) {
                            Log.d(TAG, "Mensaje recibido en $topic: ${String(message.payload)}")
                        }
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) {
                        Log.d(TAG, "✅ Mensaje entregado a AWS IoT")
                    }
                })

                // Conectar al broker
                connectMqtt(options)

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error inicializando MQTT: ${e.message}")
                e.printStackTrace()
                activateLocalMode("Error MQTT: ${e.javaClass.simpleName}")
            }
        }.start()
    }

    private fun connectMqtt(options: MqttConnectOptions) {
        if (isConnecting.getAndSet(true)) {
            Log.d(TAG, "⚠️ Conexión a AWS IoT ya en progreso")
            return
        }

        try {
            Log.d(TAG, "🔄 Conectando a AWS IoT vía Paho...")

            mainHandler.post {
                updateNotification("Conectando a AWS IoT...")
                sendStatusUpdate("Conectando a AWS IoT...")
            }

            // Intentar conexión con timeout personalizado
            mqttClient?.connect(options)

            Log.d(TAG, "✅ Conectado a AWS IoT exitosamente")
            isConnected = true
            useLocalStorage = false

            // Suscribirse a temas (opcional)
            mqttClient?.subscribe("$topic/commands", 0)

            // Enviar mensaje de conexión
            val connectionMessage = JSONObject().apply {
                put("deviceId", clientId)
                put("status", "connected")
                put("timestamp", System.currentTimeMillis())
            }

            publishMessage("$topic/status", connectionMessage.toString())

            // Actualizar UI
            mainHandler.post {
                updateNotification("Conectado a AWS IoT")
                sendStatusUpdate("Enviando datos a MongoDB vía IoT")
            }

        } catch (e: MqttException) {
            Log.e(TAG, "❌ Error MQTT: ${e.message}, Código: ${e.reasonCode}")

            // Logging detallado
            when (e.reasonCode) {
                MqttException.REASON_CODE_INVALID_PROTOCOL_VERSION.toInt() -> Log.e(TAG, "Versión MQTT inválida")
                MqttException.REASON_CODE_INVALID_CLIENT_ID.toInt() -> Log.e(TAG, "ID de cliente rechazado")
                MqttException.REASON_CODE_BROKER_UNAVAILABLE.toInt() -> Log.e(TAG, "Broker no disponible")
                MqttException.REASON_CODE_FAILED_AUTHENTICATION.toInt() -> Log.e(TAG, "Error de autenticación - Certificados incorrectos")
                MqttException.REASON_CODE_NOT_AUTHORIZED.toInt() -> Log.e(TAG, "No autorizado - Política restrictiva")
                MqttException.REASON_CODE_CONNECTION_LOST.toInt() -> Log.e(TAG, "Conexión perdida")
                MqttException.REASON_CODE_CLIENT_EXCEPTION.toInt() -> Log.e(TAG, "Excepción de cliente - Problema de red")
                else -> Log.e(TAG, "Otro error MQTT: ${e.reasonCode}")
            }

            // Activar modo local
            activateLocalMode("Error de conexión: ${e.reasonCode}")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error general: ${e.message}")
            e.printStackTrace()
            activateLocalMode("Error: ${e.javaClass.simpleName}")
        } finally {
            isConnecting.set(false)
        }
    }

    private fun createSSLContext(clientCertFile: File, privateKeyFile: File, caFile: File): SSLContext {
        try {
            // Cargar certificado CA
            val caFactory = CertificateFactory.getInstance("X.509")
            val caInputStream = FileInputStream(caFile)
            val caCert = caFactory.generateCertificate(caInputStream)
            caInputStream.close()

            // Cargar certificado cliente
            val clientCertInputStream = FileInputStream(clientCertFile)
            val clientCert = caFactory.generateCertificate(clientCertInputStream)
            clientCertInputStream.close()

            // Crear KeyStore
            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
            keyStore.load(null)

            // Añadir certificado CA
            keyStore.setCertificateEntry("ca-certificate", caCert)

            // Añadir certificado cliente
            keyStore.setCertificateEntry("client-certificate", clientCert)

            // Configurar TrustManager
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(keyStore)

            // Configurar KeyManager
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(keyStore, null)

            // Crear y configurar SSLContext
            val sslContext = SSLContext.getInstance("TLSv1.2")
            sslContext.init(kmf.keyManagers, tmf.trustManagers, null)

            Log.d(TAG, "✅ SSLContext creado correctamente")
            return sslContext

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error creando SSLContext: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }

    private fun publishMessage(topic: String, payload: String) {
        try {
            if (mqttClient?.isConnected == true) {
                val message = MqttMessage(payload.toByteArray())
                message.qos = 0
                mqttClient?.publish(topic, message)
                Log.d(TAG, "✅ Mensaje publicado en $topic")
            } else {
                Log.e(TAG, "❌ No conectado a MQTT, no se puede publicar")
                throw Exception("No conectado a MQTT")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error publicando mensaje: ${e.message}")
            throw e
        }
    }

    private fun activateLocalMode(reason: String) {
        Log.d(TAG, "🏠 Activando modo de almacenamiento local debido a: $reason")
        useLocalStorage = true

        Handler(Looper.getMainLooper()).post {
            updateNotification("Modo local activo - sin conexión a AWS")
            sendStatusUpdate("Modo sin conexión activo: $reason")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Servicio iniciado")

        // Iniciar actualizaciones de ubicación
        startLocationUpdates()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun publishLocationData(location: Location) {
        // Determinar la ciudad basada en la ubicación
        val city = determinarCiudad(location.latitude, location.longitude)

        // Crear payload JSON
        val payload = JSONObject().apply {
            put("deviceId", clientId)
            put("timestamp", System.currentTimeMillis())
            put("latitude", location.latitude)
            put("longitude", location.longitude)
            put("accuracy", location.accuracy)
            put("altitude", if (location.hasAltitude()) location.altitude else 0.0)
            put("speed", if (location.hasSpeed()) location.speed else 0.0f)
            put("bearing", if (location.hasBearing()) location.bearing else 0.0f)
            put("isMock", location.isFromMockProvider)
            put("isActive", true)
            put("city", city)
        }

        // Decidir si usar almacenamiento local o AWS IoT
        if (useLocalStorage || !isConnected) {
            // Almacenamiento local
            saveLocationLocally(payload)
        } else {
            // Enviar a AWS IoT
            try {
                publishMessage(topic, payload.toString())

                mainHandler.post {
                    sendStatusUpdate("✅ Datos enviados a AWS IoT")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error al publicar: ${e.message}")

                // Guardar localmente en caso de error
                saveLocationLocally(payload)
            }
        }
    }

    private fun saveLocationLocally(payload: JSONObject) {
        Thread {
            try {
                // Crear directorio si no existe
                val dir = File(getExternalFilesDir(null), "location_data")
                if (!dir.exists()) {
                    dir.mkdirs()
                }

                // Crear nombre de archivo con fecha/hora
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val file = File(dir, "location_${timestamp}.json")

                // Escribir datos en archivo
                FileWriter(file).use { writer ->
                    writer.write(payload.toString())
                }

                Log.d(TAG, "✅ Datos guardados localmente en: ${file.absolutePath}")

                Handler(Looper.getMainLooper()).post {
                    sendStatusUpdate("✅ Datos guardados localmente")
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error al guardar localmente: ${e.message}")

                Handler(Looper.getMainLooper()).post {
                    sendStatusUpdate("❌ Error al guardar: ${e.message}")
                }
            }
        }.start()
    }

    // Resto de tu código (determinarCiudad, calcularDistancia, etc) permanece igual...

    // Función para determinar la ciudad basada en coordenadas
    private fun determinarCiudad(latitude: Double, longitude: Double): String {
        // Definir regiones de ciudades (versión simplificada)
        val regiones = listOf(
            Triple("Bello", 6.333176, -75.573553),       // Centro de Bello
            Triple("Medellín", 6.244338, -75.573553),    // Centro de Medellín
            Triple("Envigado", 6.175294, -75.591888),     // Centro de Envigado
            Triple("Sabaneta", 6.151537, -75.615293),
            Triple("Itagüí", 6.184409, -75.599051),
        )

        // Encontrar la ciudad más cercana
        var ciudadMasCercana = "Área Metropolitana"
        var distanciaMinima = Double.MAX_VALUE

        for (region in regiones) {
            val distancia = calcularDistancia(
                latitude, longitude,
                region.second, region.third
            )

            if (distancia < distanciaMinima) {
                distanciaMinima = distancia
                ciudadMasCercana = region.first
            }
        }

        return ciudadMasCercana
    }

    // Cálculo de distancia utilizando la fórmula Haversine
    private fun calcularDistancia(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val radioTierra = 6371.0 // Radio de la Tierra en kilómetros

        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)

        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))

        return radioTierra * c // Distancia en kilómetros
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    handleNewLocation(location)
                }
            }
        }
    }

    private fun handleNewLocation(location: Location) {
        val lat = location.latitude
        val lng = location.longitude
        val acc = location.accuracy
        val city = determinarCiudad(lat, lng)

        Log.d(TAG, "📍 UBICACIÓN REAL RECIBIDA: $lat, $lng (±$acc m) en $city")

        // Actualizar notificación
        updateNotification("Ubicación: $lat, $lng en $city")

        // Enviar a UI mediante broadcast
        sendLocationBroadcast(location)

        // Publicar en MQTT o guardar localmente
        publishLocationData(location)
    }

    private fun startLocationUpdates() {
        try {
            Log.d(TAG, "🔵 Iniciando solicitud de ubicación real...")

            if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "❌ ERROR: Sin permisos para solicitar ubicación")
                return
            }

            // Primero intenta obtener la última ubicación conocida
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    Log.d(TAG, "✅ Última ubicación conocida encontrada: ${location.latitude}, ${location.longitude}")
                    handleNewLocation(location)
                } else {
                    Log.w(TAG, "⚠️ No hay última ubicación conocida disponible")
                }
            }

            // Configurar solicitud de ubicaciones REALES
            val locationRequest = LocationRequest.Builder(UPDATE_INTERVAL)
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setMinUpdateDistanceMeters(SMALLEST_DISPLACEMENT)
                .setMinUpdateIntervalMillis(FASTEST_INTERVAL)
                .setWaitForAccurateLocation(true)
                .build()

            // Iniciar actualizaciones
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )

            Log.d(TAG, "✅ Solicitud de ubicación real configurada correctamente")

        } catch (e: SecurityException) {
            Log.e(TAG, "❌ Error de permisos: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error general al solicitar ubicación: ${e.message}")
        }
    }

    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        Log.d(TAG, "Actualizaciones de ubicación detenidas")
    }

    private fun sendLocationBroadcast(location: Location) {
        val intent = Intent("LOCATION_UPDATE")
        intent.putExtra("latitude", location.latitude)
        intent.putExtra("longitude", location.longitude)
        intent.putExtra("accuracy", location.accuracy)
        intent.putExtra("city", determinarCiudad(location.latitude, location.longitude))

        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun sendStatusUpdate(status: String) {
        val intent = Intent("LOCATION_UPDATE")
        intent.putExtra("status", status)

        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    override fun onDestroy() {
        Log.d(TAG, "🔴 Servicio destruido - deteniendo todas las operaciones")

        // Detener actualizaciones de ubicación
        try {
            stopLocationUpdates()
        } catch (e: Exception) {
            Log.e(TAG, "Error al detener actualizaciones de ubicación: ${e.message}")
        }

        // Cancelar todos los runnables programados
        for (runnable in locationUpdateRunnable) {
            mainHandler.removeCallbacks(runnable)
        }
        locationUpdateRunnable.clear()

        // Desconectar MQTT
        try {
            if (mqttClient?.isConnected == true) {
                // Publicar mensaje de desconexión
                val lastMessage = JSONObject().apply {
                    put("deviceId", clientId)
                    put("status", "disconnected_graceful")
                    put("timestamp", System.currentTimeMillis())
                }

                val message = MqttMessage(lastMessage.toString().toByteArray())
                message.qos = 0
                mqttClient?.publish("$topic/status", message)

                // Pequeña pausa para garantizar que el mensaje se envíe
                Thread.sleep(500)

                mqttClient?.disconnect()
                mqttClient?.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error al desconectar MQTT: ${e.message}")
        }

        // Detener el servicio en primer plano
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            stopForeground(true)
        }

        // Enviar actualización de estado
        sendStatusUpdate("Servicio detenido")

        // Llamar al método onDestroy de la superclase
        super.onDestroy()
    }

    // Métodos para notificación
    private fun createNotification(text: String): Notification {
        createNotificationChannel()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Rastreador de Ubicación Real")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Location Service Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Canal para el servicio de seguimiento de ubicación"
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
