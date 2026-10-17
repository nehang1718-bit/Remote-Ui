package com.example.hitachiremote

// Ported from the open-source IRremoteESP8266 library (ir_Hitachi.cpp).
// Untested on real hardware: use the in-app protocol picker to find the one your AC answers to.

data class AcState(
    val power: Boolean,
    val mode: String,   // cool, heat, dry, fan, auto
    val temp: Int,      // 16..32
    val fan: String,    // auto, low, med, high
    val swing: Boolean,
    val proto: String = "AC"
)

class IrFrame(val carrierHz: Int, val pattern: IntArray)

object HitachiEncoder {
    val PROTOCOLS = listOf("AC", "AC1", "AC264", "AC344", "AC424")
    private var prev: AcState? = null
    private var prevProto = ""

    private fun rev(v: Int, bits: Int) = Integer.reverse(v) ushr (32 - bits)

    private class T(val hm: Int, val hs: Int, val bm: Int, val one: Int, val zero: Int,
                    val lm: Int = 0, val ls: Int = 0)

    private fun build(bytes: IntArray, n: Int, t: T, msb: Boolean): IrFrame {
        val p = ArrayList<Int>()
        if (t.lm > 0) { p.add(t.lm); p.add(t.ls) }
        p.add(t.hm); p.add(t.hs)
        for (k in 0 until n) for (i in 0 until 8) {
            val bit = if (msb) (bytes[k] shr (7 - i)) and 1 else (bytes[k] shr i) and 1
            p.add(t.bm); p.add(if (bit == 1) t.one else t.zero)
        }
        p.add(t.bm)
        return IrFrame(38000, p.toIntArray())
    }

    fun encode(s: AcState): IrFrame? {
        val last = if (prevProto == s.proto) prev else null
        val frame = when (s.proto) {
            "AC" -> ac(s)
            "AC1" -> ac1(s, last)
            "AC264", "AC344", "AC424" -> ac424(s, last)
            else -> null
        }
        prev = s; prevProto = s.proto
        return frame
    }

    // 28-byte "Hitachi" protocol
    private fun ac(s: AcState): IrFrame {
        val r = IntArray(28)
        intArrayOf(0x80, 0x08, 0x0C, 0x02, 0xFD, 0x80, 0x7F, 0x88, 0x48, 0x10).copyInto(r)
        r[14] = 0x60; r[15] = 0x60; r[24] = 0x80
        val mode = when (s.mode) { "auto" -> 2; "heat" -> 3; "cool" -> 4; "dry" -> 5; "fan" -> 0xC; else -> 2 }
        val temp = if (mode == 0xC) 64 else s.temp.coerceIn(16, 32)
        var fan = when (s.fan) { "low" -> 2; "med" -> 3; "high" -> 5; else -> 1 }
        if (mode == 5) fan = fan.coerceIn(2, 3)
        if (mode == 0xC) fan = fan.coerceAtLeast(2)
        r[9] = if (temp == 16) 0x90 else 0x10
        r[10] = rev(mode, 8); r[11] = rev(temp shl 1, 8); r[13] = rev(fan, 8)
        if (s.swing) { r[14] = r[14] or 0x80; r[15] = r[15] or 0x80 }
        if (s.power) r[17] = r[17] or 1
        var sum = 62
        for (i in 0 until 27) sum -= rev(r[i], 8)
        r[27] = rev(sum and 0xFF, 8)
        return build(r, 28, T(3300, 1700, 400, 1250, 500), true)
    }

    // 13-byte "AC1" protocol (power and swing are toggle bits)
    private fun ac1(s: AcState, last: AcState?): IrFrame {
        val r = intArrayOf(0xB2, 0xAE, 0x4D, 0x91, 0xF0, 0xE1, 0xA4, 0, 0, 0, 0, 0x61, 0x24)
        val mode = when (s.mode) { "dry" -> 2; "fan" -> 4; "cool" -> 6; "heat" -> 9; else -> 14 }
        var fan = when (s.fan) { "high" -> 2; "med" -> 4; "low" -> 8; else -> 1 }
        when (mode) {
            2 -> fan = 8
            14 -> fan = 1
            9, 4 -> if (fan == 1) fan = 8
        }
        val temp = if (mode == 14) 25 else s.temp.coerceIn(16, 32)
        r[5] = (mode shl 4) or fan
        r[6] = (r[6] and 0x83) or (rev(temp - 7, 5) shl 2)
        val powerToggle = (last?.power ?: true) != s.power
        val swingToggle = last != null && last.swing != s.swing
        r[11] = (if (swingToggle) 1 else 0) or ((if (powerToggle) 1 else 0) shl 4) or
                ((if (s.power) 1 else 0) shl 5) or ((if (s.swing) 1 else 0) shl 6)
        var sum = 0
        for (i in 5 until 12) sum += rev(r[i] and 0xF, 4) + rev((r[i] shr 4) and 0xF, 4)
        r[12] = rev(sum and 0xFF, 8)
        return build(r, 13, T(3400, 3400, 400, 1250, 500), true)
    }

    // 33-byte (AC264), 43-byte (AC344) and 53-byte (AC424) protocols
    private fun ac424(s: AcState, last: AcState?): IrFrame {
        val r = IntArray(53)
        r[0] = 0x01; r[1] = 0x10; r[3] = 0x40; r[5] = 0xFF; r[7] = 0xCC; r[27] = 0xE1
        r[33] = 0x80; r[35] = 0x03; r[37] = 0x01; r[39] = 0x88
        r[45] = 0xFF; r[47] = 0xFF; r[49] = 0xFF; r[51] = 0xFF
        if (s.proto == "AC344") { r[37] = 0; r[39] = 0 }
        if (s.proto == "AC264") { r[27] = 0xC1 }
        val mode = when (s.mode) { "fan" -> 1; "dry" -> 5; "heat" -> 6; else -> 3 }
        val temp = if (mode == 1) 27 else s.temp.coerceIn(16, 32)
        var fan = when (s.fan) { "low" -> 2; "med" -> 3; "high" -> 4; else -> 5 }
        if (s.proto == "AC264" && fan == 2) fan = 1
        if (mode == 5 && fan != 5) fan = fan.coerceAtMost(2)
        if (mode == 1 && fan == 5) fan = 1
        r[9] = 0x92
        if (s.proto != "AC264" && fan == 1) r[9] = 0x98
        r[13] = temp shl 2
        r[25] = mode or (fan shl 4)
        r[27] = (r[27] and 0xEF) or ((if (s.power) 1 else 0) shl 4)
        r[37] = (r[37] and 0xDF) or ((if (s.swing) 1 else 0) shl 5)
        r[11] = when {
            last == null || last.power != s.power || last.mode != s.mode -> 0x13
            last.fan != s.fan -> 0x42
            last.temp != s.temp -> if (s.temp < last.temp) 0x43 else 0x44
            last.swing != s.swing -> 0x81
            else -> 0x13
        }
        for (i in 4 until 53 step 2) r[i] = r[i - 1].inv() and 0xFF
        return when (s.proto) {
            "AC424" -> build(r, 53, T(3416, 1604, 463, 1208, 372, 29784, 49290), false)
            "AC344" -> build(r, 43, T(3300, 1700, 400, 1250, 500), false)
            else -> build(r, 33, T(3300, 1700, 400, 1250, 500), false)
        }
    }
}
