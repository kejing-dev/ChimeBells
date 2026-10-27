package io.embodit.bianzhong.ui.chime.scene

import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.math.Vector2
import com.pico.spatial.core.math.Vector3
import io.embodit.bianzhong.domain.model.Bells
import io.embodit.bianzhong.domain.model.Vec3
import io.embodit.bianzhong.domain.usecase.PlateFacing
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** Collects triangles with per-vertex normals and texture coordinates, then makes one mesh. */
internal class MeshBuilder {
    val positions = ArrayList<Vector3>()
    val normals = ArrayList<Vector3>()
    val uvs = ArrayList<Vector2>()
    val indices = ArrayList<Int>()

    fun vertex(p: Vec3, n: Vec3, u: Float = 0f, v: Float = 0f): Int {
        positions += Vector3(p.x, p.y, p.z)
        normals += Vector3(n.x, n.y, n.z)
        uvs += Vector2(u, v)
        return positions.size - 1
    }

    fun tri(a: Int, b: Int, c: Int) {
        indices += a
        indices += b
        indices += c
    }

    fun quad(a: Int, b: Int, c: Int, d: Int) {
        tri(a, b, c)
        tri(a, c, d)
    }

    /** An axis-aligned box with flat normals. */
    fun box(center: Vec3, size: Vec3) {
        val h = size / 2f
        val faces =
            listOf(
                Vec3(1f, 0f, 0f) to listOf(Vec3(1f, -1f, -1f), Vec3(1f, 1f, -1f), Vec3(1f, 1f, 1f), Vec3(1f, -1f, 1f)),
                Vec3(-1f, 0f, 0f) to listOf(Vec3(-1f, -1f, 1f), Vec3(-1f, 1f, 1f), Vec3(-1f, 1f, -1f), Vec3(-1f, -1f, -1f)),
                Vec3(0f, 1f, 0f) to listOf(Vec3(-1f, 1f, -1f), Vec3(-1f, 1f, 1f), Vec3(1f, 1f, 1f), Vec3(1f, 1f, -1f)),
                Vec3(0f, -1f, 0f) to listOf(Vec3(-1f, -1f, 1f), Vec3(-1f, -1f, -1f), Vec3(1f, -1f, -1f), Vec3(1f, -1f, 1f)),
                Vec3(0f, 0f, 1f) to listOf(Vec3(-1f, -1f, 1f), Vec3(1f, -1f, 1f), Vec3(1f, 1f, 1f), Vec3(-1f, 1f, 1f)),
                Vec3(0f, 0f, -1f) to listOf(Vec3(1f, -1f, -1f), Vec3(-1f, -1f, -1f), Vec3(-1f, 1f, -1f), Vec3(1f, 1f, -1f)),
            )
        for ((n, corners) in faces) {
            val ids = corners.map { c -> vertex(center + Vec3(c.x * h.x, c.y * h.y, c.z * h.z), n) }
            quad(ids[0], ids[1], ids[2], ids[3])
        }
    }

    /** A bar from [a] to [b] with a square section of [w] (hooks). */
    fun bar(a: Vec3, b: Vec3, w: Float) {
        val dir = (b - a).normalized()
        var side = Vec3.UP.cross(dir)
        if (side.length < 1e-3f) side = Vec3.RIGHT
        side = side.normalized() * (w / 2f)
        val up = dir.cross(side).normalized() * (w / 2f)
        val ring = listOf(side + up, -side + up, -side - up, side - up)
        for (i in 0 until 4) {
            val p0 = ring[i]
            val p1 = ring[(i + 1) % 4]
            val n = (p0 + p1).normalized()
            val i0 = vertex(a + p0, n)
            val i1 = vertex(a + p1, n)
            val i2 = vertex(b + p1, n)
            val i3 = vertex(b + p0, n)
            quad(i0, i3, i2, i1)
        }
    }

