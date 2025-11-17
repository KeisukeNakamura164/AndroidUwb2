package io.github.keisukenakamura164.androiduwb2

import java.nio.ByteBuffer
import java.nio.ByteOrder // ★★★ 1. ByteOrderをインポート

/** Controller(親)→Controlee(子) へ送るパラメーター */
data class UwbControllerParams(
    val address: ByteArray,
    val channel: Int,
    val preambleIndex: Int,
    // ★★★ 2. sessionId をデータクラスのプライマリコンストラクタから削除
    // val sessionId: Int,
) {

    /** シリアライズ、デシリアライズ用 */
    companion object {

        // アドレス(8 bytes) + channel(4 bytes) + preambleIndex(4 bytes) = 16 bytes
        // アドレスサイズを先頭につけると +1 で 17 bytes になるが、UWBアドレスは8バイト固定なのでサイズ情報は不要
        private const val BYTE_SIZE = 8 + 4 + 4

        /**
         * UwbControllerParams を固定長のバイト配列にエンコードする
         */
        fun encode(params: UwbControllerParams): ByteArray {
            val buffer = ByteBuffer.allocate(BYTE_SIZE).apply {
                order(ByteOrder.BIG_ENDIAN)
                // UWBのアドレスは8バイト固定なので、サイズ情報は含めず直接書き込む
                put(params.address)
                putInt(params.channel)
                putInt(params.preambleIndex)
            }
            return buffer.array()
        }

        /**
         * バイト配列から UwbControllerParams をデコードする
         */
        fun decode(byteArray: ByteArray): UwbControllerParams {
            val buffer = ByteBuffer.wrap(byteArray).apply {
                order(ByteOrder.BIG_ENDIAN)
            }
            // 8バイト固定でアドレスを読み出す
            val address = ByteArray(8).apply { buffer.get(this) }
            val channel = buffer.getInt()
            val preambleIndex = buffer.getInt()

            return UwbControllerParams(
                address = address,
                channel = channel,
                preambleIndex = preambleIndex
                // ★★★ 3. sessionIdの引数を削除
            )
        }
    }

    // ByteArrayの比較は contentEquals を使う必要があるため、equalsとhashCodeを正しくオーバーライド
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as UwbControllerParams

        if (!address.contentEquals(other.address)) return false
        if (channel != other.channel) return false
        if (preambleIndex != other.preambleIndex) return false
        // ★★★ 4. sessionIdの比較を削除

        return true
    }

    override fun hashCode(): Int {
        var result = address.contentHashCode()
        result = 31 * result + channel
        result = 31 * result + preambleIndex
        // ★★★ 5. sessionIdのハッシュコード計算を削除
        return result
    }
}
