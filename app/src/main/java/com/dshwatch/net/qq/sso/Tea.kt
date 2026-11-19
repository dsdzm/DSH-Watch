package com.dshwatch.net.qq.sso

import java.util.Random

/**
 * QQ 用的 TEA 变种。
 *
 * 和教科书 TEA 有三处不同，任何一处按标准实现都会让服务端直接丢包：
 *
 * 1. **16 轮**（标准是 32 轮）；
 * 2. 解密时减的是 `((v0 shl 4) xor (v0 ushr 5)) + v0`，不是标准 TEA 的
 *    `((v0 shl 4) + k2) xor (v0 + sum) xor ((v0 ushr 5) + k3)` —— 这是腾讯改的；
 * 3. 分组之后还套了两层链：密文要先 xor 上一块密文，再 xor 上一块的「加工明文」。
 *
 * 第 3 点最容易被当成普通 CBC 而写错。这里的实现按「加密与解密严格互逆」来写，
 * 两者共用同一个链式状态机，改一边必须改另一边。
 *
 * 明文布局（加密后）：
 * ```
 * [0]        = (随机数 & 0xF8) | 填充长度
 * [1..pad]   = 随机填充
 * [pad+1..2] = 两个随机字节（与正文一起参与加密，防止固定 IV 攻击）
 * [..]       = 正文
 * 末尾 7 字节 = 0
 * ```
 */
internal object Tea {

    private const val DELTA = 0x9E3779B9L
    private const val MASK = 0xFFFFFFFFL

    /** 16 字节密钥 */
    fun encrypt(plain: ByteArray, key: ByteArray): ByteArray = run(plain, key, true)

    /**
     * @throws IllegalStateException 密文长度非法或填充位校验不通过 —— 通常是密钥不对
     */
    fun decrypt(cipher: ByteArray, key: ByteArray): ByteArray = run(cipher, key, false)

    private fun run(data: ByteArray, key: ByteArray, encrypt: Boolean): ByteArray {
        require(key.size >= 16) { "TEA 密钥至少要 16 字节，现在 ${key.size}" }
        val k = LongArray(4) { u32(key, it * 4) }
        return if (encrypt) doEncrypt(data, k) else doDecrypt(data, k)
    }

    // ---------------- 加密 ----------------

    private fun doEncrypt(plain: ByteArray, k: LongArray): ByteArray {
        val rnd = Random()
        val rem = (plain.size + 10) % 8
        val pad = if (rem == 0) 0 else 8 - rem

        // 总长一定是 8 的倍数：1(头) + pad + 2(随机) + len + 7(尾)
        val out = ByteArray(pad + plain.size + 10)
        val block = ByteArray(8)

        block[0] = (((rnd.nextInt() and 0xF8) or pad) and 0xFF).toByte()
        for (i in 1..pad) block[i] = (rnd.nextInt() and 0xFF).toByte()

        var pos = pad + 1
        var outPos = 0
        var iv = ByteArray(8)
        var prevCipher = ByteArray(8)
        var first = true

        /** 把攒满的 8 字节压成一块写出去 */
        fun flush() {
            if (!first) {
                for (i in 0..7) block[i] = (block[i].toInt() xor prevCipher[i].toInt()).toByte()
            }
            val enc = encBlock(block, k)
            for (i in 0..7) {
                out[outPos + i] = (enc[i].toInt() xor iv[i].toInt()).toByte()
            }
            // iv 记的是「加工后的明文块」，不是密文块
            iv = block.copyOf()
            // 下一块要 xor 的是「已经掩码过的最终密文」，不是 encBlock 的裸结果 ——
            // 这两者在第一块之后就不一样了，记错会让所有后续块全错
            prevCipher = out.copyOfRange(outPos, outPos + 8)
            outPos += 8
            first = false
            block.fill(0)
            pos = 0
        }

        // 两个随机字节。用 while 而不是 repeat(2)：触发 flush 的那一轮并没有写入，
        // 不该消耗次数 —— 用 repeat 会少写一个，导致正文整体错位一字节
        var g = 0
        while (g < 2) {
            if (pos < 8) {
                block[pos++] = (rnd.nextInt() and 0xFF).toByte()
                g++
            }
            if (pos == 8) flush()
        }

        var i = 0
        while (i < plain.size) {
            if (pos < 8) block[pos++] = plain[i++]
            if (pos == 8) flush()
        }

        // 末尾 7 个 0。这里**不能**假设它们刚好攒满最后一块 ——
        // 当 len % 8 == 7 时，7 个 0 只填到第 7 个位置，最后一块根本不会触发 flush，
        // 输出尾部就留下 8 个 0 字节（永远解不出、且校验必失败）。
        repeat(7) {
            if (pos < 8) block[pos++] = 0
            if (pos == 8) flush()
        }
        if (pos != 0) {
            while (pos < 8) block[pos++] = 0
            flush()
        }

        return out
    }

    // ---------------- 解密 ----------------

