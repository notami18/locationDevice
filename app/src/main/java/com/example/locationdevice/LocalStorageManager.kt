package com.example.locationdevice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.collections.mutableListOf

/**
 * Gestor robusto de almacenamiento local con buffer, cola y sistema de reintento
 */
class LocalStorageManager(private val context: Context) {
    
    private val TAG = "LocalStorageManager"
    
    // Cola en memoria para datos pendientes
    private val pendingLocations = ConcurrentLinkedQueue<JSONObject>()
    
    // Archivo único para datos no enviados
    private val pendingDataFile = File(context.filesDir, "pending_locations.json")
    
    // Archivo de respaldo/historial
    private val historyDataFile = File(context.filesDir, "location_history.json")
    
    // Configuración
    private val MAX_PENDING_ITEMS = 1000 // Máximo items en cola
    private val BATCH_SIZE = 50 // Enviar en lotes de máximo 50
    private val RETRY_INTERVAL = 30000L // 30 segundos entre reintentos
    
    // Job para procesamiento en background
    private var processingJob: Job? = null
    private val processingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    init {
        // Cargar datos pendientes del archivo al inicializar
        loadPendingDataFromFile()
        
        // Iniciar procesamiento en background
        startBackgroundProcessing()
    }
    
    /**
     * Guarda una ubicación, siempre primero localmente como backup
     */
    fun saveLocation(locationData: JSONObject, mqttAvailable: Boolean = false) {
        try {
            // SIEMPRE guardar en cola local primero (backup)
            addToPendingQueue(locationData)
            
            // Si MQTT está disponible, intentar envío inmediato
            if (mqttAvailable) {
                Log.d(TAG, "📤 MQTT disponible, enviando inmediatamente")
                // Se enviará por el sistema normal de MQTT
            } else {
                Log.d(TAG, "📥 MQTT no disponible, datos guardados en cola local")
                // Persistir inmediatamente al archivo
                savePendingDataToFile()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error guardando ubicación: ${e.message}", e)
        }
    }
    
    /**
     * Añade datos a la cola en memoria
     */
    private fun addToPendingQueue(locationData: JSONObject) {
        // Añadir timestamp de guardado local para trazabilidad
        locationData.put("localSavedAt", System.currentTimeMillis())
        locationData.put("attemptCount", 0)
        
        pendingLocations.offer(locationData)
        
        // Mantener límite de cola
        while (pendingLocations.size > MAX_PENDING_ITEMS) {
            val removedItem = pendingLocations.poll()
            Log.w(TAG, "⚠️ Cola llena, eliminando item más antiguo: ${removedItem?.optLong("timestamp")}")
        }
        
        Log.d(TAG, "📝 Datos añadidos a cola. Tamaño actual: ${pendingLocations.size}")
    }
    
    /**
     * Persiste la cola en memoria al archivo
     */
    private fun savePendingDataToFile() {
        processingScope.launch {
            try {
                val jsonArray = JSONArray()
                
                // Convertir cola a JSON Array
                pendingLocations.forEach { item ->
                    jsonArray.put(item)
                }
                
                // Escribir al archivo de manera atómica
                val tempFile = File(context.filesDir, "pending_locations.tmp")
                FileWriter(tempFile).use { writer ->
                    writer.write(jsonArray.toString(2)) // Pretty print
                }
                
                // Mover archivo temporal al definitivo (operación atómica)
                if (tempFile.renameTo(pendingDataFile)) {
                    Log.d(TAG, "💾 Datos persistidos: ${pendingLocations.size} items")
                } else {
                    Log.e(TAG, "❌ Error moviendo archivo temporal")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error persistiendo datos: ${e.message}", e)
            }
        }
    }
    
    /**
     * Carga datos pendientes del archivo al inicializar
     */
    private fun loadPendingDataFromFile() {
        try {
            if (!pendingDataFile.exists()) {
                Log.d(TAG, "📂 No hay archivo de datos pendientes")
                return
            }
            
            val jsonContent = pendingDataFile.readText()
            val jsonArray = JSONArray(jsonContent)
            
            pendingLocations.clear()
            
            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                pendingLocations.offer(item)
            }
            
            Log.d(TAG, "📥 Cargados ${pendingLocations.size} items pendientes del archivo")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error cargando datos pendientes: ${e.message}", e)
            // Si hay error, respaldar archivo corrupto
            if (pendingDataFile.exists()) {
                val backupFile = File(context.filesDir, "pending_locations_backup_${System.currentTimeMillis()}.json")
                pendingDataFile.copyTo(backupFile, overwrite = true)
                pendingDataFile.delete()
                Log.w(TAG, "⚠️ Archivo corrupto respaldado como: ${backupFile.name}")
            }
        }
    }
    
    /**
     * Inicia procesamiento en background para reintento de envíos
     */
    private fun startBackgroundProcessing() {
        processingJob?.cancel()
        
        processingJob = processingScope.launch {
            while (isActive) {
                try {
                    if (pendingLocations.isNotEmpty()) {
                        Log.d(TAG, "🔄 Procesando ${pendingLocations.size} items pendientes...")
                        
                        // Procesar en lotes
                        val batch = mutableListOf<JSONObject>()
                        repeat(minOf(BATCH_SIZE, pendingLocations.size)) {
                            pendingLocations.poll()?.let { batch.add(it) }
                        }
                        
                        if (batch.isNotEmpty()) {
                            processBatch(batch)
                        }
                    }
                    
                    // Esperar antes del próximo ciclo
                    delay(RETRY_INTERVAL)
                    
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error en procesamiento background: ${e.message}", e)
                    delay(RETRY_INTERVAL)
                }
            }
        }
        
        Log.d(TAG, "🚀 Procesamiento en background iniciado")
    }
    
    /**
     * Procesa un lote de ubicaciones pendientes
     */
    private suspend fun processBatch(batch: List<JSONObject>) {
        for (item in batch) {
            try {
                val attemptCount = item.optInt("attemptCount", 0)
                
                // Incrementar contador de intentos
                item.put("attemptCount", attemptCount + 1)
                item.put("lastAttempt", System.currentTimeMillis())
                
                // Simular envío (aquí iría la lógica real de MQTT)
                val success = attemptToSendLocation(item)
                
                if (success) {
                    Log.d(TAG, "✅ Ubicación enviada exitosamente después de ${attemptCount + 1} intentos")
                    
                    // Guardar en historial
                    saveToHistory(item)
                    
                } else {
                    // Si falló y no ha superado el límite de intentos, devolver a cola
                    if (attemptCount < 5) { // Máximo 5 intentos
                        pendingLocations.offer(item)
                        Log.w(TAG, "⚠️ Reintento ${attemptCount + 1}/5 fallido, reencolar")
                    } else {
                        Log.e(TAG, "❌ Ubicación descartada después de 5 intentos fallidos")
                        saveToHistory(item, failed = true)
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error procesando item: ${e.message}", e)
                // Devolver a cola para reintento
                pendingLocations.offer(item)
            }
        }
        
        // Actualizar archivo con datos restantes
        savePendingDataToFile()
    }
    
    /**
     * Intenta enviar una ubicación (aquí iría la integración con MQTT)
     */
    private suspend fun attemptToSendLocation(locationData: JSONObject): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // TODO: Aquí iría la llamada real a MQTT
                // Por ahora, simulamos éxito basado en conectividad
                
                // Simular envío con probabilidad de éxito
                val random = Random()
                val success = random.nextBoolean() // 50% probabilidad para prueba
                
                if (success) {
                    Log.d(TAG, "📤 Ubicación enviada: ${locationData.optDouble("latitude")}, ${locationData.optDouble("longitude")}")
                } else {
                    Log.w(TAG, "📤 Fallo simulado en envío")
                }
                
                success
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error en intento de envío: ${e.message}")
                false
            }
        }
    }
    
