package app.rollentrainer.ui.world.geo.scenery

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Deterministic randomness. Scenery must come out identical for the same route and tile on every
 * device and every run (tiles are evicted and rebuilt while riding, and a tree that moves when its
 * tile streams in again would be very visible), so nothing here uses wall-clock seeds or
 * java.util.Random's platform-dependent streams: SplitMix64 is fully specified and fast.
 */
internal object Hash {
    fun mix(z0: Long): Long {
        var z = z0 + -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    fun of(a: Long, b: Long): Long = mix(mix(a) xor (b * 0x632BE59BD9B4E019L))
    fun of(a: Long, b: Long, c: Long): Long = of(of(a, b), c)
    fun of(a: Long, b: Long, c: Long, d: Long): Long = of(of(of(a, b), c), d)

    /** A float in [0, 1) from a hash, for per-object decisions that must not depend on call order. */
    fun unit(h: Long): Float = ((h ushr 40).toInt() and 0xFFFFFF) / 16777216f

    fun str(s: String): Long {
        var h = 0x2545F4914F6CDD1DL
        for (c in s) h = mix(h xor c.code.toLong())
        return h
    }
}

/** SplitMix64 stream. */
internal class Rand(seed: Long) {
    private var state = Hash.mix(seed)

    fun nextLong(): Long {
        state += -0x61c8864680b583ebL
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    /** Uniform in [0, 1). */
    fun f(): Float = ((nextLong() ushr 40).toInt() and 0xFFFFFF) / 16777216f
    fun d(): Double = (nextLong() ushr 11) * (1.0 / (1L shl 53))
    fun range(a: Float, b: Float): Float = a + (b - a) * f()
    fun range(a: Double, b: Double): Double = a + (b - a) * d()
    fun int(n: Int): Int = if (n <= 1) 0 else ((nextLong() ushr 33) % n).toInt()
    fun chance(p: Float): Boolean = f() < p
    fun chance(p: Double): Boolean = d() < p
    /** Symmetric in [-a, a]. */
    fun sym(a: Float): Float = (f() * 2f - 1f) * a
    fun angle(): Float = f() * (2f * PI.toFloat())

    /** Standard normal (Box-Muller); used for natural size variation. */
    fun gauss(): Float {
        val u1 = (d() * 0.999999 + 1e-6)
        val u2 = d()
        return (sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)).toFloat()
    }

    fun <T> pick(list: List<T>): T = list[int(list.size)]

    /** Index into [weights] with probability proportional to the weight. */
    fun weighted(weights: FloatArray): Int {
        var sum = 0f
        for (w in weights) sum += w
        if (sum <= 0f) return 0
        var x = f() * sum
        for (i in weights.indices) {
            x -= weights[i]
            if (x < 0f) return i
        }
        return weights.size - 1
    }

    /** Uniform direction on the unit sphere. */
    fun onSphere(out: Vec3): Vec3 {
        val z = f() * 2f - 1f
        val a = angle()
        val r = sqrt(1f - z * z)
        out.set(r * cos(a), z, r * sin(a))
        return out
    }
}
