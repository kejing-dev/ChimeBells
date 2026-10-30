package io.embodit.bianzhong.ui.chime.scene

/** Ids and dp sizes of every attachment panel (design `panels`); the Compose side declares exactly these, once. */
object PanelSpec {
    /** The control panel in its short form (start, placing, free play): 400 x 168. */
    const val CONTROL = "controlPanel"

    /** The control panel in Follow (song row and action row added): 400 x 264. */
    const val FOLLOW = "followPanel"
    const val MENU = "menuPanel"
    const val DIALOG = "dialogPanel"
    const val SNACK = "snackPanel"

    val CONTROL_SIZE = 400f to 168f
    val FOLLOW_SIZE = 400f to 264f
    val MENU_SIZE = 288f to 400f
    val DIALOG_SIZE = 400f to 236f
    val SNACK_SIZE = 560f to 56f

    /** The control family is shown at 0.75 (1 dp = 0.6 mm). */
    const val MAIN_SCALE = 0.75f

    /** Dialog 0.9 m ahead (10 degrees down), snackbar 0.9 m away 14 degrees up: both at 1.125. */
    const val FLOATING_SCALE = 1.125f
    const val DIALOG_DISTANCE_M = 0.9f

    /** The dialog sits 10 degrees below the level gaze (v0.1.1, taste profile D10; 0.1.0 had it at eye level). */
    const val DIALOG_DOWN_DEG = 10f
    val DIALOG_COS = kotlin.math.cos(Math.toRadians(DIALOG_DOWN_DEG.toDouble())).toFloat()
    val DIALOG_SIN = kotlin.math.sin(Math.toRadians(DIALOG_DOWN_DEG.toDouble())).toFloat()

    val pool: List<String> = listOf(CONTROL, FOLLOW, MENU, DIALOG, SNACK)

    fun size(id: String): Pair<Float, Float> =
        when (id) {
            CONTROL -> CONTROL_SIZE
            FOLLOW -> FOLLOW_SIZE
            MENU -> MENU_SIZE
            DIALOG -> DIALOG_SIZE
            else -> SNACK_SIZE
        }

    /** Half width / height of a panel in metres at [scale]. */
    fun halfM(id: String, scale: Float): Pair<Float, Float> {
        val (w, h) = size(id)
        return (w * PanelRig.DP_M * scale / 2f) to (h * PanelRig.DP_M * scale / 2f)
    }
}
