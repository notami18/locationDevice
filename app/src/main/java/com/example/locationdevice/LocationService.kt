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
import kotlinx.coroutines.*

class LocationService : Service() {

    private val TAG = "LocationServiceTAG"

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    // Cliente MQTT y certificados dinámicos
    private var mqttClient: MqttClient? = null
    private lateinit var certificateManager: CertificateManager
    private lateinit var localStorageManager: LocalStorageManager
    private var deviceCertInfo: CertificateManager.DeviceCertificateInfo? = null
    private val clientId: String get() = deviceCertInfo?.thingName ?: "fallback-device"
    private val isConnecting = AtomicBoolean(false)
    private var useLocalStorage = false

    // Configuración MQTT para AWS IoT
    private val iotEndpoint = "d025874830hkteiu1u9o9-ats.iot.us-east-1.amazonaws.com"
    private val topic = "devices/location"

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

            // Inicializar gestores
            certificateManager = CertificateManager(this)
            localStorageManager = LocalStorageManager(this)

            // Inicializar cliente de ubicación
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

            // Configurar callback de ubicación
            setupLocationCallback()

            // Iniciar como servicio en primer plano con notificación
            startForeground(NOTIFICATION_ID, createNotification("Inicializando certificados dinámicos..."))

            // Inicializar certificados antes de conectar
            initializeDeviceCertificates()

            // Iniciar actualizaciones de ubicación reales - IMPORTANTE
            startLocationUpdates()