    fun build(name: String): MeshResource {
        var min = Vec3(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        var max = Vec3(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (p in positions) {
            min = Vec3(minOf(min.x, p.x), minOf(min.y, p.y), minOf(min.z, p.z))
            max = Vec3(maxOf(max.x, p.x), maxOf(max.y, p.y), maxOf(max.z, p.z))
        }
        val c = (min + max) / 2f
        val h = ((max - min) / 2f).let { Vec3(maxOf(it.x, 0.001f), maxOf(it.y, 0.001f), maxOf(it.z, 0.001f)) }
        return MeshResource.createWithMeshModel(
            model = MeshModel(positions = positions, triangleIndices = indices, normals = normals, uv0 = uvs),
            bounds = BoundingBox(center = Vector3(c.x, c.y, c.z), halfExtent = Vector3(h.x, h.y, h.z)),
            name = name,
        )
    }
}

/**
 * The procedural yongzhong (design `entities3d.bell`, first-day tier: true-proportion white model in
 * bronze). One template in unit space, scaled per bell by (width, height, depth):
 * - origin at the hanging point = centre of the xuan ring, [Bells.HANG_SHARE] (0.22) below the shank top;
 * - shank (yong) 0.32 of the height, body 0.68: shoulder (wu) at y = -0.10, mouth corners at y = -0.78;
 * - almond (hewa) section: two arcs meeting in sharp corners (xian) at x = +-a, depth +-b;
 *   x = a cos(phi), z = b sin(phi)|sin(phi)|, the front drum faces +Z;
 * - the mouth arches up between the corners (the corners hang lowest);
 * - 36 bosses (mei): 2 faces x 2 fields x 3 rows x 3, short cones along the surface normal.
 * Texture coordinates: body u = phi / 2pi, v = 0.15 + 0.85 t (t = 0 shoulder .. 1 mouth); the shank uses
 * the plain strip v < 0.12 (see `BellArt.bronzeTexture`).
 */
internal object BellMeshes {
    const val TOP_Y = Bells.HANG_SHARE
    const val SHOULDER_Y = Bells.HANG_SHARE - (1f - Bells.BODY_SHARE)
    const val MOUTH_Y = SHOULDER_Y - Bells.BODY_SHARE
    private const val ARCH = 0.07f
    private const val AROUND = 36
    private const val RINGS = 16

    /** Half width / half depth of the section at t (0 shoulder .. 1 mouth), unit bell. */
    fun halfWidth(t: Float): Float = 0.5f * (0.80f + 0.20f * t)

    fun halfDepth(t: Float): Float = 0.5f * (0.70f + 0.30f * t)

    /** A point of the body surface, unit bell; [out] pushes it along the normal. */
    fun surface(phi: Float, t: Float, out: Float = 0f): Vec3 {
        val a = halfWidth(t)
        val b = halfDepth(t)
        val s = sin(phi)
        val x = a * cos(phi)
        val z = b * s * abs(s)
        val y = SHOULDER_Y - t * Bells.BODY_SHARE + ARCH * t.pow(4) * s * s
        val p = Vec3(x, y, z)
        return if (out == 0f) p else p + normalAt(phi, t) * out
    }

    fun normalAt(phi: Float, t: Float): Vec3 {
        val e = 0.01f
        val du = surface(phi + e, t) - surface(phi - e, t)
        val dv = surface(phi, (t + e).coerceAtMost(1f)) - surface(phi, (t - e).coerceAtLeast(0f))
        var n = dv.cross(du)
        if (n.length < 1e-6f) n = Vec3(cos(phi), 0f, sin(phi))
        n = n.normalized()
        // outward: away from the axis
        val radial = Vec3(cos(phi), 0f, sin(phi))
        return if (n.dot(radial) < 0f) -n else n
    }

    fun template(): MeshResource {
        val m = MeshBuilder()
        body(m)
        shoulder(m)
        shank(m)
        bosses(m)
        return m.build("bellTemplate")
    }

    private fun body(m: MeshBuilder) {
        val first = m.positions.size
        for (j in 0..RINGS) {
            val t = j.toFloat() / RINGS
            for (i in 0..AROUND) {
                val phi = (2 * PI * i / AROUND).toFloat()
                m.vertex(surface(phi, t), normalAt(phi, t), i.toFloat() / AROUND, 0.15f + 0.85f * t)
            }
        }
        val row = AROUND + 1
        for (j in 0 until RINGS) for (i in 0 until AROUND) {
            val a = first + j * row + i
            val b = a + 1
            val c = a + row + 1
            val d = a + row
            m.quad(a, b, c, d)
        }
    }

    /** The flat top of the body (wu), closing the shell. */
    private fun shoulder(m: MeshBuilder) {
        val center = m.vertex(Vec3(0f, SHOULDER_Y, 0f), Vec3.UP, 0.5f, 0.13f)
        val ring = (0 until AROUND).map { i ->
            val phi = (2 * PI * i / AROUND).toFloat()
            m.vertex(surface(phi, 0f), Vec3.UP, 0.5f + 0.4f * cos(phi), 0.13f)
        }
        for (i in 0 until AROUND) m.tri(center, ring[(i + 1) % AROUND], ring[i])
    }

    /** Shank (yong): tapered octagon from the shoulder to the top, the top cap (heng) and the xuan ring at y = 0. */
    private fun shank(m: MeshBuilder) {
        val sides = 8
        fun tube(y0: Float, r0: Float, y1: Float, r1: Float, v: Float) {
            for (i in 0 until sides) {
                val a0 = (2 * PI * i / sides).toFloat()
                val a1 = (2 * PI * (i + 1) / sides).toFloat()
                val n0 = Vec3(cos(a0), 0f, sin(a0))
                val n1 = Vec3(cos(a1), 0f, sin(a1))
                val p0 = m.vertex(Vec3(n0.x * r0, y0, n0.z * r0), n0, i / 8f, v)
                val p1 = m.vertex(Vec3(n1.x * r0, y0, n1.z * r0), n1, (i + 1) / 8f, v)
                val p2 = m.vertex(Vec3(n1.x * r1, y1, n1.z * r1), n1, (i + 1) / 8f, v)
                val p3 = m.vertex(Vec3(n0.x * r1, y1, n0.z * r1), n0, i / 8f, v)
                m.quad(p0, p3, p2, p1)
            }
        }
        fun cap(y: Float, r: Float, up: Boolean) {
            val n = if (up) Vec3.UP else Vec3(0f, -1f, 0f)
            val c = m.vertex(Vec3(0f, y, 0f), n, 0.5f, 0.05f)
            val ids = (0 until sides).map { i ->
                val a = (2 * PI * i / sides).toFloat()
                m.vertex(Vec3(cos(a) * r, y, sin(a) * r), n, 0.5f, 0.05f)
            }
            for (i in 0 until sides) if (up) m.tri(c, ids[(i + 1) % sides], ids[i]) else m.tri(c, ids[i], ids[(i + 1) % sides])
        }
        tube(SHOULDER_Y, 0.085f, TOP_Y, 0.058f, 0.06f)
        cap(TOP_Y, 0.058f, up = true)
        cap(TOP_Y + 0.012f, 0.07f, up = true)
        tube(TOP_Y, 0.07f, TOP_Y + 0.012f, 0.07f, 0.04f)
        // xuan: the collar the bell hangs by, centred on the origin
        tube(-0.02f, 0.098f, 0.02f, 0.098f, 0.02f)
        cap(0.02f, 0.098f, up = true)
        cap(-0.02f, 0.098f, up = false)
    }

    /** The 36 bosses (mei): rows at t = 0.12 / 0.26 / 0.40, columns at |x| / a = 0.44 / 0.62 / 0.80, both faces. */
    private fun bosses(m: MeshBuilder) {
        val rows = floatArrayOf(0.12f, 0.26f, 0.40f)
        val cols = floatArrayOf(0.44f, 0.62f, 0.80f)
        for (face in intArrayOf(1, -1)) for (side in intArrayOf(1, -1)) for (t in rows) for (c in cols) {
            // x / a = cos(phi) = side * c, the face picks the sign of sin(phi)
            val base = acos((side * c).toDouble()).toFloat()
            val phi = if (face > 0) base else -base
            boss(m, phi, t)
        }
    }

    private fun boss(m: MeshBuilder, phi: Float, t: Float) {
        val sides = 8
        val p = surface(phi, t)
        val n = normalAt(phi, t)
        var u = Vec3.UP.cross(n)
        if (u.length < 1e-3f) u = Vec3.RIGHT
        u = u.normalized()
        val w = n.cross(u).normalized()
        val r = 0.026f
        val length = 0.055f
        val tip = m.vertex(p + n * length, n, phi / (2 * PI.toFloat()).let { if (it < 0) it + 1f else it }, 0.15f + 0.85f * t)
        val ring = (0 until sides).map { i ->
            val a = (2 * PI * i / sides).toFloat()
            val dir = u * cos(a) + w * sin(a)
            val uu = (phi / (2 * PI.toFloat())).let { if (it < 0) it + 1f else it }
            m.vertex(p + dir * r - n * 0.004f, (dir + n * 0.6f).normalized(), uu, 0.15f + 0.85f * t)
        }
        for (i in 0 until sides) m.tri(tip, ring[i], ring[(i + 1) % sides])
    }

    /**
     * A highlight decal on the front of the bell (design `entities3d.strikeZone`): a patch of the body
     * surface 4 mm (unit) outside it, over t = 0.62 .. 0.92. [x0]..[x1] = x / a range on the front
     * face: the front drum -0.5 .. 0.5, a side drum 0.5 .. 1.0 (or -1.0 .. -0.5).
     */
    fun decal(x0: Float, x1: Float, name: String): MeshResource {
        val m = MeshBuilder()
        val cols = 10
        val rows = 5
        val phi0 = acos(x1.coerceIn(-1f, 1f).toDouble()).toFloat()
        val phi1 = acos(x0.coerceIn(-1f, 1f).toDouble()).toFloat()
        for (j in 0..rows) {
            val t = 0.62f + 0.30f * j / rows
            for (i in 0..cols) {
                val phi = phi0 + (phi1 - phi0) * i / cols
                m.vertex(surface(phi, t, out = 0.006f), normalAt(phi, t))
            }
        }
        for (j in 0 until rows) for (i in 0 until cols) {
            val a = j * (cols + 1) + i
            m.quad(a, a + 1, a + cols + 2, a + cols + 1)
        }
        return m.build(name)
    }

    /** The rack in rack coordinates (design `bells.layout.posts`): posts, bases and beams, one mesh. */
    fun rackLacquer(): MeshResource {
        val m = MeshBuilder()
        for (x in floatArrayOf(-1.80f, 1.80f)) {
            m.box(Vec3(x, Bells.RACK_HEIGHT / 2f, POST_Z), Vec3(0.12f, Bells.RACK_HEIGHT, 0.12f))
            m.box(Vec3(x, 0.04f, -0.20f), Vec3(0.60f, 0.08f, 0.30f))
        }
        for (y in floatArrayOf(Bells.LOWER_HOOK_Y, Bells.UPPER_HOOK_Y)) {
            m.box(Vec3(0f, y + 0.06f, BEAM_Z), Vec3(3.60f, 0.12f, 0.10f))
        }
        return m.build("rackLacquer")
    }

    /** The cinnabar band, 2 cm high, along the front of both beams. */
    fun rackBand(): MeshResource {
        val m = MeshBuilder()
        for (y in floatArrayOf(Bells.LOWER_HOOK_Y, Bells.UPPER_HOOK_Y)) {
            m.box(Vec3(0f, y + 0.06f, BEAM_Z + 0.052f), Vec3(3.56f, 0.02f, 0.006f))
        }
        return m.build("rackBand")
    }

    /** Two bronze hooks per bell, from the beam front to either side of the xuan ring. */
    fun rackHooks(): MeshResource {
        val m = MeshBuilder()
        for (b in Bells.all) {
            val off = 0.098f * b.widthM + 0.012f
            for (side in floatArrayOf(-1f, 1f)) {
                val beam = Vec3(b.xM + side * off, b.hookY + 0.03f, BEAM_Z + 0.05f)
                val bend = Vec3(b.xM + side * off, b.hookY + 0.03f, -0.02f)
                val end = Vec3(b.xM + side * off, b.hookY - 0.015f, 0f)
                m.bar(beam, bend, 0.018f)
                m.bar(bend, end, 0.018f)
            }
        }
        return m.build("rackHooks")
    }

    /** A centred unit quad facing +Z with texture coordinates (plates). */
    fun quadUv(name: String, x0: Float = -0.5f, x1: Float = 0.5f): MeshResource {
        val m = MeshBuilder()
        val n = Vec3(0f, 0f, 1f)
        // v = 1 on the top edge: the engine samples v = 0 at the bitmap's bottom row (0.1.0 had the plates upside down)
        val (a, b, c, d) = PlateFacing.corners(x0, x1).map { m.vertex(it.position, n, it.u, it.v) }
        m.quad(a, b, c, d)
        return m.build(name)
    }

    /**
     * A rectangle outline on the floor (XZ plane, facing +Y), centred: [w] x [d] metres, band [band];
     * with [dash] > 0 the band is broken into dash / gap pieces (masking-tape footprint, snap outline).
     */
    fun rectOutline(w: Float, d: Float, band: Float, dash: Float, gap: Float, name: String): MeshResource {
        val m = MeshBuilder()
        fun strip(a: Vec3, b: Vec3) {
            val dir = (b - a).normalized()
            val side = Vec3.UP.cross(dir).normalized() * (band / 2f)
            val i0 = m.vertex(a - side, Vec3.UP)
            val i1 = m.vertex(a + side, Vec3.UP)
            val i2 = m.vertex(b + side, Vec3.UP)
            val i3 = m.vertex(b - side, Vec3.UP)
            m.quad(i0, i1, i2, i3)
        }
        fun edge(a: Vec3, b: Vec3) {
            val len = a.distanceTo(b)
            if (dash <= 0f) {
                strip(a, b)
                return
            }
            val dir = (b - a) / len
            var s = 0f
            while (s < len) {
                val e = minOf(s + dash, len)
                strip(a + dir * s, a + dir * e)
                s += dash + gap
            }
        }
        val hw = w / 2f
        val hd = d / 2f
        val c = listOf(Vec3(-hw, 0f, -hd), Vec3(hw, 0f, -hd), Vec3(hw, 0f, hd), Vec3(-hw, 0f, hd))
        for (i in 0 until 4) edge(c[i], c[(i + 1) % 4])
        return m.build(name)
    }

    /** The body's centre in bell coordinates of a bell of height [h] (box collider, hover box). */
    fun boxCenterY(h: Float): Float = (MOUTH_Y + TOP_Y) / 2f * h

    const val POST_Z = -0.24f
    const val BEAM_Z = -0.25f
}
