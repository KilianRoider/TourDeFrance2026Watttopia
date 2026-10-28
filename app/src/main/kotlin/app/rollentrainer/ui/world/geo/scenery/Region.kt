package app.rollentrainer.ui.world.geo.scenery

import app.rollentrainer.core.geo.GeoMeta
import app.rollentrainer.core.geo.RoutePath
import app.rollentrainer.core.route.Route
import app.rollentrainer.ui.world.geo.RegionStyle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot

/*
 * Which landscape a place looks like. The tour crosses a dozen distinct landscapes in three weeks
 * (Catalan coast, Pyrenees, Landes pine plantations, Bordeaux vineyards, Massif Central, Burgundy
 * bocage, Jura and Vosges, the Alps, Paris), and stages often cross from one into the next, so the
 * generator classifies every tile from its own position and height; [forRoute] gives the
 * dominant style of a whole route (the SceneContext default, terrain textures, ambience).
 */
object RegionPicker {
    private val byRouteId = mapOf(
        "alpe-dhuez" to RegionStyle.ALPS, "galibier" to RegionStyle.ALPS, "izoard" to RegionStyle.ALPS,
        "madeleine" to RegionStyle.ALPS, "loze" to RegionStyle.ALPS, "joux-plane" to RegionStyle.ALPS,
        "ventoux" to RegionStyle.MEDITERRANEAN,
        "tourmalet" to RegionStyle.PYRENEES, "hautacam" to RegionStyle.PYRENEES, "portet" to RegionStyle.PYRENEES,
        "aubisque" to RegionStyle.PYRENEES, "luz-ardiden" to RegionStyle.PYRENEES, "peyresourde" to RegionStyle.PYRENEES,
        "marie-blanque" to RegionStyle.PYRENEES,
        "planche" to RegionStyle.JURA_VOSGES, "puy-de-dome" to RegionStyle.MASSIF_CENTRAL,
        "mur-de-bretagne" to RegionStyle.BRITTANY, "champs-elysees" to RegionStyle.PARIS,
    )

    /** Dominant style of each stage of the 2026 tour (the stage's main landscape). */
    private val byStage = mapOf(
        1 to RegionStyle.CATALONIA, 2 to RegionStyle.CATALONIA, 3 to RegionStyle.PYRENEES, 4 to RegionStyle.MEDITERRANEAN,
        5 to RegionStyle.SOUTH_WEST, 6 to RegionStyle.PYRENEES, 7 to RegionStyle.SOUTH_WEST, 8 to RegionStyle.SOUTH_WEST,
        9 to RegionStyle.MASSIF_CENTRAL, 10 to RegionStyle.MASSIF_CENTRAL, 11 to RegionStyle.CENTRAL, 12 to RegionStyle.BURGUNDY,
        13 to RegionStyle.JURA_VOSGES, 14 to RegionStyle.JURA_VOSGES, 15 to RegionStyle.ALPS, 16 to RegionStyle.ALPS,
        17 to RegionStyle.ALPS, 18 to RegionStyle.ALPS, 19 to RegionStyle.ALPS, 20 to RegionStyle.ALPS, 21 to RegionStyle.PARIS,
    )

    /**
     * The style for a route: built-in routes by id or stage number, any other route (a pack built
     * for a user route) from the position and median height of its road.
     */
    fun forRoute(route: Route, meta: GeoMeta, path: RoutePath? = null): RegionStyle {
        byRouteId[route.id]?.let { return it }
        route.stage?.number?.let { n -> byStage[n]?.let { return it } }
        val z = path?.let { p -> p.z.copyOf().also { it.sort() }[p.size / 2] } ?: route.points.map { it.elevationM }.sorted().let { it[it.size / 2] }
        return at(meta.projection.lat0, meta.projection.lon0, z)
    }

    /** Approximate inverse of the pack's transverse Mercator (good to ~50 m within 100 km; plenty for styles). */
    fun latLon(meta: GeoMeta, x: Double, y: Double): DoubleArray {
        val lat0 = meta.projection.lat0
        val lat = lat0 + y / 111_132.0
        val lon = meta.projection.lon0 + x / (111_320.0 * cos((lat0 + lat) * 0.5 * PI / 180.0))
        return doubleArrayOf(lat, lon)
    }

    private fun km(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dy = (lat1 - lat2) * 111.13
        val dx = (lon1 - lon2) * 111.32 * cos(lat1 * PI / 180.0)
        return hypot(dx, dy)
    }