    /**
     * Guarda ubicación en historial
     */
    private fun saveToHistory(locationData: JSONObject, failed: Boolean = false) {
        processingScope.launch {
            try {
                locationData.put("sentAt", System.currentTimeMillis())
                locationData.put("sendStatus", if (failed) "failed" else "success")
                
                // Leer historial actual
                val historyArray = if (historyDataFile.exists()) {
                    JSONArray(historyDataFile.readText())
                } else {
                    JSONArray()
                }
                
                historyArray.put(locationData)
                
                // Mantener solo últimos 500 registros para no saturar
                while (historyArray.length() > 500) {
                    historyArray.remove(0)
                }
                
                // Escribir historial actualizado
                FileWriter(historyDataFile).use { writer ->
                    writer.write(historyArray.toString(2))
                }
                
                Log.d(TAG, "📚 Guardado en historial: ${if (failed) "FALLIDO" else "EXITOSO"}")
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error guardando historial: ${e.message}", e)
            }
        }
    }
    
    /**
     * Obtiene estadísticas del almacenamiento local
     */
    fun getStorageStats(): StorageStats {
        return StorageStats(
            pendingCount = pendingLocations.size,
            pendingFileExists = pendingDataFile.exists(),
            historyFileExists = historyDataFile.exists(),
            pendingFileSize = if (pendingDataFile.exists()) pendingDataFile.length() else 0L,
            historyFileSize = if (historyDataFile.exists()) historyDataFile.length() else 0L
        )
    }
    
    /**
     * Limpia datos de almacenamiento local
     */
    fun clearPendingData() {
        pendingLocations.clear()
        if (pendingDataFile.exists()) {
            pendingDataFile.delete()
        }
        Log.d(TAG, "🧹 Datos pendientes limpiados")
    }
    
    /**
     * Marca que MQTT está disponible para procesar cola pendiente
     */
    fun onMqttConnected() {
        Log.d(TAG, "🔗 MQTT conectado, procesando cola pendiente...")
        
        processingScope.launch {
            // Procesar inmediatamente al conectar
            if (pendingLocations.isNotEmpty()) {
                val batch = mutableListOf<JSONObject>()
                repeat(minOf(BATCH_SIZE, pendingLocations.size)) {
                    pendingLocations.poll()?.let { batch.add(it) }
                }
                
                if (batch.isNotEmpty()) {
                    processBatch(batch)
                }
            }
        }
    }
    
    /**
     * Para limpieza al destruir el servicio
     */
    fun cleanup() {
        processingJob?.cancel()
        savePendingDataToFile() // Guardar datos pendientes antes de cerrar
        Log.d(TAG, "🧹 LocalStorageManager limpiado")
    }
    
    data class StorageStats(
        val pendingCount: Int,
        val pendingFileExists: Boolean,
        val historyFileExists: Boolean,
        val pendingFileSize: Long,
        val historyFileSize: Long
    )
}