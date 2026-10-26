package io.embodit.bianzhong.ui.chime.scene

import android.content.res.Resources
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.HoverEffectComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.TextureResource
import io.embodit.bianzhong.domain.model.BellSpec
import io.embodit.bianzhong.domain.model.Bells
import io.embodit.bianzhong.domain.model.Quat4
import io.embodit.bianzhong.domain.model.Vec3
import io.embodit.bianzhong.domain.usecase.RackPose
import io.embodit.bianzhong.platform.ChimeLog
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2

/** Meshes shared by the rack, its ghost, the plates, ripples and outlines (made once, two per frame). */
internal class ChimeMeshes(
    val bell: MeshResource,
    val decalZheng: MeshResource,
    val decalCeRight: MeshResource,
    val decalCeLeft: MeshResource,
    val lacquer: MeshResource,
    val band: MeshResource,
    val hooks: MeshResource,
    val plate: MeshResource,
    val halfLeft: MeshResource,
    val halfRight: MeshResource,
    val ring: MeshResource,
    val footDashed: MeshResource,
    val footSolid: MeshResource,
    val outlineDashed: MeshResource,
    val outlineSolid: MeshResource,
)

/** One bell of the rack: its pivot at the hanging point (collider, hover effect), body, decals. */
internal class BellNode(val spec: BellSpec, val pivot: Entity, val body: Entity, val decals: List<Entity>) {
    /** Last opacity written per decal (0 = hidden). */
    val decalOpacity = FloatArray(3)
}

/** Decal slots of a bell: 0 = front drum, 1 = right side drum (+x), 2 = left side drum (-x). */
internal object DecalSlot {
    const val ZHENG = 0
    const val CE_RIGHT = 1
    const val CE_LEFT = 2
}

private fun Entity.addModel(mesh: MeshResource, material: Material, name: String): Entity {
    val e = ModelEntity(mesh, material).named(name)
    addChild(e)
    return e
}

/**
 * The hung rack (design `entities3d.rack`, `bell`, `strikeZone`, plates): lacquer posts and beams,
 * cinnabar band, bronze hooks, twelve bells hanging at 20 degrees, three highlight decals per bell, a
 * name plate under every mouth. Everything hangs below [root] in rack coordinates; the rack's pose and
 * scale are the root's transform.
 */
internal class RackView(parent: Entity, private val materials: SceneMaterials, private val meshes: ChimeMeshes) {
    val root: Entity = Entity().named("rack")
    val bells = ArrayList<BellNode>()
    private val plates = ArrayList<Entity>()
    private val plateHalves = ArrayList<Pair<Entity, Entity>>()
    private val plateHalfOpacity = Array(12) { FloatArray(2) }
    private val plateMaterials = arrayOfNulls<Material>(12)
    private val plateTextures = arrayOfNulls<TextureResource>(12)
    private val plateShown = BooleanArray(12)
    private val byPivot = HashMap<Long, Int>()
    private var shown = true

    init {
        parent.addChild(root)
        root.addModel(meshes.lacquer, materials.lacquer, "rack:lacquer")
        root.addModel(meshes.band, materials.cinnabar, "rack:band")
        root.addModel(meshes.hooks, materials.hook, "rack:hooks")
    }

    /** Built: all twelve bells and plates are in (the scene adds them one per paced step). */
    val complete: Boolean
        get() = bells.size == Bells.all.size

