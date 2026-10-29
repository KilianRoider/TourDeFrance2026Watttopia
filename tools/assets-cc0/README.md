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
