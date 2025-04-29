package com.example.locationdevice

import android.util.Log
import java.io.InputStream
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

object SSLUtils_ {
    private const val TAG = "SSLUtils"

    /**
     * Carga un certificado X.509 desde un InputStream
     */
    fun loadCertificate(inputStream: InputStream): X509Certificate {
        val certificateFactory = CertificateFactory.getInstance("X.509")
        return certificateFactory.generateCertificate(inputStream) as X509Certificate
    }

    /**
     * Carga una clave privada desde un archivo PEM
     */
    fun loadPrivateKeyFromPEM(inputStream: InputStream): PrivateKey {
        val privateKeyPEM = inputStream.bufferedReader().use { it.readText() }
            .replace("-----BEGIN RSA PRIVATE KEY-----", "")
            .replace("-----END RSA PRIVATE KEY-----", "")
            .replace("\\s+".toRegex(), "")

        Log.d(TAG, "Procesando clave privada (longitud Base64: ${privateKeyPEM.length})")

        try {
            // Decodificar Base64
            val keyBytes = android.util.Base64.decode(privateKeyPEM, android.util.Base64.DEFAULT)

            // Usar el KeyFactory estándar de Android
            val keyFactory = KeyFactory.getInstance("RSA")

            try {
                // Intentar como clave PKCS8
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

    /**
     * Crea un SSLContext con los certificados proporcionados
     */
    fun createSSLContext(
        clientCert: X509Certificate,
        privateKey: PrivateKey,
        caCert: X509Certificate
    ): SSLContext {
        // Crear KeyStore
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(null)

        // Agregar certificado CA
        keyStore.setCertificateEntry("ca-certificate", caCert)

        // Agregar certificado y clave privada del cliente
        keyStore.setKeyEntry("client-key", privateKey, null, arrayOf(clientCert))

        // Configurar TrustManager
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(keyStore)

        // Configurar KeyManager
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, null)

        // Crear y configurar SSLContext
        val sslContext = SSLContext.getInstance("TLSv1.2")
        sslContext.init(kmf.keyManagers, tmf.trustManagers, null)

        return sslContext
    }
}