    /** Adds the next bell (pivot, body, decals, collider) and its plate; the startup builds these one per step. */
    fun addBell() {
        val spec = Bells.all.getOrNull(bells.size) ?: return
        val pivot = Entity().named("bell:${spec.index}")
        root.addChild(pivot)
        pivot.setLocal(position = Vec3(spec.xM, spec.hookY, 0f), rotation = Bells.lean())
        val scale = Vec3(spec.widthM, spec.heightM, spec.depthM)
        val body = pivot.addModel(meshes.bell, materials.bronze, "bell:${spec.index}:body")
        body.setLocal(scale = scale)
        val decals =
            listOf(meshes.decalZheng, meshes.decalCeRight, meshes.decalCeLeft).mapIndexed { k, mesh ->
                pivot.addModel(mesh, materials.decal, "bell:${spec.index}:decal$k").also {
                    it.setLocal(scale = scale)
                    it.enabled = false
                }
            }
        // the whole bell takes the pointer: a box around body and shank (design `bells.strikeZones`)
        val size = Vec3(maxOf(spec.widthM, 0.08f), maxOf(spec.heightM, 0.08f), maxOf(spec.depthM, 0.08f))
        pivot.tapBox(size, Vec3(0f, BellMeshes.boxCenterY(spec.heightM), 0f))
        runCatching { pivot.components.set(HoverEffectComponent()) }.onFailure { ChimeLog.w("HOVER effect failed: $it") }
        byPivot[pivot.id] = spec.index
        bells += BellNode(spec, pivot, body, decals)
        val plate = ModelEntity(meshes.plate, materials.plateGold).named("plate:${spec.index}")
        root.addChild(plate)
        val w = plateWidth(spec)
        plate.setLocal(scale = Vec3(w, w * PLATE_ASPECT, 1f))
        val left = plate.addModel(meshes.halfLeft, materials.plateGold, "plate:${spec.index}:l")
        val right = plate.addModel(meshes.halfRight, materials.plateGold, "plate:${spec.index}:r")
        left.setLocal(position = Vec3(0f, 0f, 0.002f))
        right.setLocal(position = Vec3(0f, 0f, 0.002f))
        left.enabled = false
        right.enabled = false
        plate.enabled = false
        plates += plate
        plateHalves += left to right
    }

    /** Bell index of a tapped entity (the pivot carries the collider). */
    fun bellOf(entity: Entity): Int? = byPivot[entity.id]

    fun setShown(value: Boolean) {
        if (value == shown) return
        shown = value
        root.enabled = value
    }

    fun place(pose: RackPose) {
        root.setLocal(position = pose.origin, rotation = pose.rotation, scale = Vec3(pose.scale, pose.scale, pose.scale))
    }

    fun swing(i: Int, rad: Float) {
        bells[i].pivot.setLocal(rotation = Bells.lean(rad))
    }

    fun decal(i: Int, slot: Int, opacity: Float) {
        val node = bells[i]
        val o = if (opacity < 0.02f) 0f else opacity
        if (abs(node.decalOpacity[slot] - o) < 0.01f) return
        val e = node.decals[slot]
        if (o == 0f) {
            e.enabled = false
        } else {
            if (node.decalOpacity[slot] == 0f) e.enabled = true
            e.setOpacity(o)
        }
        node.decalOpacity[slot] = o
    }

    /** Language the plate [i] texture was drawn for (null = not yet). */
    private val plateLanguage = arrayOfNulls<Boolean>(12)

    /** Indices of the plates whose texture is missing or in the other language. */
    fun platesToDraw(english: Boolean): List<Int> = plates.indices.filter { plateLanguage[it] != english }

    /** Draws plate [i]'s texture (Chinese: degree + note, English: note only); one per paced step. */
    fun buildPlate(i: Int, resources: Resources, english: Boolean) {
        if (i !in plates.indices || plateLanguage[i] == english) return
        val spec = Bells.all[i]
        val tex = runCatching { TextureResource.create(BellArt.plate(resources, spec, english)) }.onFailure { ChimeLog.e("PLATE texture failed: $it", it) }.getOrNull() ?: return
        val mat = materials.textured(tex)
        plates[i].setMaterial(mat)
        runCatching { plateMaterials[i]?.close() }
        runCatching { plateTextures[i]?.close() }
        plateMaterials[i] = mat
        plateTextures[i] = tex
        plateLanguage[i] = english
        if (i == plates.size - 1) ChimeLog.i("PLATES built english=$english")
    }