    private fun doDecrypt(cipher: ByteArray, k: LongArray): ByteArray {
        require(cipher.size >= 16 && cipher.size % 8 == 0) {
            "密文长度必须是 8 的倍数且不小于 16，现在 ${cipher.size}"
        }

        val len = cipher.size
        var iv = decBlock(cipher.copyOfRange(0, 8), k)
        val pad = iv[0].toInt() and 7
        val plainLen = len - pad - 10
        if (plainLen < 0) fail("填充长度 $pad 不合理")

        val out = ByteArray(plainLen)
        var outPos = 0
        var cipherPos = 8
        var prevPos = 0
        var first = true
        var ivPos = pad + 1

        /**
         * 解下一块。
         *
         * [prevPos] 由调用方在**推进 cipherPos 之前**设置好，这里不要碰 ——
         * 明文块要 xor 的是「前一块密文」，两者错位一块就会解出乱码。
         */
        fun nextBlock(): Boolean {
            if (cipherPos + 8 > len) return false
            val t = ByteArray(8) { i -> (iv[i].toInt() xor cipher[cipherPos + i].toInt()).toByte() }
            iv = decBlock(t, k)
            cipherPos += 8
            ivPos = 0
            return true
        }

        // 跳过两个随机字节（不产出明文，只推进游标）。
        // 注意推进与换块的顺序：先 ++ 再判断是否满 8 —— 反过来的话，
        // 换块后游标已经归零，再 ++ 就正好跳过新块的第 0 个字节。
        var g = 0
        while (g < 2) {
            if (ivPos < 8) {
                ivPos++
                g++
            }
            if (ivPos == 8) {
                first = false
                if (!nextBlock()) fail("随机填充段越界")
            }
        }

        var p = plainLen
        while (p != 0) {
            if (ivPos < 8) {
                out[outPos++] = if (first) iv[ivPos]
                else (cipher[prevPos + ivPos].toInt() xor iv[ivPos].toInt()).toByte()
                ivPos++
            }
            if (ivPos == 8) {
                prevPos = cipherPos - 8
                first = false
                if (!nextBlock()) fail("正文段越界")
            }
            p--
        }

        // 尾部 7 个 0 是固定填充；非 0 说明密钥或数据不对，
        // 早点报错胜过拿脏数据去解析出一个看似合理的错包。
        // 这 7 个字节可能正好压在最后一块里，读完就没有下一块了 —— 那是正常结束。
        g = 0
        while (g < 7) {
            if (ivPos < 8) {
                val v = if (first) iv[ivPos]
                else (cipher[prevPos + ivPos].toInt() xor iv[ivPos].toInt()).toByte()
                if (v.toInt() != 0) fail("尾部填充非 0")
                ivPos++
            }
            if (ivPos == 8) {
                prevPos = cipherPos - 8
                if (!nextBlock()) {
                    // 还没校验够 7 个就到头了，说明密码长度字段与实际密文对不上
                    if (g < 6) fail("尾部段越界")
                    break
                }
            }
            g++
        }

        return out
    }

    private fun fail(why: String): Nothing =
        throw IllegalStateException("TEA 解密失败：$why")

    // ---------------- 单块 ----------------

    private fun encBlock(src: ByteArray, k: LongArray): ByteArray {
        var v0 = u32(src, 0)
        var v1 = u32(src, 4)
        var sum = 0L
        repeat(16) {
            sum = (sum + DELTA) and MASK
            v0 = (v0 + (((v1 shl 4) + k[0]) xor (v1 + sum) xor ((v1 ushr 5) + k[1]))) and MASK
            v1 = (v1 + (((v0 shl 4) + k[2]) xor (v0 + sum) xor ((v0 ushr 5) + k[3]))) and MASK
        }
        return ByteArray(8).also { putU32(it, 0, v0); putU32(it, 4, v1) }
    }

    private fun decBlock(src: ByteArray, k: LongArray): ByteArray {
        var v0 = u32(src, 0)
        var v1 = u32(src, 4)
        var sum = (DELTA shl 4) and MASK
        repeat(16) {
            // 腾讯的写法：这里用 xor 拼移位，不是标准 TEA 的加法
            v1 = (v1 - (((v0 shl 4) + k[2]) xor (v0 + sum) xor ((v0 ushr 5) + k[3]))) and MASK
            v0 = (v0 - (((v1 shl 4) + k[0]) xor (v1 + sum) xor ((v1 ushr 5) + k[1]))) and MASK
            sum = (sum - DELTA) and MASK
        }
        return ByteArray(8).also { putU32(it, 0, v0); putU32(it, 4, v1) }
    }

    private fun u32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or
            ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or
            (b[at + 3].toLong() and 0xFF)

    private fun putU32(b: ByteArray, at: Int, v: Long) {
        b[at] = (v ushr 24).toByte()
        b[at + 1] = (v ushr 16).toByte()
        b[at + 2] = (v ushr 8).toByte()
        b[at + 3] = v.toByte()
    }
}