            Log.d(TAG, "🟢 Servicio iniciado completamente")
        } catch (e: Exception) {
            Log.e(TAG, "❌ ERROR FATAL en onCreate: ${e.message}", e)
            e.printStackTrace()
        }
    }

    /**
     * Inicializa certificados dinámicos específicos del dispositivo
     */
    private fun initializeDeviceCertificates() {
        // Usar corrutina para operaciones asíncronas
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "🔐 Inicializando certificados dinámicos...")
                
                // 1. Obtener información de certificados del dispositivo
                deviceCertInfo = certificateManager.getDeviceCertificateInfo()
                
                if (deviceCertInfo == null) {
                    Log.e(TAG, "❌ No se pudo obtener información de certificados")
                    withContext(Dispatchers.Main) {
                        activateLocalMode("Error obteniendo información de certificados")
                    }
                    return@launch
                }
                
                Log.d(TAG, "📱 Dispositivo ID: ${deviceCertInfo!!.deviceId}")
                Log.d(TAG, "🏷️ Thing Name: ${deviceCertInfo!!.thingName}")
                
                // 2. Verificar si los certificados existen localmente
                val certificatesAvailable = certificateManager.areCertificatesAvailable(deviceCertInfo!!)
                
                if (!certificatesAvailable) {
                    Log.d(TAG, "📥 Certificados no encontrados localmente, descargando...")
                    
                    withContext(Dispatchers.Main) {
                        updateNotification("Descargando certificados únicos...")
                    }
                    
                    // 3. Descargar certificados del backend
                    val downloadSuccess = certificateManager.downloadDeviceCertificates(deviceCertInfo!!)
                    
                    if (!downloadSuccess) {
                        Log.e(TAG, "❌ No se pudieron descargar certificados")
                        withContext(Dispatchers.Main) {
                            activateLocalMode("Error descargando certificados")
                        }
                        return@launch
                    }
                }
                
                Log.d(TAG, "✅ Certificados inicializados correctamente")
                
                // 4. Proceder con conectividad una vez que los certificados están listos
                withContext(Dispatchers.Main) {
                    updateNotification("Certificados listos, verificando conectividad...")
                    checkConnectivity()
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error inicializando certificados: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    activateLocalMode("Error de certificados: ${e.message}")
                }
            }
        }
    }

    private fun checkConnectivity() {
        Thread {
            try {
                Log.d(TAG, "🔍 Verificando conectividad con AWS IoT endpoint...")
                
                // Verificar que tengamos certificados antes de proceder
                if (deviceCertInfo == null) {
                    Log.e(TAG, "❌ No hay información de certificados disponible")
                    activateLocalMode("Sin certificados de dispositivo")
                    return@Thread
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

                // Intentar conectar a AWS IoT con certificados dinámicos
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
                Log.d(TAG, "🔄 Iniciando conexión a AWS IoT Core con certificados dinámicos...")
                
                // Verificar que tengamos información de certificados
                val certInfo = deviceCertInfo
                if (certInfo == null) {
                    Log.e(TAG, "❌ No hay información de certificados disponible")
                    activateLocalMode("Sin información de certificados")
                    return@Thread
                }

                // Desconectar cliente existente si hay uno
                try {
                    if (mqttClient?.isConnected == true) {
                        mqttClient?.disconnect()
                        Log.d(TAG, "Cliente MQTT previo desconectado")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error al desconectar cliente existente: ${e.message}")
                }

                // Cargar certificados dinámicos específicos del dispositivo
                val certificateFiles = certificateManager.loadDeviceCertificatesFromStorage(certInfo)
                
                if (certificateFiles == null) {
                    Log.e(TAG, "❌ No se pudieron cargar certificados del dispositivo")
                    activateLocalMode("Error cargando certificados")
                    return@Thread
                }

                Log.d(TAG, "✅ Certificados dinámicos cargados para dispositivo: ${certInfo.deviceId}")

                // Procesar certificados
                val caCert = loadCertificate(certificateFiles.caCertStream)
                val clientCert = loadCertificate(certificateFiles.clientCertStream)
                val privateKey = loadPrivateKeyFromPEM(certificateFiles.privateKeyStream)

                Log.d(TAG, "✅ Certificados procesados correctamente para Thing: ${certInfo.thingName}")

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

                        // Programar un intento de reconexión con retraso
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
                
                // Notificar al storage manager que MQTT está disponible
                localStorageManager.onMqttConnected()

                // Actualizar UI
                Handler(Looper.getMainLooper()).post {
                    updateNotification("Conectado a AWS IoT - Procesando cola pendiente")
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
            put("isActive", true) // TODO: Por favor tener encuenta en cambiar el estado ya que segun esto se activa el web socket en el back
            put("city", city)
        }

        // SIEMPRE usar el LocalStorageManager robusto
        val mqttAvailable = !useLocalStorage && mqttClient?.isConnected == true
        localStorageManager.saveLocation(payload, mqttAvailable)
        
        // Si MQTT está disponible, intentar envío directo también
        if (mqttAvailable) {
            publishToAwsIot(payload)
        } else {
            Log.d(TAG, "📥 MQTT no disponible, ubicación guardada en sistema robusto de almacenamiento")
            
            Handler(Looper.getMainLooper()).post {
                val stats = localStorageManager.getStorageStats()
                sendStatusUpdate("📥 Datos en cola local: ${stats.pendingCount}")
            }
        }
    }

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

    // Método obsoleto removido - ahora usa LocalStorageManager robusto

    private fun publishToAwsIot(payload: JSONObject) {
        Thread {
            try {
                val message = MqttMessage(payload.toString().toByteArray())
                message.qos = 0

                Log.d(TAG, "📤 Publicando en $topic: ${payload.optDouble("latitude")}, ${payload.optDouble("longitude")}")
                mqttClient?.publish(topic, message)

                Handler(Looper.getMainLooper()).post {
                    val stats = localStorageManager.getStorageStats()
                    sendStatusUpdate("✅ Enviado a AWS IoT (Cola: ${stats.pendingCount})")
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error al publicar: ${e.javaClass.simpleName} - ${e.message}")
                e.printStackTrace()

                // Si hay error, activar modo local
                activateLocalMode("Error de publicación: ${e.message}")
                
                // El LocalStorageManager ya se encarga del almacenamiento automáticamente
                Log.d(TAG, "🔄 Datos asegurados en LocalStorageManager para reintento")
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

            // Configurar solicitud de ubicaciones REALES - CLAVE PARA USAR GPS REAL
            val locationRequest = LocationRequest.Builder(UPDATE_INTERVAL)
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY) // IMPORTANTE: Alta precisión = GPS
                .setMinUpdateDistanceMeters(SMALLEST_DISPLACEMENT)
                .setMinUpdateIntervalMillis(FASTEST_INTERVAL)
                .setWaitForAccurateLocation(true) // Esperar precisión alta
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
                mqttClient?.disconnect()
            }
            mqttClient?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error al desconectar MQTT: ${e.message}")
        }

        // Limpiar LocalStorageManager
        try {
            localStorageManager.cleanup()
        } catch (e: Exception) {
            Log.e(TAG, "Error limpiando LocalStorageManager: ${e.message}")
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