    /** Places plate [i] (rack coordinates) turned about the vertical towards [headLocal]; hides it when not [visible]. */
    fun plate(i: Int, visible: Boolean, center: Vec3, headLocal: Vec3?) {
        val e = plates[i]
        if (visible != plateShown[i]) {
            plateShown[i] = visible
            e.enabled = visible
        }
        if (!visible) return
        val yaw = if (headLocal == null) 0f else atan2(headLocal.x - center.x, headLocal.z - center.z)
        e.setLocal(position = center, rotation = Quat4.axisAngle(Vec3.UP, yaw))
    }

    /** Gold over the plate's left (front drum) / right (side drum) half. */
    fun plateGold(i: Int, left: Float, right: Float) {
        val (l, r) = plateHalves[i]
        fun set(e: Entity, k: Int, o: Float) {
            val v = if (o < 0.02f) 0f else o
            if (abs(plateHalfOpacity[i][k] - v) < 0.01f) return
            if (v == 0f) e.enabled = false
            else {
                if (plateHalfOpacity[i][k] == 0f) e.enabled = true
                e.setOpacity(v)
            }
            plateHalfOpacity[i][k] = v
        }
        set(l, 0, left)
        set(r, 1, right)
    }

    fun destroy() {
        runCatching { root.destroy(true) }
        for (k in 0 until 12) {
            runCatching { plateMaterials[k]?.close() }
            runCatching { plateTextures[k]?.close() }
        }
    }

    companion object {
        const val PLATE_ASPECT = 0.28f
        const val PLATE_DROP_M = 0.06f

        fun plateWidth(spec: BellSpec): Float = maxOf(0.22f * spec.heightM, 0.09f)

        /** Plate centre in rack coordinates: 6 cm below the mouth centre, following the swing. */
        fun plateCenter(spec: BellSpec, swingRad: Float): Vec3 {
            val mouth = Bells.toRack(spec, Vec3(0f, BellMeshes.MOUTH_Y * spec.heightM, 0f), swingRad)
            return mouth - Vec3(0f, PLATE_DROP_M + plateWidth(spec) * PLATE_ASPECT / 2f, 0f)
        }

        /**
         * A point on the bell's surface near a hit point given in bell coordinates (metres), with its
         * outward normal (bell coordinates): ripples sit on the face they hit.
         */
        fun surfacePoint(spec: BellSpec, local: Vec3): Pair<Vec3, Vec3> {
            val unit = Vec3(local.x / spec.widthM, local.y / spec.heightM, local.z / spec.depthM)
            val t = ((BellMeshes.SHOULDER_Y - unit.y) / Bells.BODY_SHARE).coerceIn(0f, 1f)
            val a = BellMeshes.halfWidth(t)
            var phi = acos((unit.x / a).coerceIn(-1f, 1f).toDouble()).toFloat()
            if (unit.z < 0f) phi = -phi
            return scaled(spec, phi, t)
        }

        /** The centre of a zone on the front face: front drum at x = 0, side drum at x / a = +0.75 (right). */
        fun zonePoint(spec: BellSpec, ce: Boolean, left: Boolean = false): Pair<Vec3, Vec3> {
            val xa = if (!ce) 0f else if (left) -0.75f else 0.75f
            val phi = acos(xa.toDouble()).toFloat()
            return scaled(spec, phi, 0.77f)
        }

        private fun scaled(spec: BellSpec, phi: Float, t: Float): Pair<Vec3, Vec3> {
            val p = BellMeshes.surface(phi, t)
            val n = BellMeshes.normalAt(phi, t)
            val point = Vec3(p.x * spec.widthM, p.y * spec.heightM, p.z * spec.depthM)
            val normal = Vec3(n.x / spec.widthM, n.y / spec.heightM, n.z / spec.depthM).normalized()
            return point to normal
        }
    }
}

