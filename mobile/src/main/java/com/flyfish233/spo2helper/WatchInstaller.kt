package com.flyfish233.spo2helper

import android.content.Context
import android.os.Build
import android.sun.security.x509.AlgorithmId
import android.sun.security.x509.CertificateAlgorithmId
import android.sun.security.x509.CertificateExtensions
import android.sun.security.x509.CertificateIssuerName
import android.sun.security.x509.CertificateSerialNumber
import android.sun.security.x509.CertificateSubjectName
import android.sun.security.x509.CertificateValidity
import android.sun.security.x509.CertificateVersion
import android.sun.security.x509.CertificateX509Key
import android.sun.security.x509.KeyIdentifier
import android.sun.security.x509.SubjectKeyIdentifierExtension
import android.sun.security.x509.X500Name
import android.sun.security.x509.X509CertImpl
import android.sun.security.x509.X509CertInfo
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.Random
import java.util.concurrent.TimeUnit

/**
 * Talks to the watch's ADB daemon over Wi-Fi (Wireless debugging) so the phone
 * can install the watch APK that is bundled in this APK's assets. The user pairs
 * once with the code shown on the watch; the RSA key that pairing authorises is
 * kept in app storage, so later installs only need the connect port.
 */
class WatchInstaller(private val context: Context) {

    private val manager: AdbManager by lazy { AdbManager(context) }

    /** Pairs this phone's ADB key with the watch. Throws with a readable message on failure. */
    suspend fun pair(host: String, pairPort: Int, code: String): Unit = withContext(Dispatchers.IO) {
        manager.pair(host, pairPort, code.trim())
    }

    /**
     * Installs the bundled watch APK. Returns the daemon's reply, which contains
     * "Success" when the install went through.
     */
    suspend fun install(host: String, connectPort: Int, onProgress: (Int) -> Unit): String =
        withContext(Dispatchers.IO) {
            manager.setTimeout(20, TimeUnit.SECONDS)
            if (!manager.isConnected) {
                manager.connect(host, connectPort)
            }
            try {
                val size = context.assets.openFd(WEAR_APK).use { it.length }
                    .takeIf { it > 0 } ?: context.assets.open(WEAR_APK).use { it.readBytes().size.toLong() }
                // exec: gives a raw (non-pty) stream, the same thing `adb install` uses.
                val stream = manager.openStream("exec:cmd package install -r -S $size")
                var sent = 0L
                context.assets.open(WEAR_APK).use { input ->
                    val out = stream.openOutputStream()
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        sent += n
                        onProgress((sent * 100 / size).toInt())
                    }
                    out.flush()
                }
                val reply = stream.openInputStream().bufferedReader().use { it.readText() }.trim()
                stream.close()
                reply.ifEmpty { "No reply from watch" }
            } finally {
                runCatching { manager.disconnect() }
            }
        }

    /** Size of the bundled watch APK in bytes, or 0 if it is missing. */
    fun bundledApkSize(): Long = runCatching {
        context.assets.openFd(WEAR_APK).use { it.length }
    }.getOrElse {
        runCatching { context.assets.open(WEAR_APK).use { it.available().toLong() } }.getOrDefault(0)
    }

    companion object {
        const val WEAR_APK = "wear.apk"
    }

    /** libadb needs an RSA key and a self-signed certificate; keep them in app storage. */
    private class AdbManager(context: Context) : AbsAdbConnectionManager() {
        private val privateKey: PrivateKey
        private val certificate: Certificate

        init {
            setApi(Build.VERSION.SDK_INT)
            val keyFile = File(context.filesDir, "adb-key.der")
            val certFile = File(context.filesDir, "adb-cert.der")
            if (keyFile.exists() && certFile.exists()) {
                privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                certificate = certFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
            } else {
                val generator = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }
                val pair = generator.generateKeyPair()
                privateKey = pair.private
                certificate = selfSigned(pair.public, pair.private)
                keyFile.writeBytes(privateKey.encoded)
                certFile.writeBytes(certificate.encoded)
            }
        }

        override fun getPrivateKey(): PrivateKey = privateKey
        override fun getCertificate(): Certificate = certificate
        override fun getDeviceName(): String = "Spo2Helper"

        private fun selfSigned(publicKey: java.security.PublicKey, privateKey: PrivateKey): Certificate {
            val algorithm = "SHA512withRSA"
            val name = X500Name("CN=Spo2Helper")
            val notBefore = Date()
            val notAfter = Date(System.currentTimeMillis() + 20L * 365 * 24 * 3600 * 1000)
            val extensions = CertificateExtensions().apply {
                set("SubjectKeyIdentifier", SubjectKeyIdentifierExtension(KeyIdentifier(publicKey).identifier))
                set("PrivateKeyUsage", android.sun.security.x509.PrivateKeyUsageExtension(notBefore, notAfter))
            }
            val info = X509CertInfo().apply {
                set("version", CertificateVersion(2))
                set("serialNumber", CertificateSerialNumber(Random().nextInt() and Int.MAX_VALUE))
                set("algorithmID", CertificateAlgorithmId(AlgorithmId.get(algorithm)))
                set("subject", CertificateSubjectName(name))
                set("key", CertificateX509Key(publicKey))
                set("validity", CertificateValidity(notBefore, notAfter))
                set("issuer", CertificateIssuerName(name))
                set("extensions", extensions)
            }
            return X509CertImpl(info).apply { sign(privateKey, algorithm) }
        }
    }
}
