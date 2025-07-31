package com.example.locationdevice

import android.content.Context
import android.util.Log
import com.amplifyframework.auth.AuthUser
import com.amplifyframework.core.Amplify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * Gestiona certificados dinámicos únicos por dispositivo usando Cognito Identity
 */
class CertificateManager(private val context: Context) {
    
    private val TAG = "CertificateManager"
    
    data class DeviceCertificateInfo(
        val deviceId: String,
        val thingName: String,
        val clientCertPath: String,
        val privateKeyPath: String,
        val caCertPath: String
    )
    
    /**
     * Obtiene información de certificados para el dispositivo actual
     */
    suspend fun getDeviceCertificateInfo(): DeviceCertificateInfo? {
        return try {
            val cognitoUser = getCurrentCognitoUser()
            val deviceId = cognitoUser?.userId ?: generateFallbackDeviceId()
            
            Log.d(TAG, "Obteniendo certificados para dispositivo: $deviceId")
            
            DeviceCertificateInfo(
                deviceId = deviceId,
                thingName = "device-$deviceId",
                clientCertPath = "certificates/${deviceId}-certificate.pem.crt",
                privateKeyPath = "certificates/${deviceId}-private.pem.key",
                caCertPath = "AmazonRootCA1.pem" // Este se mantiene común
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error obteniendo información de certificados: ${e.message}")
            null
        }
    }
    
    /**
     * Verifica si los certificados del dispositivo existen localmente
     */
    suspend fun areCertificatesAvailable(certInfo: DeviceCertificateInfo): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val certDir = File(context.filesDir, "device_certificates")
                val clientCertFile = File(certDir, "${certInfo.deviceId}-certificate.pem.crt")
                val privateKeyFile = File(certDir, "${certInfo.deviceId}-private.pem.key")
                
                val available = clientCertFile.exists() && privateKeyFile.exists()
                Log.d(TAG, "Certificados disponibles para ${certInfo.deviceId}: $available")
                
                available
            } catch (e: Exception) {
                Log.e(TAG, "Error verificando certificados: ${e.message}")
                false
            }
        }
    }
    
    /**
     * Descarga certificados específicos del dispositivo desde backend
     */
    suspend fun downloadDeviceCertificates(certInfo: DeviceCertificateInfo): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Descargando certificados para dispositivo: ${certInfo.deviceId}")
                
                // TODO: Implementar llamada a AppSync/GraphQL para obtener certificados
                // Por ahora, simulamos el proceso
                
                // 1. Solicitar generación de certificado al backend
                val certificateData = requestCertificateFromBackend(certInfo.deviceId)
                
                if (certificateData != null) {
                    // 2. Guardar certificados localmente
                    saveCertificatesLocally(certInfo, certificateData)
                    Log.d(TAG, "✅ Certificados descargados y guardados para ${certInfo.deviceId}")
                    true
                } else {
                    Log.e(TAG, "❌ No se pudieron obtener certificados del backend")
                    false
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error descargando certificados: ${e.message}")
                false
            }
        }
    }
    
    private suspend fun getCurrentCognitoUser(): AuthUser? {
        return try {
            // TODO: Implementar integración real con Amplify Auth
            // Por ahora retornamos null para usar fallback
            withContext(Dispatchers.Main) {
                if (::Amplify.isInitialized) {
                    Amplify.Auth.getCurrentUser()
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo obtener usuario Cognito: ${e.message}")
            null
        }
    }
    
    private fun generateFallbackDeviceId(): String {
        // Usar identificadores únicos del dispositivo como fallback
        val androidId = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        )
        return "fallback-$androidId"
    }
    
    private suspend fun requestCertificateFromBackend(deviceId: String): CertificateData? {
        // TODO: Implementar llamada real a AppSync/GraphQL
        // Esto sería algo como:
        /*
        val mutation = GenerateDeviceCertificateMutation.builder()
            .deviceId(deviceId)
            .build()
            
        val response = Amplify.API.mutate(mutation)
        return response.data
        */
        
        Log.d(TAG, "🔄 Solicitando certificado al backend para: $deviceId")
        
        // Por ahora, simulamos que tenemos certificados de fallback
        // En producción, esto vendría del backend
        return try {
            CertificateData(
                clientCertificate = "dummy-cert-content",
                privateKey = "dummy-key-content"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error en solicitud al backend: ${e.message}")
            null
        }
    }
    
    private fun saveCertificatesLocally(
        certInfo: DeviceCertificateInfo, 
        certificateData: CertificateData
    ) {
        try {
            val certDir = File(context.filesDir, "device_certificates")
            if (!certDir.exists()) {
                certDir.mkdirs()
            }
            
            // Guardar certificado cliente
            val clientCertFile = File(certDir, "${certInfo.deviceId}-certificate.pem.crt")
            FileOutputStream(clientCertFile).use { fos ->
                fos.write(certificateData.clientCertificate.toByteArray())
            }
            
            // Guardar clave privada
            val privateKeyFile = File(certDir, "${certInfo.deviceId}-private.pem.key")
            FileOutputStream(privateKeyFile).use { fos ->
                fos.write(certificateData.privateKey.toByteArray())
            }
            
            Log.d(TAG, "✅ Certificados guardados en: ${certDir.absolutePath}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error guardando certificados: ${e.message}")
            throw e
        }
    }
    
    /**
     * Carga certificados desde almacenamiento local
     */
    fun loadDeviceCertificatesFromStorage(certInfo: DeviceCertificateInfo): CertificateFiles? {
        return try {
            val certDir = File(context.filesDir, "device_certificates")
            
            val clientCertFile = File(certDir, "${certInfo.deviceId}-certificate.pem.crt")
            val privateKeyFile = File(certDir, "${certInfo.deviceId}-private.pem.key")
            
            // CA cert sigue siendo de assets
            val caCertStream = context.assets.open(certInfo.caCertPath)
            
            if (clientCertFile.exists() && privateKeyFile.exists()) {
                CertificateFiles(
                    clientCertStream = clientCertFile.inputStream(),
                    privateKeyStream = privateKeyFile.inputStream(),
                    caCertStream = caCertStream
                )
            } else {
                Log.w(TAG, "Archivos de certificado no encontrados para ${certInfo.deviceId}")
                null
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error cargando certificados: ${e.message}")
            null
        }
    }
    
    data class CertificateData(
        val clientCertificate: String,
        val privateKey: String
    )
    
    data class CertificateFiles(
        val clientCertStream: java.io.InputStream,
        val privateKeyStream: java.io.InputStream,
        val caCertStream: java.io.InputStream
    )
}