/** The placement ghost (design `entities3d.ghostRack`): the rack and its bells see-through, the taped footprint. */
internal class GhostView(parent: Entity, private val materials: SceneMaterials, private val meshes: ChimeMeshes) {
    enum class Tone { NORMAL, BRIGHT, RED }

    val root: Entity = Entity().named("ghost")
    private val parts = ArrayList<Entity>()
    private val foot: Entity
    private val footSolid: Entity
    private var tone: Tone? = null
    private var shown = true
    private var solid = false

    init {
        parent.addChild(root)
        parts += root.addModel(meshes.lacquer, materials.ghost, "ghost:lacquer")
        parts += root.addModel(meshes.hooks, materials.ghost, "ghost:hooks")
        foot = root.addModel(meshes.footDashed, materials.tape, "ghost:foot")
        footSolid = root.addModel(meshes.footSolid, materials.tape, "ghost:footSolid")
        val c = Bells.FOOT_CENTER + Vec3(0f, 0.004f, 0f)
        foot.setLocal(position = c)
        footSolid.setLocal(position = c)
        footSolid.enabled = false
        setTone(Tone.NORMAL)
    }

    private var bellsAdded = 0

    val complete: Boolean
        get() = bellsAdded == Bells.all.size

    /** Adds the next see-through bell (one per paced step at startup). */
    fun addBell() {
        val spec = Bells.all.getOrNull(bellsAdded) ?: return
        val pivot = Entity().named("ghost:pivot:${spec.index}")
        root.addChild(pivot)
        pivot.setLocal(position = Vec3(spec.xM, spec.hookY, 0f), rotation = Bells.lean())
        val m =
            when (tone) {
                Tone.BRIGHT -> materials.ghostBright
                Tone.RED -> materials.ghostRed
                else -> materials.ghost
            }
        parts += pivot.addModel(meshes.bell, m, "ghost:bell:${spec.index}").also { it.setLocal(scale = Vec3(spec.widthM, spec.heightM, spec.depthM)) }
        bellsAdded++
    }

    fun setShown(value: Boolean) {
        if (value == shown) return
        shown = value
        root.enabled = value
    }

    fun place(pose: RackPose) {
        root.setLocal(position = pose.origin, rotation = pose.rotation, scale = Vec3(pose.scale, pose.scale, pose.scale))
    }

    fun setTone(value: Tone) {
        if (value == tone) return
        tone = value
        val m =
            when (value) {
                Tone.NORMAL -> materials.ghost
                Tone.BRIGHT -> materials.ghostBright
                Tone.RED -> materials.ghostRed
            }
        for (p in parts) p.setMaterial(m)
        val t = if (value == Tone.RED) materials.tapeRed else materials.tape
        foot.setMaterial(t)
        footSolid.setMaterial(t)
    }

    /** Snapped: the footprint tape turns solid. */
    fun setSolid(value: Boolean) {
        if (value == solid) return
        solid = value
        foot.enabled = !value
        footSolid.enabled = value
    }

    fun destroy() {
        runCatching { root.destroy(true) }
    }
}

/** The snap outlines in front of the walls (design `entities3d.snapOutline`, at most three). */
internal class SnapOutlines(parent: Entity, materials: SceneMaterials, meshes: ChimeMeshes) {
    private class Slot(val dashed: Entity, val solid: Entity) {
        var shown = false
        var solidOn = false
        var opacity = -1f
    }

    private val slots: List<Slot> =
        (0 until 3).map { k ->
            val d = ModelEntity(meshes.outlineDashed, materials.outline).named("outline:$k")
            val s = ModelEntity(meshes.outlineSolid, materials.outline).named("outline:$k:solid")
            parent.addChild(d)
            parent.addChild(s)
            d.enabled = false
            s.enabled = false
            Slot(d, s)
        }

