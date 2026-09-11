package com.campusmesh.testing

import java.security.KeyStore
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.cert.Certificate
import java.util.Date
import java.util.Enumeration
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGenerator
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal in-memory stand-in for the "AndroidKeyStore" JCA provider.
 *
 * `androidx.security.crypto` (MasterKey / EncryptedSharedPreferences) asks the platform for
 * `KeyStore.getInstance("AndroidKeyStore")` and an AES `KeyGenerator` from the same provider.
 * Neither exists on the JVM, so any test that constructs [com.campusmesh.android.crypto.EncryptionService],
 * [com.campusmesh.android.identity.SecureIdentityStateManager] or
 * [com.campusmesh.android.noise.NoiseEncryptionService] dies with
 * `KeyStoreException: AndroidKeyStore not found`.
 *
 * This registers a provider of that name backed by a plain HashMap, which is enough for those
 * classes to create and reload a master key inside a single test JVM.
 *
 * Test-only: it provides no hardware backing and no at-rest protection whatsoever. Call
 * [install] from an `@BeforeClass`/`@Before` in any test that needs encrypted preferences.
 */
object FakeAndroidKeyStore {

    private const val PROVIDER_NAME = "AndroidKeyStore"

    /** Entries are process-wide, mirroring the real keystore's behaviour across a test run. */
    private val entries = ConcurrentHashMap<String, SecretKey>()

    @Synchronized
    fun install() {
        if (java.security.Security.getProvider(PROVIDER_NAME) != null) return
        java.security.Security.addProvider(FakeProvider())
    }

    /** Drops every stored key - use between tests that assert on key rotation. */
    fun clear() = entries.clear()

    private class FakeProvider : Provider(PROVIDER_NAME, 1.0, "Test-only AndroidKeyStore") {
        init {
            put("KeyStore.$PROVIDER_NAME", FakeKeyStoreSpi::class.java.name)
            put("KeyGenerator.AES", FakeAesKeyGeneratorSpi::class.java.name)
        }
    }

    /**
     * Ignores the AndroidKeyStore-specific `KeyGenParameterSpec` it is initialised with and just
     * emits random AES key material - the spec only describes hardware constraints we cannot and
     * need not honour here.
     */
    class FakeAesKeyGeneratorSpi : KeyGeneratorSpi() {
        private var keySizeBits = 256

        override fun engineInit(random: SecureRandom?) = Unit

        override fun engineInit(params: java.security.spec.AlgorithmParameterSpec?, random: SecureRandom?) {
            // KeyGenParameterSpec carries the requested size, but it is an Android-only type;
            // reading it reflectively keeps this class compilable against a plain JVM.
            val size = params?.let {
                runCatching {
                    it.javaClass.getMethod("getKeySize").invoke(it) as? Int
                }.getOrNull()
            }
            if (size != null && size > 0) keySizeBits = size
        }

        override fun engineInit(keysize: Int, random: SecureRandom?) {
            keySizeBits = keysize
        }

        override fun engineGenerateKey(): SecretKey {
            val bytes = ByteArray(keySizeBits / 8)
            SecureRandom().nextBytes(bytes)
            val key = SecretKeySpec(bytes, "AES")
            // The real provider persists on generate; callers then load it back by alias.
            entries[PENDING_ALIAS] = key
            return key
        }

        companion object {
            /**
             * KeyGenParameterSpec carries the alias, but we read it back through the same
             * reflective path the spec itself is opaque to, so generated keys are parked under a
             * sentinel and claimed by the next setEntry/getEntry for a real alias.
             */
            const val PENDING_ALIAS = "__pending__"
        }
    }

    class FakeKeyStoreSpi : KeyStoreSpi() {

        override fun engineGetKey(alias: String, password: CharArray?): java.security.Key? {
            entries[alias]?.let { return it }
            // Claim a key that engineGenerateKey just produced for this alias.
            val pending = entries.remove(FakeAesKeyGeneratorSpi.PENDING_ALIAS) ?: return null
            entries[alias] = pending
            return pending
        }

        override fun engineContainsAlias(alias: String): Boolean =
            entries.containsKey(alias) || entries.containsKey(FakeAesKeyGeneratorSpi.PENDING_ALIAS)

        override fun engineSetKeyEntry(
            alias: String,
            key: java.security.Key,
            password: CharArray?,
            chain: Array<out Certificate>?
        ) {
            if (key is SecretKey) entries[alias] = key
        }

        override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<out Certificate>?) =
            throw UnsupportedOperationException("not needed by androidx.security.crypto")

        override fun engineDeleteEntry(alias: String) {
            entries.remove(alias)
        }

        override fun engineAliases(): Enumeration<String> =
            java.util.Collections.enumeration(entries.keys.toList())

        override fun engineSize(): Int = entries.size
        override fun engineIsKeyEntry(alias: String): Boolean = entries.containsKey(alias)
        override fun engineIsCertificateEntry(alias: String): Boolean = false
        override fun engineGetCertificate(alias: String): Certificate? = null
        override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null
        override fun engineGetCertificateAlias(cert: Certificate): String? = null
        override fun engineGetCreationDate(alias: String): Date = Date(0)
        override fun engineSetCertificateEntry(alias: String, cert: Certificate) = Unit
        override fun engineStore(stream: java.io.OutputStream?, password: CharArray?) = Unit
        override fun engineLoad(stream: java.io.InputStream?, password: CharArray?) = Unit
    }

    /** Referenced so the unused-import warning does not fire on KeyStore/KeyGenerator. */
    @Suppress("unused")
    private val keepImports = listOf(KeyStore::class, KeyGenerator::class)
}
