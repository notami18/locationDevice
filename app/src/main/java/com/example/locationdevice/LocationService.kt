package com.example.locationdevice

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
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
import java.io.FileWriter
import java.io.InputStream
import java.net.InetAddress
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
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
    private var useLocalStorage = false

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
            startForeground(NOTIFICATION_ID, createNotification("Iniciando servicio..."))

            // Listar archivos en assets para debug
            listAssetFiles()

            // Primero probar conectividad básica
            checkConnectivity()

            // Generar ubicación simulada
            generateMockLocation()

            // Iniciar actualizaciones de ubicación reales
            startLocationUpdates()

            Log.d(TAG, "🟢 Servicio iniciado completamente")
        } catch (e: Exception) {
            Log.e(TAG, "❌ ERROR FATAL en onCreate: ${e.message}", e)
            e.printStackTrace()
        }
    }

    private fun checkConnectivity() {
        Thread {
            try {
                Log.d(TAG, "🔍 Verificando conectividad con AWS IoT endpoint...")

                // Verificar si estamos en emulador
                if (isEmulator()) {
                    Log.w(TAG, "⚠️ Ejecutando en emulador - podría haber restricciones de red")
                }

                // Intentar hacer ping al endpoint
                try {
                    val address = InetAddress.getByName(iotEndpoint)
                    val reachable = address.isReachable(3000)
                    Log.d(TAG, "Ping a $iotEndpoint: ${if (reachable) "EXITOSO" else "FALLIDO"}")

                    if (!reachable) {
                        Log.w(TAG, "⚠️ No se puede alcanzar el endpoint. Problema de conectividad.")
                        activateLocalMode("No se puede alcanzar AWS IoT")
                        return@Thread
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error al intentar hacer ping: ${e.message}")
                }

                // Intentar conectar a AWS IoT
                connectToAwsIot()

            } catch (e: Exception) {
                Log.e(TAG, "Error verificando conectividad: ${e.message}")
                e.printStackTrace()
            }
        }.start()
    }

    private fun activateLocalMode(reason: String) {
        Log.d(TAG, "🏠 Activando modo de almacenamiento local debido a: $reason")
        useLocalStorage = true

        Handler(Looper.getMainLooper()).post {
            updateNotification("Modo local activo - sin conexión a AWS")
            sendStatusUpdate("Modo sin conexión activo: $reason")
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Servicio iniciado")

        // Iniciar actualizaciones de ubicación
        startLocationUpdates()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun connectToAwsIot() {
        // Si ya está conectándose, no iniciar otra conexión
        if (isConnecting.getAndSet(true)) {
            Log.d(TAG, "⚠️ Conexión a AWS IoT ya en progreso, ignorando solicitud")
            return
        }

        Thread {
            try {
                Log.d(TAG, "🔄 Iniciando conexión a AWS IoT Core...")

                // Desconectar cliente existente si hay uno
                try {
                    if (mqttClient?.isConnected == true) {
                        mqttClient?.disconnect()
                        Log.d(TAG, "Cliente MQTT previo desconectado")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error al desconectar cliente existente: ${e.message}")
                }

                // Cargar certificados desde assets
                val caCertInputStream = applicationContext.assets.open(caCertFileName)
                val clientCertInputStream = applicationContext.assets.open(clientCertFileName)
                val privateKeyInputStream = applicationContext.assets.open(privateKeyFileName)

                Log.d(TAG, "✅ Certificados cargados correctamente")

                // Procesar certificados
                val caCert = loadCertificate(caCertInputStream)
                val clientCert = loadCertificate(clientCertInputStream)
                val privateKey = loadPrivateKeyFromPEM(privateKeyInputStream)

                Log.d(TAG, "✅ Certificados procesados correctamente")

                // Crear SSLContext
                val sslContext = createSSLContext(clientCert, privateKey, caCert)

                // Configurar opciones MQTT
                val options = MqttConnectOptions().apply {
                    isCleanSession = true
                    connectionTimeout = 60
                    keepAliveInterval = 60  // Aumentado de 30 a 60 segundos
                    socketFactory = sslContext.socketFactory

                    // Configurar Last Will Testament para saber cuando se desconecta
                    setWill(
                        "$topic/status",
                        """{"deviceId":"$clientId","status":"disconnected"}""".toByteArray(),
                        0,
                        false
                    )
                }

                // Preparar conexión a AWS IoT
                val awsIotBroker = "ssl://$iotEndpoint:8883"
                Log.d(TAG, "Conectando a AWS IoT: $awsIotBroker")

                // Crear cliente MQTT
                mqttClient = MqttClient(awsIotBroker, clientId, MemoryPersistence())

                // Configurar callbacks
                mqttClient?.setCallback(object : MqttCallback {
                    override fun connectionLost(cause: Throwable?) {
                        Log.e(TAG, "Conexión AWS IoT perdida: ${cause?.message}")
                        isConnecting.set(false)

                        // Programar un intento de reconexión con retraso exponencial
                        val delay = 5000L  // 5 segundos
                        Handler(Looper.getMainLooper()).postDelayed({
                            if (!mqttClient?.isConnected!! == true) {
                                Log.d(TAG, "Intentando reconexión automática a AWS IoT...")
                                connectToAwsIot()
                            }
                        }, delay)
                    }

                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        // No esperamos recibir mensajes
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) {
                        Log.d(TAG, "✅ Mensaje entregado a AWS IoT")
                    }
                })

                // Conectar con manejo detallado de errores
                try {
                    Log.d(TAG, "Intentando conexión MQTT explícita...")
                    mqttClient?.connect(options)
                    Log.d(TAG, "✅ Conectado a AWS IoT correctamente")

                    // Desactivar modo local
                    useLocalStorage = false
                } catch (e: MqttException) {
                    // Log detallado del error MQTT
                    Log.e(TAG, "❌ MqttException: ${e.message}, Código: ${e.reasonCode}, Causa: ${e.cause?.message}")
                    when (e.reasonCode) {
                        MqttException.REASON_CODE_CLIENT_EXCEPTION.toInt() -> {
                            Log.e(TAG, "Error de cliente MQTT (posiblemente problemas de red)")
                            activateLocalMode("Problemas de red")
                        }
                        MqttException.REASON_CODE_INVALID_PROTOCOL_VERSION.toInt().toInt() -> Log.e(TAG, "Versión de protocolo MQTT inválida")
                        MqttException.REASON_CODE_INVALID_CLIENT_ID.toInt() -> Log.e(TAG, "ID de cliente inválido")
                        MqttException.REASON_CODE_BROKER_UNAVAILABLE.toInt() -> {
                            Log.e(TAG, "Broker no disponible (endpoint incorrecto o problemas de red)")
                            activateLocalMode("Broker no disponible")
                        }
                        MqttException.REASON_CODE_FAILED_AUTHENTICATION.toInt().toInt() -> Log.e(TAG, "Autenticación fallida (certificados incorrectos)")
                        MqttException.REASON_CODE_NOT_AUTHORIZED.toInt().toInt() -> Log.e(TAG, "No autorizado (falta política en AWS IoT)")
                        MqttException.REASON_CODE_SUBSCRIBE_FAILED.toInt().toInt() -> Log.e(TAG, "Suscripción fallida")
                        MqttException.REASON_CODE_CLIENT_TIMEOUT.toInt() -> {
                            Log.e(TAG, "Timeout de cliente")
                            activateLocalMode("Timeout de conexión")
                        }
                        MqttException.REASON_CODE_NO_MESSAGE_IDS_AVAILABLE.toInt() -> Log.e(TAG, "No hay IDs de mensaje disponibles")
                        MqttException.REASON_CODE_CONNECTION_LOST.toInt() -> {
                            Log.e(TAG, "Conexión perdida")
                            activateLocalMode("Conexión perdida")
                        }
                        else -> {
                            Log.e(TAG, "Otro error MQTT: ${e.reasonCode}")
                            activateLocalMode("Error MQTT ${e.reasonCode}")
                        }
                    }
                    e.printStackTrace()
                    throw e
                }

                Log.d(TAG, "✅ Conectado exitosamente a AWS IoT")

                // Actualizar UI
                Handler(Looper.getMainLooper()).post {
                    updateNotification("Conectado a AWS IoT")
                    sendStatusUpdate("Enviando datos a MongoDB vía IoT")
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error AWS IoT: ${e.javaClass.simpleName} - ${e.message}")
                e.printStackTrace()

                // Actualizar UI con el error
                Handler(Looper.getMainLooper()).post {
                    updateNotification("Error de conexión IoT: ${e.javaClass.simpleName}")
                    sendStatusUpdate("Error: ${e.message}")
                }

                // En caso de error, activar modo local
                activateLocalMode("Error de conexión: ${e.javaClass.simpleName}")
            } finally {
                // Siempre marcar que ya no está conectándose
                isConnecting.set(false)
            }
        }.start()
    }

    private fun publishLocationData(location: Location) {
        // Determinar la ciudad desde los extras, o usar "Desconocido" como valor por defecto
        val city = location.extras?.getString("city") ?: "Desconocido"

        // Crear payload JSON
        val payload = JSONObject().apply {
            put("deviceId", clientId)
            put("timestamp", System.currentTimeMillis())
            put("latitude", location.latitude)
            put("longitude", location.longitude)
            put("accuracy", location.accuracy)
            put("isMock", location.isFromMockProvider)
            put("city", city) // Añadir la ciudad
        }

        // Decidir si usar almacenamiento local o AWS IoT
        if (useLocalStorage || mqttClient?.isConnected != true) {
            // Almacenamiento local
            saveLocationLocally(payload)
        } else {
            // Enviar a AWS IoT
            publishToAwsIot(payload)
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

    private fun publishToAwsIot(payload: JSONObject) {
        Thread {
            try {
                val message = MqttMessage(payload.toString().toByteArray())
                message.qos = 0

                Log.d(TAG, "📤 Publicando en $topic: $payload")
                mqttClient?.publish(topic, message)

                Handler(Looper.getMainLooper()).post {
                    sendStatusUpdate("✅ Datos enviados a AWS IoT")
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error al publicar: ${e.javaClass.simpleName} - ${e.message}")
                e.printStackTrace()

                // Si hay error, activar modo local
                activateLocalMode("Error de publicación: ${e.message}")

                // Y guardar el dato actual localmente
                saveLocationLocally(payload)
            }
        }.start()
    }

    // Funciones auxiliares para SSL
    private fun loadCertificate(inputStream: InputStream): X509Certificate {
        val certificateFactory = CertificateFactory.getInstance("X.509")
        return certificateFactory.generateCertificate(inputStream) as X509Certificate
    }

    private fun loadPrivateKeyFromPEM(inputStream: InputStream): PrivateKey {
        val privateKeyPEM = inputStream.bufferedReader().use { it.readText() }
            .replace("-----BEGIN RSA PRIVATE KEY-----", "")
            .replace("-----END RSA PRIVATE KEY-----", "")
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\s+".toRegex(), "")

        Log.d(TAG, "Procesando clave privada (longitud Base64: ${privateKeyPEM.length})")

        try {
            // Decodificar Base64
            val keyBytes = android.util.Base64.decode(privateKeyPEM, android.util.Base64.DEFAULT)

            // Intentar diferentes formatos de clave
            try {
                // Intentar como clave PKCS8
                Log.d(TAG, "Intentando formato PKCS8...")
                val keyFactory = KeyFactory.getInstance("RSA")
                val keySpec = PKCS8EncodedKeySpec(keyBytes)
                return keyFactory.generatePrivate(keySpec)
            } catch (e: Exception) {
                Log.e(TAG, "Error con formato PKCS8: ${e.message}")
                throw e
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error procesando clave privada: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }

    private fun createSSLContext(
        clientCert: X509Certificate,
        privateKey: PrivateKey,
        caCert: X509Certificate
    ): SSLContext {
        try {
            // Crear KeyStore
            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
            keyStore.load(null)

            // Agregar certificado CA
            keyStore.setCertificateEntry("ca-certificate", caCert)

            // Agregar certificado y clave privada del cliente
            keyStore.setKeyEntry("client-key", privateKey, null, arrayOf(clientCert))
            Log.d(TAG, "KeyStore configurado correctamente")

            // Configurar TrustManager
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(keyStore)

            // Configurar KeyManager
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(keyStore, null)

            // Crear y configurar SSLContext
            val sslContext = SSLContext.getInstance("TLSv1.2")
            sslContext.init(kmf.keyManagers, tmf.trustManagers, null)
            Log.d(TAG, "SSLContext creado correctamente")

            return sslContext
        } catch (e: Exception) {
            Log.e(TAG, "Error creando SSLContext: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }

    private fun generateMockLocation() {
        Log.d(TAG, "Generando ubicación simulada ya que no recibimos actualizaciones reales")

        // Coordenadas de ejemplo (Ciudad de México)
        val mockLocation = Location("MockProvider")
        mockLocation.latitude = 19.432608
        mockLocation.longitude = -99.133209
        mockLocation.accuracy = 10.0f
        mockLocation.time = System.currentTimeMillis()

        // Procesar esta ubicación simulada
        handleNewLocation(mockLocation)

        // Crear runnable para próxima actualización
        val mockGenRunnable = Runnable {
            generateMockLocation()
        }

        // Guardar referencia
        locationUpdateRunnable.add(mockGenRunnable)

        // Programar otra actualización simulada en 10 segundos
        mainHandler.postDelayed(mockGenRunnable, 10000)
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

        Log.d(TAG, "📍 UBICACIÓN RECIBIDA: $lat, $lng (±$acc m)")

        // Actualizar notificación
        updateNotification("Ubicación: $lat, $lng")

        // Enviar a UI mediante broadcast
        sendLocationBroadcast(location)

        // Publicar en MQTT o guardar localmente
        publishLocationData(location)
    }

    private fun startLocationUpdates() {
        try {
            Log.d(TAG, "🔵 Iniciando solicitud de ubicación...")

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

                    // Como estamos en simulador, generamos una ubicación simulada
                    if (isEmulator()) {
                        Log.d(TAG, "📱 Ejecutando en emulador - generando ubicación simulada")
                        startMockLocationUpdates()
                    }
                }
            }

            // Configurar solicitud de ubicaciones reales
            val locationRequest = LocationRequest.Builder(5000) // 5 segundos
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setMinUpdateDistanceMeters(5f) // 5 metros
                .setWaitForAccurateLocation(false)
                .build()

            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )

            Log.d(TAG, "✅ Solicitud de ubicación configurada correctamente")

        } catch (e: SecurityException) {
            Log.e(TAG, "❌ Error de permisos: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error general al solicitar ubicación: ${e.message}")
        }
    }

    private fun isEmulator(): Boolean {
        return (Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for")
                || Build.MANUFACTURER.contains("Genymotion")
                || Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
    }

    private fun startMockLocationUpdates() {
        Log.d(TAG, "🔵 Iniciando simulación de ubicaciones en el Área Metropolitana de Medellín")

        // Definir coordenadas para las tres ciudades
        val locations = arrayOf(
            // Bello - varios puntos
            Pair(6.333333, -75.558333),  // Centro de Bello
            Pair(6.339722, -75.554722),  // Barrio Niquía
            Pair(6.320833, -75.570278),  // Barrio Cabañas
            Pair(6.346111, -75.542778),  // Barrio La Cumbre

            // Medellín - varios puntos
            Pair(6.244747, -75.573101),  // Centro de Medellín
            Pair(6.210129, -75.572380),  // El Poblado
            Pair(6.256773, -75.589861),  // Laureles
            Pair(6.231144, -75.586700),  // Estadio

            // Envigado - varios puntos
            Pair(6.175742, -75.591370),  // Centro de Envigado
            Pair(6.168900, -75.574300),  // Zona norte de Envigado
            Pair(6.184722, -75.585833),  // Barrio La Sebastiana
            Pair(6.163889, -75.594444)   // Barrio La Mina
        )

        // Programar actualizaciones periódicas
        val mockRunnable = object : Runnable {
            private var currentLocationIndex = (Math.random() * locations.size).toInt()
            private var count = 0

            override fun run() {
                // Elegir una ubicación aleatoria
                currentLocationIndex = (Math.random() * locations.size).toInt()
                val selectedLocation = locations[currentLocationIndex]

                // Añadir pequeña variación para simular movimiento
                val randomLat = selectedLocation.first + 0.001 * (Math.random() - 0.5)
                val randomLng = selectedLocation.second + 0.001 * (Math.random() - 0.5)

                // Determinar la ciudad basado en el índice
                val city = when(currentLocationIndex) {
                    in 0..3 -> "Bello"
                    in 4..7 -> "Medellín"
                    else -> "Envigado"
                }

                // Enviar ubicación y metadatos adicionales
                sendMockLocation(randomLat, randomLng, city)

                count++
                if (count < 1000) { // Limitar a 1000 actualizaciones
                    mainHandler.postDelayed(this, 10000) // Cada 10 segundos
                }
            }
        }

        // Guardar referencia al runnable
        locationUpdateRunnable.add(mockRunnable)

        // Iniciar el runnable de inmediato y luego periódicamente
        mockRunnable.run()
    }

    private fun sendMockLocation(latitude: Double, longitude: Double, city: String = "") {
        val mockLocation = Location("mock")
        mockLocation.latitude = latitude
        mockLocation.longitude = longitude
        mockLocation.accuracy = 5f + (Math.random() * 10).toFloat() // Precisión entre 5 y 15 metros
        mockLocation.time = System.currentTimeMillis()

        // Añadir extras para transportar metadatos adicionales
        val bundle = Bundle()
        bundle.putString("city", city)
        mockLocation.extras = bundle

        Log.d(TAG, "📍 Ubicación simulada generada en $city: $latitude, $longitude")

        // Procesar la ubicación simulada como si fuera real
        handleNewLocation(mockLocation)
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
                mqttClient?.disconnect()
            }
            mqttClient?.close()
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
            .setContentTitle("Rastreador de Ubicación")
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