    /** Shows outline [k] at a footprint centre / heading / scale; [solid] when the ghost is snapped to it. */
    fun show(k: Int, center: Vec3, yaw: Float, scale: Float, opacity: Float, solid: Boolean) {
        val slot = slots.getOrNull(k) ?: return
        val pos = center + Vec3(0f, 0.004f, 0f)
        val rot = Quat4.axisAngle(Vec3.UP, yaw)
        val sc = Vec3(scale, 1f, scale)
        slot.dashed.setLocal(position = pos, rotation = rot, scale = sc)
        slot.solid.setLocal(position = pos, rotation = rot, scale = sc)
        if (!slot.shown || slot.solidOn != solid) {
            slot.dashed.enabled = !solid
            slot.solid.enabled = solid
            slot.shown = true
            slot.solidOn = solid
        }
        val o = if (solid) 1f else opacity
        if (abs(o - slot.opacity) > 0.01f) {
            slot.opacity = o
            slot.dashed.setOpacity(o)
            slot.solid.setOpacity(o)
        }
    }

    fun hide(k: Int) {
        val slot = slots.getOrNull(k) ?: return
        if (!slot.shown) return
        slot.shown = false
        slot.dashed.enabled = false
        slot.solid.enabled = false
    }

    fun hideAll() {
        for (k in slots.indices) hide(k)
    }

    fun destroy() {
        for (s in slots) {
            runCatching { s.dashed.destroy(true) }
            runCatching { s.solid.destroy(true) }
        }
    }
}

/**
 * Gold strike ripples (design `entities3d.ripple`): a pool of rings in rack coordinates. Six would do for
 * play; twelve keep the whole done-wave on screen when the debug hook freezes it.
 */
internal class Ripples(parent: Entity, materials: SceneMaterials, mesh: MeshResource) {
    private class Ring(val entity: Entity) {
        var startMs = Long.MIN_VALUE
        var bell = 0
        var local = Vec3.ZERO
        var normal = Vec3(0f, 0f, 1f)
        var on = false
    }

    private val rings: List<Ring> =
        (0 until 12).map { k ->
            val e = ModelEntity(mesh, materials.ripple).named("ripple:$k")
            parent.addChild(e)
            e.enabled = false
            Ring(e)
        }
    private var next = 0

    fun fire(bell: Int, local: Vec3, normal: Vec3, nowMs: Long) {
        val r = rings[next]
        next = (next + 1) % rings.size
        r.startMs = nowMs
        r.bell = bell
        r.local = local
        r.normal = normal
        if (!r.on) {
            r.on = true
            r.entity.enabled = true
        }
    }

    /** Per frame: grows the rings 0.2 -> 1.2 x the bell's width over 0.4 s, fading 0.6 -> 0. */
    fun frame(nowMs: Long, freeze: Boolean, swing: (Int) -> Float) {
        for (r in rings) {
            if (!r.on) continue
            var q = (nowMs - r.startMs) / DURATION_MS
            if (freeze) q = q.coerceAtMost(0.35f)
            if (q >= 1f) {
                r.on = false
                r.entity.enabled = false
                continue
            }
            val spec = Bells.all[r.bell]
            val s = swing(r.bell)
            val p = Bells.toRack(spec, r.local + r.normal * 0.004f, s)
            val n = Bells.lean(s).rotate(r.normal)
            val d = spec.widthM * (0.2f + 1.0f * Ease.outCubic(q))
            r.entity.setLocal(position = p, rotation = Quat4.fromTo(Vec3.UP, n), scale = Vec3(d, d, d))
            r.entity.setOpacity(0.6f * (1f - q))
        }
    }

    fun clear() {
        for (r in rings) if (r.on) {
            r.on = false
            r.entity.enabled = false
        }
    }

    fun destroy() {
        for (r in rings) runCatching { r.entity.destroy(true) }
    }

    companion object {
        private const val DURATION_MS = 400f
    }
}