    /** Style at a point (WGS84) with terrain height [z] (m). */
    fun at(lat: Double, lon: Double, z: Double): RegionStyle {
        if (km(lat, lon, 48.857, 2.347) < 13.0) return RegionStyle.PARIS
        // Spain: south of the Pyrenean main ridge (~42.45 N in Catalonia, ~42.8 N further west).
        val spainBorder = if (lon > 0.7) 42.45 else 42.8 + (0.7 - lon) * 0.15
        if (lat < spainBorder && lon > -2.0 && lon < 3.4) {
            return if (z > 1100) RegionStyle.PYRENEES else RegionStyle.CATALONIA
        }
        // Pyrenees and their foothills.
        if (lat < 43.3 && lon > -1.9 && lon < 2.9 && (z > 650 || lat < 42.95 || (lat < 43.15 && z > 380))) return RegionStyle.PYRENEES
        // Alps (incl. Haute-Provence, Savoie, Haute-Savoie, Chablais).
        if (lon > 5.55 && lat > 43.75 && lat < 46.45) {
            if (lat > 46.05 && lon < 6.2 && z < 900) return RegionStyle.JURA_VOSGES
            if (lat < 44.3 && z < 700 && lon < 6.3) return RegionStyle.MEDITERRANEAN
            return RegionStyle.ALPS
        }
        // Mediterranean France: Provence, Languedoc, Roussillon, Corbières.
        if (lat < 44.55 && lon > 1.95) {
            if (lat > 43.9 && lon < 4.1 && z > 550) return RegionStyle.MASSIF_CENTRAL
            if (lon < 2.6 && lat > 43.45) return RegionStyle.SOUTH_WEST
            return RegionStyle.MEDITERRANEAN
        }
        // Jura, Vosges, Alsace.
        if ((lat in 46.2..47.65 && lon in 5.35..7.3) || (lat in 47.35..49.1 && lon in 6.35..8.3)) return RegionStyle.JURA_VOSGES
        // Massif Central (uplands only; the valleys around it keep their lowland style).
        if (lat in 43.9..46.35 && lon in 1.55..4.7 && z > 420) return RegionStyle.MASSIF_CENTRAL
        // South-west: Gascony, Landes, Gironde, Périgord, Quercy.
        if (lat < 45.65 && lon < 1.95) return RegionStyle.SOUTH_WEST
        // Atlantic coast: Charente, Vendée, Poitou.
        if (lat < 47.25 && lon < 0.7) return RegionStyle.ATLANTIC
        // Brittany and Normandy: bocage, granite, slate.
        if (lon < -1.1 && lat > 47.25) return RegionStyle.BRITTANY
        if (lat > 48.35 && lon < 1.55 && lon > -2.0) return RegionStyle.BRITTANY
        // The north: Nord, Picardie, Champagne.
        if (lat > 49.25 || (lat > 48.55 && lon > 3.3)) return RegionStyle.NORTH
        // Burgundy, Saône-et-Loire, Nivernais east.
        if (lat in 46.15..48.1 && lon in 3.45..5.5) return RegionStyle.BURGUNDY
        // Bourbonnais, Berry, Limousin lowlands.
        if (lat in 45.6..47.4 && lon in 0.7..3.45) return RegionStyle.CENTRAL
        return RegionStyle.LOIRE
    }
}

/** Kinds of building looks the region decides between. */
internal enum class RoofKind { GABLED, HIPPED, FLAT, MANSARD, PYRAMIDAL, SKILLION, HALF_HIPPED }

/** Crop types for the fields beside the road (July). */
internal enum class Crop { WHEAT, MAIZE, SUNFLOWER, LAVENDER, PASTURE }

/**
 * The look of a region: which trees grow where, the tree line, hedges and walls, avenues, crops,
 * and how houses are built. Weights are relative.
 */
internal class RegionProfile(
    val style: RegionStyle,
    /** Forest species by altitude band: (top of the band in metres, weights). */
    val forest: List<Pair<Float, Map<Species, Float>>>,
    /** Single trees in fields, pastures, hedgerows (by altitude band). */
    val open: List<Pair<Float, Map<Species, Float>>>,
    val garden: Map<Species, Float>,
    /** Upper limit of closed forest (m); dwarf pines and rhododendrons above, then bare. */
    val treeline: Float,
    /** Roadside avenues: species and how often a suitable stretch gets one (0..1). */
    val avenue: Species?,
    val avenueChance: Float,
    /** Rows of poplars along rivers and field drains. */
    val poplarRows: Float,
    /** Bocage: hedgerows along field boundaries and roads (0..1). */
    val bocage: Float,
    /** Dry-stone walls along pastures (0..1). */
    val stoneWalls: Float,
    val vineRowM: Float,
    val orchardTree: Species,
    val crops: Map<Crop, Float>,
    /** Shrub look: 0 temperate (hazel, hawthorn), 1 garrigue/maquis, 2 gorse/broom. */
    val shrubKind: Int,
    // ---- buildings
    val houseFacades: Map<String, Float>,
    val townFacades: Map<String, Float>,
    val farmFacades: Map<String, Float>,
    val roofs: Map<String, Float>,
    val roofKinds: Map<RoofKind, Float>,
    val pitchDeg: Float,
    val pitchVar: Float,
    val eavesM: Float,
    /** Share of houses built as alpine chalets (wood upper floors, wide eaves). */
    val chalet: Float,
    val wallTint: Int,
    /** Rock texture for boulders and walls. */
    val rock: String,
    /** Sign conventions: "fr" or "es". */
    val signs: String,
)

