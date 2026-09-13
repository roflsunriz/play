package io.github.playmusic.data.auth

import android.os.Build
import java.io.ByteArrayOutputStream

/** Platform oneof in the service's connectivity.proto, verified from the reference descriptor. */
sealed interface ClientTokenPlatformData {
    val fieldNumber: Int
    fun encode(): ByteArray
}

data class NativeAndroidData(
    val screen: AndroidScreen? = null,
    val osVersion: String = "",
    val apiLevel: Int = 0,
    val name: String = "",
    val model: String = "",
    val brand: String = "",
    val manufacturer: String = "",
    val volumeSteps: Int = 0,
    val deviceDataExtension: String = "",
    val installerName: String = "",
) : ClientTokenPlatformData {
    override val fieldNumber: Int = 1

    init {
        require(apiLevel >= 0 && volumeSteps >= 0)
    }

    override fun encode(): ByteArray = ByteArrayOutputStream().apply {
        screen?.let { write(ProtoWire.fieldMessage(1, it.encode())) }
        writeString(2, osVersion)
        writeNumber(3, apiLevel.toLong())
        writeString(4, name)
        writeString(5, model)
        writeString(6, brand)
        writeString(7, manufacturer)
        writeNumber(8, volumeSteps.toLong())
        writeString(9, deviceDataExtension)
        writeString(10, installerName)
    }.toByteArray()

    companion object {
        fun current(): NativeAndroidData = NativeAndroidData(
            osVersion = Build.VERSION.RELEASE.orEmpty(),
            apiLevel = Build.VERSION.SDK_INT,
            name = Build.DEVICE.orEmpty(),
            model = Build.MODEL.orEmpty(),
            brand = Build.BRAND.orEmpty(),
            manufacturer = Build.MANUFACTURER.orEmpty(),
        )
    }
}

data class AndroidScreen(
    val width: Int,
    val height: Int,
    val smallestScreenWidthDp: Int,
    val screenDensityCurrent: Int,
    val screenDensityStable: Int,
) {
    init {
        require(listOf(width, height, smallestScreenWidthDp, screenDensityCurrent, screenDensityStable).all { it >= 0 })
    }

    fun encode(): ByteArray = ByteArrayOutputStream().apply {
        writeNumber(1, width.toLong())
        writeNumber(2, height.toLong())
        writeNumber(3, smallestScreenWidthDp.toLong())
        writeNumber(4, screenDensityCurrent.toLong())
        writeNumber(5, screenDensityStable.toLong())
    }.toByteArray()
}

private fun ByteArrayOutputStream.writeString(field: Int, value: String) {
    if (value.isNotEmpty()) write(ProtoWire.fieldString(field, value))
}

private fun ByteArrayOutputStream.writeNumber(field: Int, value: Long) {
    if (value != 0L) write(ProtoWire.fieldVarint(field, value))
}
