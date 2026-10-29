# CC0 assets (raw)

Source material for the renderer, downloaded from [Poly Haven](https://polyhaven.com) (CC0 1.0, no attribution
required). Nothing here is wired into the app yet; `manifest.json` lists every file with its source page, authors
and licence, and maps each texture to the app material name used in
`app/src/main/kotlin/app/rollentrainer/ui/world/geo/Materials.kt`.

- `textures/<app_material>/` 2k JPG: `diff` (sRGB albedo), `nor_gl` (OpenGL normal), `rough`, `ao`, `disp`.
  The app wants `<name>.webp` + `_n` + `_m` (R AO, G roughness, B height), so these need repacking.
  Facade/roof sets are surface materials only (no windows or doors).
- `hdri/` four partly cloudy alpine/hill skies at 2k and 4k (Radiance .hdr).
- `models/` glTF 2.0 with 1k textures. Broadleaf trees are simplified with gltfpack (`_mobile` ~100k tris,
  `_far` fewer); shrubs and grass are the originals. Conifers are not included: simplification strips their needle
  cards, so they need impostors baked from the originals (see manifest notes).
- Not covered: `cereal_green`, `wheat_ripe`, `maize_rows` (no Poly Haven equivalent; ambientCG downloads are blocked).
- `preview/` contact sheet of the texture candidates and a render of the simplified trees.

## Ground cover and road (2026-10-29)

- `tufts.py` renders Poly Haven's scanned plant models (grass_medium_01, grass_medium_02, grass_bermuda_01,
  dandelion_01, weed_plant_02; 2k glTF plus their `Alpha` and `dry_diff` maps) into
  `app/src/main/assets/tex/scn_meadow.webp` (4 x 4 tufts, see `MeadowAtlas` in Plants.kt).
  Preview: `preview/grasbueschel-atlas.jpg`.
- `grass_meadow` = ambientCG [Grass004](https://ambientcg.com/view?id=Grass004), `grass_alpine` = ambientCG
  [Grass003](https://ambientcg.com/view?id=Grass003) (CC0). Candidates: `preview/gras-quellen.jpg`.
- `asphalt_fresh` = ambientCG [Asphalt015](https://ambientcg.com/view?id=Asphalt015), `asphalt_polish` = ambientCG
  [Asphalt010](https://ambientcg.com/view?id=Asphalt010) (CC0). Candidates: `preview/asphalt-quellen.jpg`.
  Converted with `convert.py` (ambientCG 2K-JPG zips unpacked into one folder per asset id).