internal object Regions {
    private fun w(vararg p: Pair<Species, Float>) = mapOf(*p)
    private fun f(vararg p: Pair<String, Float>) = mapOf(*p)

    private val profiles: Map<RegionStyle, RegionProfile> by lazy { build() }

    fun profile(style: RegionStyle): RegionProfile = profiles.getValue(style)

    private fun build(): Map<RegionStyle, RegionProfile> {
        val m = HashMap<RegionStyle, RegionProfile>()
        m[RegionStyle.CATALONIA] = RegionProfile(
            RegionStyle.CATALONIA,
            forest = listOf(400f to w(Species.ALEPPO_PINE to 4f, Species.HOLM_OAK to 3f, Species.STONE_PINE to 1.5f),
                1200f to w(Species.HOLM_OAK to 3f, Species.PINE to 2f, Species.OAK to 1f), 9999f to w(Species.PINE to 3f, Species.SPRUCE to 1f)),
            open = listOf(500f to w(Species.OLIVE to 3f, Species.STONE_PINE to 1.5f, Species.HOLM_OAK to 1.5f, Species.CYPRESS to 0.8f, Species.ALEPPO_PINE to 1f),
                9999f to w(Species.HOLM_OAK to 2f, Species.PINE to 1f, Species.OAK to 1f)),
            garden = w(Species.PALM to 2f, Species.CYPRESS to 1.2f, Species.OLIVE to 1f, Species.PLANE to 1.5f),
            treeline = 2250f, avenue = Species.PLANE, avenueChance = 0.12f, poplarRows = 0.15f, bocage = 0f, stoneWalls = 0.35f,
            vineRowM = 2.4f, orchardTree = Species.OLIVE,
            crops = mapOf(Crop.WHEAT to 3f, Crop.SUNFLOWER to 0.5f, Crop.PASTURE to 1f), shrubKind = 1,
            houseFacades = f("facade_plaster_cream" to 3f, "facade_plaster_ochre" to 2f, "facade_stone_village" to 1.5f, "facade_modern" to 1f,
                "facade_plaster_white" to 1f, "facade_plaster_pink" to 0.6f),
            townFacades = f("facade_eixample" to 3f, "facade_modern" to 2f, "facade_plaster_cream" to 1f),
            farmFacades = f("facade_stone_village" to 3f, "facade_plaster_ochre" to 1f),
            roofs = f("roof_roman_tiles" to 1f),
            roofKinds = mapOf(RoofKind.GABLED to 3f, RoofKind.HIPPED to 1.5f, RoofKind.FLAT to 2f),
            pitchDeg = 22f, pitchVar = 4f, eavesM = 0.35f, chalet = 0f, wallTint = 0xFFFFFF, rock = "rock_limestone", signs = "es",
        )
        m[RegionStyle.MEDITERRANEAN] = RegionProfile(
            RegionStyle.MEDITERRANEAN,
            forest = listOf(500f to w(Species.ALEPPO_PINE to 3f, Species.HOLM_OAK to 3f, Species.OAK to 1f),
                1100f to w(Species.OAK to 2f, Species.PINE to 2f, Species.BEECH to 1f, Species.HOLM_OAK to 1f),
                1600f to w(Species.BEECH to 2f, Species.PINE to 2f, Species.LARCH to 0.5f), 9999f to w(Species.PINE to 2f)),
            open = listOf(600f to w(Species.OLIVE to 2.5f, Species.CYPRESS to 1.5f, Species.PLANE to 1f, Species.HOLM_OAK to 1.5f, Species.ALEPPO_PINE to 1f, Species.STONE_PINE to 0.8f),
                9999f to w(Species.OAK to 2f, Species.PINE to 1f)),
            garden = w(Species.CYPRESS to 2f, Species.OLIVE to 1.5f, Species.PLANE to 1f, Species.PALM to 0.3f),
            treeline = 1650f, avenue = Species.PLANE, avenueChance = 0.3f, poplarRows = 0.2f, bocage = 0f, stoneWalls = 0.45f,
            vineRowM = 2.3f, orchardTree = Species.OLIVE,
            crops = mapOf(Crop.WHEAT to 2f, Crop.LAVENDER to 1.2f, Crop.SUNFLOWER to 0.8f, Crop.PASTURE to 0.6f), shrubKind = 1,
            houseFacades = f("facade_plaster_ochre" to 3f, "facade_plaster_cream" to 2f, "facade_stone_village" to 2f, "facade_plaster_pink" to 1.5f,
                "facade_plaster_white" to 0.6f),
            townFacades = f("facade_plaster_ochre" to 2f, "facade_plaster_cream" to 2f, "facade_stone_village" to 1f, "facade_plaster_pink" to 1.2f),
            farmFacades = f("facade_stone_village" to 3f, "facade_plaster_ochre" to 1f),
            roofs = f("roof_roman_tiles" to 1f),
            roofKinds = mapOf(RoofKind.GABLED to 4f, RoofKind.HIPPED to 1f),
            pitchDeg = 24f, pitchVar = 4f, eavesM = 0.3f, chalet = 0f, wallTint = 0xFFFFFF, rock = "rock_limestone", signs = "fr",
        )
        m[RegionStyle.PYRENEES] = RegionProfile(
            RegionStyle.PYRENEES,
            forest = listOf(700f to w(Species.OAK to 2f, Species.CHESTNUT to 2f, Species.BEECH to 1.5f),
                1500f to w(Species.BEECH to 4f, Species.SPRUCE to 1.5f, Species.OAK to 0.5f, Species.BIRCH to 0.5f),
                1900f to w(Species.SPRUCE to 2f, Species.PINE to 2.5f, Species.BEECH to 0.6f, Species.BIRCH to 0.4f),
                9999f to w(Species.PINE to 3f)),
            open = listOf(900f to w(Species.OAK to 2f, Species.CHESTNUT to 1f, Species.BIRCH to 0.5f, Species.APPLE to 0.4f),
                1700f to w(Species.BEECH to 1.5f, Species.BIRCH to 1f, Species.PINE to 1f), 9999f to w(Species.PINE to 2f, Species.MUGO to 1f)),
            garden = w(Species.APPLE to 1f, Species.BIRCH to 0.5f, Species.OAK to 0.5f),
            treeline = 2150f, avenue = Species.PLANE, avenueChance = 0.05f, poplarRows = 0.15f, bocage = 0.25f, stoneWalls = 0.5f,
            vineRowM = 2f, orchardTree = Species.APPLE,
            crops = mapOf(Crop.PASTURE to 4f, Crop.MAIZE to 1f, Crop.WHEAT to 0.5f), shrubKind = 0,
            houseFacades = f("facade_pyrenean" to 3f, "facade_plaster_cream" to 1.5f, "facade_stone_village" to 1f),
            townFacades = f("facade_pyrenean" to 2f, "facade_plaster_cream" to 2f),
            farmFacades = f("facade_barn" to 2f, "facade_pyrenean" to 2f),
            roofs = f("roof_slate" to 1f),
            roofKinds = mapOf(RoofKind.GABLED to 3f, RoofKind.HALF_HIPPED to 1f, RoofKind.HIPPED to 1f),
            pitchDeg = 42f, pitchVar = 6f, eavesM = 0.45f, chalet = 0.05f, wallTint = 0xFFFFFF, rock = "rock_granite", signs = "fr",
        )
        m[RegionStyle.SOUTH_WEST] = RegionProfile(
            RegionStyle.SOUTH_WEST,
            forest = listOf(250f to w(Species.MARITIME_PINE to 4f, Species.OAK to 2f, Species.CHESTNUT to 0.8f),
                9999f to w(Species.OAK to 3f, Species.CHESTNUT to 2f, Species.MARITIME_PINE to 0.5f)),
            open = listOf(9999f to w(Species.OAK to 3f, Species.PLANE to 0.8f, Species.POPLAR to 0.6f, Species.CHESTNUT to 0.6f)),
            garden = w(Species.PLANE to 1f, Species.APPLE to 0.6f, Species.CYPRESS to 0.4f, Species.OAK to 0.5f),
            treeline = 2000f, avenue = Species.PLANE, avenueChance = 0.22f, poplarRows = 0.5f, bocage = 0.15f, stoneWalls = 0.05f,
            vineRowM = 1.8f, orchardTree = Species.APPLE,
            crops = mapOf(Crop.MAIZE to 3f, Crop.SUNFLOWER to 1.5f, Crop.WHEAT to 1.5f, Crop.PASTURE to 1f), shrubKind = 0,
            houseFacades = f("facade_plaster_cream" to 3f, "facade_stone_village" to 2f, "facade_basque" to 0.4f),
            townFacades = f("facade_stone_village" to 2f, "facade_plaster_cream" to 2f),
            farmFacades = f("facade_barn" to 1f, "facade_stone_village" to 2f),
            roofs = f("roof_roman_tiles" to 3f, "roof_flat_tiles" to 1f),
            roofKinds = mapOf(RoofKind.GABLED to 3f, RoofKind.HIPPED to 2f),
            pitchDeg = 26f, pitchVar = 6f, eavesM = 0.35f, chalet = 0f, wallTint = 0xFFFFFF, rock = "rock_limestone", signs = "fr",
        )
        m[RegionStyle.ATLANTIC] = RegionProfile(
            RegionStyle.ATLANTIC,
            forest = listOf(9999f to w(Species.OAK to 3f, Species.MARITIME_PINE to 1.5f, Species.CHESTNUT to 1f)),
            open = listOf(9999f to w(Species.OAK to 3f, Species.POPLAR to 1f, Species.PLANE to 0.5f)),
            garden = w(Species.APPLE to 1f, Species.OAK to 0.5f, Species.CYPRESS to 0.4f),
            treeline = 2000f, avenue = Species.PLANE, avenueChance = 0.15f, poplarRows = 0.5f, bocage = 0.45f, stoneWalls = 0.05f,
            vineRowM = 2f, orchardTree = Species.APPLE,
            crops = mapOf(Crop.MAIZE to 2f, Crop.SUNFLOWER to 1.5f, Crop.WHEAT to 2f, Crop.PASTURE to 1f), shrubKind = 0,
            houseFacades = f("facade_plaster_cream" to 3f, "facade_plaster_white" to 3f, "facade_stone_village" to 1f),
            townFacades = f("facade_plaster_cream" to 2f, "facade_plaster_white" to 1.5f, "facade_stone_village" to 1f),
            farmFacades = f("facade_barn" to 1f, "facade_stone_village" to 2f),
            roofs = f("roof_roman_tiles" to 1f),
            roofKinds = mapOf(RoofKind.GABLED to 4f, RoofKind.HIPPED to 1f),
            pitchDeg = 22f, pitchVar = 4f, eavesM = 0.3f, chalet = 0f, wallTint = 0xFFFFFF, rock = "rock_limestone", signs = "fr",
        )
        m[RegionStyle.BRITTANY] = RegionProfile(
            RegionStyle.BRITTANY,
            forest = listOf(9999f to w(Species.OAK to 3f, Species.BEECH to 1.5f, Species.CHESTNUT to 1f, Species.PINE to 0.6f, Species.BIRCH to 0.4f)),
            open = listOf(9999f to w(Species.OAK to 4f, Species.CHESTNUT to 1f, Species.BEECH to 0.5f)),
            garden = w(Species.APPLE to 1.5f, Species.OAK to 0.4f, Species.BIRCH to 0.4f, Species.PALM to 0.15f),
            treeline = 2000f, avenue = Species.BEECH, avenueChance = 0.08f, poplarRows = 0.2f, bocage = 1f, stoneWalls = 0.3f,
            vineRowM = 2f, orchardTree = Species.APPLE,
            crops = mapOf(Crop.MAIZE to 2f, Crop.WHEAT to 1.5f, Crop.PASTURE to 2f), shrubKind = 2,
            houseFacades = f("facade_pyrenean" to 2f, "facade_plaster_cream" to 2f, "facade_plaster_white" to 1.5f, "facade_stone_village" to 1f),
            townFacades = f("facade_pyrenean" to 2f, "facade_plaster_cream" to 2f, "facade_plaster_white" to 0.8f),
            farmFacades = f("facade_pyrenean" to 2f, "facade_barn" to 1f),
            roofs = f("roof_slate" to 1f),
            roofKinds = mapOf(RoofKind.GABLED to 5f, RoofKind.HIPPED to 0.5f),
            pitchDeg = 45f, pitchVar = 5f, eavesM = 0.15f, chalet = 0f, wallTint = 0xFFFFFF, rock = "rock_granite", signs = "fr",
        )
        m[RegionStyle.LOIRE] = RegionProfile(
            RegionStyle.LOIRE,
            forest = listOf(9999f to w(Species.OAK to 4f, Species.CHESTNUT to 1f, Species.BEECH to 1f, Species.PINE to 0.8f, Species.BIRCH to 0.4f)),
            open = listOf(9999f to w(Species.OAK to 3f, Species.POPLAR to 1f, Species.PLANE to 0.6f)),
            garden = w(Species.APPLE to 1f, Species.PLANE to 0.5f, Species.BIRCH to 0.5f, Species.OAK to 0.5f),
            treeline = 2000f, avenue = Species.PLANE, avenueChance = 0.12f, poplarRows = 0.45f, bocage = 0.2f, stoneWalls = 0.05f,
            vineRowM = 1.6f, orchardTree = Species.APPLE,
            crops = mapOf(Crop.WHEAT to 4f, Crop.MAIZE to 1f, Crop.SUNFLOWER to 0.5f, Crop.PASTURE to 0.6f), shrubKind = 0,
            houseFacades = f("scn_facade_tuffeau" to 2f, "facade_plaster_cream" to 3f, "facade_stone_village" to 1.5f),
            townFacades = f("scn_facade_tuffeau" to 2f, "facade_plaster_cream" to 2f, "facade_haussmann" to 0.5f),
            farmFacades = f("facade_barn" to 1f, "facade_stone_village" to 2f),
            roofs = f("roof_slate" to 2f, "roof_flat_tiles" to 1.5f),
            roofKinds = mapOf(RoofKind.GABLED to 3f, RoofKind.HIPPED to 1.5f),
            pitchDeg = 45f, pitchVar = 5f, eavesM = 0.2f, chalet = 0f, wallTint = 0xFFFFFF, rock = "rock_limestone", signs = "fr",
        )
        m[RegionStyle.CENTRAL] = RegionProfile(
            RegionStyle.CENTRAL,
            forest = listOf(9999f to w(Species.OAK to 5f, Species.BEECH to 1f, Species.CHESTNUT to 0.8f, Species.PINE to 0.6f, Species.BIRCH to 0.4f)),
            open = listOf(9999f to w(Species.OAK to 5f, Species.POPLAR to 0.8f, Species.CHESTNUT to 0.4f)),
            garden = w(Species.APPLE to 1f, Species.OAK to 0.5f, Species.BIRCH to 0.4f, Species.PLANE to 0.4f),
            treeline = 2000f, avenue = Species.PLANE, avenueChance = 0.15f, poplarRows = 0.5f, bocage = 0.7f, stoneWalls = 0.1f,
            vineRowM = 1.6f, orchardTree = Species.APPLE,
            crops = mapOf(Crop.WHEAT to 3f, Crop.MAIZE to 1.5f, Crop.SUNFLOWER to 1f, Crop.PASTURE to 2f), shrubKind = 0,
            houseFacades = f("facade_stone_village" to 3f, "facade_plaster_cream" to 2f, "scn_facade_brick" to 0.5f),
            townFacades = f("facade_stone_village" to 2f, "facade_plaster_cream" to 2f),
            farmFacades = f("facade_barn" to 2f, "facade_stone_village" to 2f),
            roofs = f("roof_flat_tiles" to 3f, "roof_slate" to 1f),
            roofKinds = mapOf(RoofKind.GABLED to 3f, RoofKind.HIPPED to 1.5f),
            pitchDeg = 42f, pitchVar = 6f, eavesM = 0.25f, chalet = 0f, wallTint = 0xFFFFFF, rock = "rock_limestone", signs = "fr",
        )
        m[RegionStyle.BURGUNDY] = RegionProfile(
            RegionStyle.BURGUNDY,
            forest = listOf(450f to w(Species.OAK to 4f, Species.BEECH to 1.5f, Species.CHESTNUT to 0.5f, Species.BIRCH to 0.3f),
                9999f to w(Species.BEECH to 2f, Species.OAK to 2f, Species.SPRUCE to 1.5f)),
            open = listOf(9999f to w(Species.OAK to 5f, Species.POPLAR to 0.6f, Species.CHESTNUT to 0.3f)),
            garden = w(Species.APPLE to 1f, Species.OAK to 0.4f, Species.BIRCH to 0.4f, Species.PLANE to 0.4f),
            treeline = 2000f, avenue = Species.PLANE, avenueChance = 0.14f, poplarRows = 0.55f, bocage = 0.85f, stoneWalls = 0.2f,
            vineRowM = 1.15f, orchardTree = Species.APPLE,
            crops = mapOf(Crop.WHEAT to 3f, Crop.MAIZE to 1f, Crop.SUNFLOWER to 0.6f, Crop.PASTURE to 3f), shrubKind = 0,
            houseFacades = f("facade_stone_village" to 4f, "facade_plaster_cream" to 2f),
            townFacades = f("facade_stone_village" to 2f, "facade_plaster_cream" to 2f, "facade_plaster_ochre" to 0.5f),
            farmFacades = f("facade_barn" to 2f, "facade_stone_village" to 2f),
            roofs = f("roof_flat_tiles" to 4f, "roof_slate" to 0.6f),
            roofKinds = mapOf(RoofKind.GABLED to 4f, RoofKind.HIPPED to 1.2f),
            pitchDeg = 45f, pitchVar = 6f, eavesM = 0.25f, chalet = 0f, wallTint = 0xFFFFFF, rock = "rock_limestone", signs = "fr",
        )
        m[RegionStyle.MASSIF_CENTRAL] = RegionProfile(
            RegionStyle.MASSIF_CENTRAL,
            forest = listOf(700f to w(Species.OAK to 2f, Species.CHESTNUT to 2.5f, Species.BEECH to 1f, Species.BIRCH to 0.6f),
                1300f to w(Species.BEECH to 3f, Species.SPRUCE to 2f, Species.PINE to 1f, Species.BIRCH to 0.6f),
                9999f to w(Species.BEECH to 1f, Species.SPRUCE to 2f, Species.PINE to 1f)),
            open = listOf(900f to w(Species.OAK to 2f, Species.CHESTNUT to 1.5f, Species.BIRCH to 1f),
                9999f to w(Species.BEECH to 1f, Species.BIRCH to 1.5f, Species.SPRUCE to 0.8f, Species.PINE to 0.8f)),
            garden = w(Species.APPLE to 1f, Species.BIRCH to 0.6f, Species.OAK to 0.4f),
            treeline = 1550f, avenue = Species.BEECH, avenueChance = 0.06f, poplarRows = 0.2f, bocage = 0.55f, stoneWalls = 0.65f,
            vineRowM = 2f, orchardTree = Species.APPLE,
            crops = mapOf(Crop.PASTURE to 4f, Crop.WHEAT to 0.7f, Crop.MAIZE to 0.5f), shrubKind = 2,
            houseFacades = f("facade_pyrenean" to 3f, "facade_stone_village" to 1.5f, "facade_plaster_cream" to 1f),
            townFacades = f("facade_pyrenean" to 2f, "facade_plaster_cream" to 1.5f, "facade_stone_village" to 1f),
            farmFacades = f("facade_barn" to 2f, "facade_pyrenean" to 2f),
            roofs = f("scn_roof_lauze" to 1.5f, "roof_slate" to 2f, "roof_flat_tiles" to 0.6f),
            roofKinds = mapOf(RoofKind.GABLED to 3f, RoofKind.HIPPED to 1f, RoofKind.HALF_HIPPED to 0.5f),
            pitchDeg = 45f, pitchVar = 6f, eavesM = 0.25f, chalet = 0f, wallTint = 0xFFFFFF, rock = "rock_granite", signs = "fr",
        )
        m[RegionStyle.ALPS] = RegionProfile(
            RegionStyle.ALPS,
            forest = listOf(800f to w(Species.BEECH to 2f, Species.OAK to 1f, Species.SPRUCE to 1f, Species.PINE to 1f, Species.BIRCH to 0.4f),
                1500f to w(Species.SPRUCE to 4f, Species.BEECH to 1f, Species.LARCH to 1f, Species.PINE to 1.2f),
                1900f to w(Species.SPRUCE to 2f, Species.LARCH to 3f, Species.PINE to 1.2f),
                9999f to w(Species.LARCH to 3f, Species.PINE to 1.5f)),
            open = listOf(900f to w(Species.BEECH to 1f, Species.OAK to 1f, Species.BIRCH to 1f, Species.POPLAR to 0.5f, Species.APPLE to 0.4f),
                1700f to w(Species.SPRUCE to 2f, Species.LARCH to 1.5f, Species.BIRCH to 0.6f),
                9999f to w(Species.LARCH to 2f, Species.PINE to 1f, Species.MUGO to 1f)),
            garden = w(Species.BIRCH to 1f, Species.SPRUCE to 0.6f, Species.APPLE to 0.8f),
            treeline = 2050f, avenue = null, avenueChance = 0f, poplarRows = 0.15f, bocage = 0.05f, stoneWalls = 0.3f,
            vineRowM = 1.8f, orchardTree = Species.APPLE,
            crops = mapOf(Crop.PASTURE to 5f, Crop.MAIZE to 0.4f), shrubKind = 0,
            houseFacades = f("facade_stone_village" to 2f, "facade_plaster_cream" to 2f, "facade_pyrenean" to 1f),
            townFacades = f("facade_plaster_cream" to 2f, "facade_stone_village" to 1.5f, "facade_plaster_ochre" to 0.6f),
            farmFacades = f("facade_barn" to 2f, "facade_stone_village" to 1f),
            roofs = f("roof_slate" to 2f, "scn_roof_metal" to 1.5f, "scn_roof_lauze" to 0.8f),
            roofKinds = mapOf(RoofKind.GABLED to 5f, RoofKind.HIPPED to 0.6f),
            pitchDeg = 30f, pitchVar = 6f, eavesM = 0.9f, chalet = 0.45f, wallTint = 0xFFFFFF, rock = "rock_limestone", signs = "fr",
        )
        m[RegionStyle.JURA_VOSGES] = RegionProfile(
            RegionStyle.JURA_VOSGES,
            forest = listOf(600f to w(Species.BEECH to 3f, Species.OAK to 2f, Species.SPRUCE to 1f),
                1100f to w(Species.SPRUCE to 4f, Species.BEECH to 2.5f),
                9999f to w(Species.SPRUCE to 3f, Species.BEECH to 1f)),
            open = listOf(800f to w(Species.OAK to 2f, Species.BEECH to 1f, Species.APPLE to 0.6f, Species.BIRCH to 0.4f),
                9999f to w(Species.SPRUCE to 3f, Species.BEECH to 1f)),
            garden = w(Species.APPLE to 1.2f, Species.BIRCH to 0.5f, Species.SPRUCE to 0.5f),
            treeline = 1300f, avenue = Species.PLANE, avenueChance = 0.05f, poplarRows = 0.2f, bocage = 0.15f, stoneWalls = 0.35f,
            vineRowM = 1.4f, orchardTree = Species.APPLE,
            crops = mapOf(Crop.PASTURE to 4f, Crop.WHEAT to 1f, Crop.MAIZE to 0.6f), shrubKind = 0,
            houseFacades = f("facade_plaster_cream" to 3f, "scn_facade_halftimber" to 1f, "facade_stone_village" to 1f),
            townFacades = f("facade_plaster_cream" to 2f, "scn_facade_halftimber" to 1f, "facade_plaster_ochre" to 0.6f),
            farmFacades = f("facade_barn" to 1f, "facade_plaster_cream" to 1f),
            roofs = f("roof_flat_tiles" to 3f, "scn_roof_metal" to 0.8f, "roof_slate" to 0.4f),
            roofKinds = mapOf(RoofKind.GABLED to 3f, RoofKind.HALF_HIPPED to 1.2f, RoofKind.HIPPED to 0.6f),
            pitchDeg = 42f, pitchVar = 8f, eavesM = 0.5f, chalet = 0.08f, wallTint = 0xFFFFFF, rock = "rock_limestone", signs = "fr",
        )
        m[RegionStyle.NORTH] = RegionProfile(
            RegionStyle.NORTH,
            forest = listOf(9999f to w(Species.OAK to 3f, Species.BEECH to 2f, Species.BIRCH to 0.6f, Species.POPLAR to 0.4f)),
            open = listOf(9999f to w(Species.POPLAR to 2f, Species.OAK to 2f, Species.BIRCH to 0.4f)),
            garden = w(Species.APPLE to 1f, Species.BIRCH to 0.6f, Species.OAK to 0.4f),
            treeline = 2000f, avenue = Species.PLANE, avenueChance = 0.06f, poplarRows = 0.7f, bocage = 0.3f, stoneWalls = 0f,
            vineRowM = 1.2f, orchardTree = Species.APPLE,
            crops = mapOf(Crop.WHEAT to 4f, Crop.MAIZE to 1f, Crop.PASTURE to 1f), shrubKind = 0,
            houseFacades = f("scn_facade_brick" to 5f, "facade_plaster_cream" to 1f),
            townFacades = f("scn_facade_brick" to 4f, "facade_plaster_cream" to 1f),
            farmFacades = f("scn_facade_brick" to 2f, "facade_barn" to 1f),
            roofs = f("roof_slate" to 2f, "roof_flat_tiles" to 1.5f),
            roofKinds = mapOf(RoofKind.GABLED to 5f, RoofKind.HIPPED to 0.5f),
            pitchDeg = 45f, pitchVar = 5f, eavesM = 0.15f, chalet = 0f, wallTint = 0xFFFFFF, rock = "rock_limestone", signs = "fr",
        )
        m[RegionStyle.PARIS] = RegionProfile(
            RegionStyle.PARIS,
            forest = listOf(9999f to w(Species.OAK to 3f, Species.CHESTNUT to 1.5f, Species.BEECH to 1f, Species.BIRCH to 0.5f)),
            open = listOf(9999f to w(Species.PLANE to 3f, Species.OAK to 1f, Species.CHESTNUT to 1f)),
            garden = w(Species.PLANE to 1.5f, Species.CHESTNUT to 1f, Species.BIRCH to 0.4f),
            treeline = 2000f, avenue = Species.PLANE, avenueChance = 0.4f, poplarRows = 0.1f, bocage = 0f, stoneWalls = 0f,
            vineRowM = 1.2f, orchardTree = Species.APPLE,
            crops = mapOf(Crop.WHEAT to 3f), shrubKind = 0,
            houseFacades = f("facade_plaster_cream" to 2f, "facade_haussmann" to 1f, "facade_modern" to 1f),
            townFacades = f("facade_haussmann" to 5f, "facade_modern" to 0.6f),
            farmFacades = f("facade_stone_village" to 1f),
            roofs = f("roof_zinc" to 4f, "roof_slate" to 1f),
            roofKinds = mapOf(RoofKind.MANSARD to 4f, RoofKind.FLAT to 1f, RoofKind.HIPPED to 1f),
            pitchDeg = 40f, pitchVar = 5f, eavesM = 0.2f, chalet = 0f, wallTint = 0xFFFFFF, rock = "rock_limestone", signs = "fr",
        )
        return m
    }
}
