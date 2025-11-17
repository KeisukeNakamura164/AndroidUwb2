package io.github.keisukenakamura164.androiduwb2

import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable

/** Controller(親)→Controlee(子) へ送るパラメーター */
data class UwbControllerParams2(
    val address: ByteArray,
    val channel: Int,
    val preambleIndex: Int,
    val sessionId: Int,
    val sessionKeyInfo: ByteArray
) : Serializable {

    /** シリアライズ、デシリアライズ用 */
    companion object {

        fun encode(uwbHostParameter: UwbControllerParams2): ByteArray {
            return ByteArrayOutputStream().use { byteArrayOutputStream ->
                ObjectOutputStream(byteArrayOutputStream).use { objectOutputStream ->
                    // 書き込んで ByteArray を返す
                    objectOutputStream.writeObject(uwbHostParameter)
                    byteArrayOutputStream.toByteArray()
                }
            }
        }

        fun decode(byteArray: ByteArray): UwbControllerParams2 {
            return byteArray.inputStream().use { byteArrayInputStream ->
                ObjectInputStream(byteArrayInputStream).use { objectInputStream ->
                    // キャストする
                    objectInputStream.readObject() as UwbControllerParams2
                }
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as UwbControllerParams2

        if (!address.contentEquals(other.address)) return false
        if (channel != other.channel) return false
        if (preambleIndex != other.preambleIndex) return false
        if (sessionId != other.sessionId) return false
        if (!sessionKeyInfo.contentEquals(other.sessionKeyInfo)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = address.contentHashCode()
        result = 31 * result + channel
        result = 31 * result + preambleIndex
        result = 31 * result + sessionId
        result = 31 * result + sessionKeyInfo.contentHashCode()
        return result
    }

}