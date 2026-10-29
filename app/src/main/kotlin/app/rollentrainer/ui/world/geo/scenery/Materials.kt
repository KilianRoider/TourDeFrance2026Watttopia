package app.rollentrainer.ui.world.geo.scenery

import app.rollentrainer.ui.world.geo.Anim
import app.rollentrainer.ui.world.geo.SceneMaterial

/*
 * Materials of the scenery. All UVs are baked to texture space by the generator (uvMetres = false):
 * the facade and roof meshes know the real size of their textures (see [TexSize]) because the
 * window rhythm and storey height must line up with the geometry, which the renderer cannot know.
 * Texture coordinates use v = 0 at the image's top row (a Bitmap uploaded without flipping) and
 * GL_REPEAT wrapping.
 */
internal object Mats {
    private val cache = HashMap<String, SceneMaterial>()

    @Synchronized
    private fun get(key: String, make: () -> SceneMaterial): SceneMaterial = cache.getOrPut(key, make)

    /** Tree and plant atlases: alpha-tested cards, both sides, swaying in the wind. */
    fun foliage(atlas: String): SceneMaterial = get(atlas) {
        SceneMaterial(key = atlas, texture = atlas, alphaCutout = true, doubleSided = true, roughness = 0.82f, anim = Anim.WIND)
    }

    /**
     * Opaque textured surfaces of buildings (tiling facade / roof textures). One material per
     * texture: the renderer gives a texture to the materials that exist when it arrives, so a second
     * key for the same texture would stay untextured. Its roughness is therefore the texture's
     * ([SURFACE_ROUGHNESS]), not the caller's, or the first tile generated would decide it for all.
     */
    fun surface(texture: String, roughness: Float = 0.85f): SceneMaterial = get("scn_s:$texture") {
        SceneMaterial(key = "scn_s:$texture", texture = texture, roughness = SURFACE_ROUGHNESS[texture] ?: roughness)
    }

    /** Roofs, which the buildings ask for with several roughnesses (main roof, mansard, hipped top, spire). */
    private val SURFACE_ROUGHNESS = mapOf(
        "roof_roman_tiles" to 0.8f, "roof_slate" to 0.8f, "roof_flat_tiles" to 0.8f, "scn_roof_lauze" to 0.8f,
        "scn_roof_gravel" to 0.8f, "roof_zinc" to 0.55f, "scn_roof_metal" to 0.55f,
    )

    /** Vertex-coloured parts without a texture (eaves, chimneys, posts, poles, lamps). */
    val plain: SceneMaterial = SceneMaterial(key = "scn_plain", roughness = 0.75f)

    /** Painted metal (lamp posts, sign posts, guard rails): smoother. */
    val metal: SceneMaterial = SceneMaterial(key = "scn_metal", roughness = 0.35f)

    /** Road signs and markers: the scn_signs atlas, alpha-tested so round and triangular plates keep their shape. */
    val signs: SceneMaterial = SceneMaterial(key = "scn_signs", texture = "scn_signs", alphaCutout = true, roughness = 0.4f)

    /** Rocks and boulders: a tiling rock texture with baked UVs. */
    fun rock(texture: String): SceneMaterial = get("scn_rock:$texture") {
        SceneMaterial(key = "scn_rock:$texture", texture = texture, roughness = 0.9f)
    }

    /**
     * A town entry (EB10) or exit (EB20) sign panel whose face shows [name]. The renderer draws the
     * texture itself (see the W2 report): key "sign_town:<style>:<in|out>:<name>", UV (0,0)–(1,1)
     * over the panel face, aspect [TOWN_SIGN_ASPECT].
     */
    fun townSign(style: String, entry: Boolean, name: String): SceneMaterial =
        get("sign_town:$style:${if (entry) "in" else "out"}:$name") {
            SceneMaterial(key = "sign_town:$style:${if (entry) "in" else "out"}:$name", texture = null, roughness = 0.4f, castsShadow = true)
        }

    const val TOWN_SIGN_ASPECT = 2.6f
}

/**
 * Real-world sizes (width × height, metres) of the tiling textures the buildings use, and the
 * facade rhythm: storey height and window bays. Mirrors scratchpad/textures/meta/<name>.json
 * (package T) and scratchpad/w2/tex/meta (W2's scn_* textures).
 */
internal object TexSize {
    class Facade(
        val name: String,
        val widthM: Float,
        val storeyM: Float,
        /** Window bays per texture width (windows centred in each bay). */
        val bays: Int,
        /** A u range of the texture without windows (short walls, gables). */
        val blankU0: Float,
        val blankU1: Float,
    ) {
        val bayM: Float get() = widthM / bays
    }

    val facades: Map<String, Facade> = listOf(
        Facade("facade_haussmann", 6f, 3.4f, 2, 0.46f, 0.54f),
        Facade("facade_chalet", 6f, 3.0f, 2, 0.46f, 0.54f),
        Facade("facade_plaster_cream", 6f, 3.0f, 2, 0.44f, 0.56f),
        Facade("facade_plaster_ochre", 6f, 3.0f, 2, 0.44f, 0.56f),
        Facade("facade_plaster_pink", 6f, 3.0f, 2, 0.44f, 0.56f),
        Facade("facade_plaster_white", 6f, 3.0f, 2, 0.44f, 0.56f),
        // Ground floors facing the road: window in the first bay, front door in the second.
        Facade("facade_plaster_cream_door", 6f, 3.0f, 2, 0.44f, 0.56f),
        Facade("facade_plaster_white_door", 6f, 3.0f, 2, 0.44f, 0.56f),
        Facade("facade_stone_village_door", 6f, 3.0f, 2, 0.44f, 0.56f),
        Facade("facade_stone_village", 6f, 3.0f, 2, 0.44f, 0.56f),
        // Blank strip: the stone between the vent window (x ≈ 0.4–0.8 m) and the door (from 1.5 m).
        Facade("facade_barn", 6f, 3.0f, 1, 0.155f, 0.235f),
        Facade("facade_basque", 6f, 3.0f, 2, 0.47f, 0.53f),
        Facade("facade_eixample", 6f, 3.3f, 2, 0.46f, 0.54f),
        Facade("facade_modern", 6f, 3.0f, 2, 0.48f, 0.52f),
        Facade("facade_pyrenean", 6f, 3.0f, 2, 0.44f, 0.56f),
        Facade("facade_shop", 6f, 3.5f, 1, 0.92f, 0.99f),
        Facade("facade_church", 4f, 8.0f, 1, 0.02f, 0.18f),
        Facade("scn_facade_brick", 6f, 3.0f, 2, 0.44f, 0.56f),
        Facade("scn_facade_industrial", 6f, 3.0f, 1, 0.1f, 0.9f),
        Facade("scn_facade_halftimber", 6f, 3.0f, 2, 0.46f, 0.54f),
        Facade("scn_facade_tuffeau", 6f, 3.0f, 2, 0.44f, 0.56f),
    ).associateBy { it.name }

    /** Roof textures: size of one tile (u along the eaves, v down the slope). */
    val roofs: Map<String, Float> = mapOf(
        "roof_roman_tiles" to 3f,
        "roof_slate" to 3f,
        "roof_flat_tiles" to 2.5f,
        "roof_zinc" to 3f,
        "scn_roof_metal" to 3f,
        "scn_roof_lauze" to 3f,
        "scn_roof_gravel" to 4f,
    )

    fun roof(name: String): Float = roofs[name] ?: 3f
    fun facade(name: String): Facade = facades[name] ?: facades.getValue("facade_plaster_cream")
}
