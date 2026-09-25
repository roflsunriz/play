package io.github.playmusic.data.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/** Supplies the device DPoP key, mirroring the desktop client's secure-storage key pair. */
interface DpopKeys {
    fun getOrCreate(): DpopProofs.Key
    fun current(): DpopProofs.Key?
}

class DpopKeyStore : DpopKeys {
    override fun getOrCreate(): DpopProofs.Key {
        current()?.let { return it }
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEY_STORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build(),
        )
        generator.generateKeyPair()
        return checkNotNull(current()) { "DPoP key could not be created" }
    }

    override fun current(): DpopProofs.Key? {
        val store = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        val privateKey = store.getKey(KEY_ALIAS, null) as? java.security.PrivateKey ?: return null
        val certificate = store.getCertificate(KEY_ALIAS) ?: return null
        val point = (certificate.publicKey as? ECPublicKey)?.w ?: return null
        return DpopProofs.Key(privateKey,
            DpopProofs.coordinate(point.affineX), DpopProofs.coordinate(point.affineY))
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "play_dpop_key"
    }
}
