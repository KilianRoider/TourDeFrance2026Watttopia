# Geodata pipeline

Rebuilds the built-in routes (Tour 2026 stages `tour26-01` … `tour26-21`, the climbs of
`Routes.kt`) from real geodata: the real road with its bends and hairpins, a corrected elevation
profile and the landscape around the road, written as one streaming pack per route
(`<id>.wwgeo`, see [FORMAT.md](FORMAT.md)) plus a small profile resource for the app.

```
waypoints/<id>.json ─► route.py   road path on the Overture road graph (5 m samples)
                     ─► profile.py road profile from Copernicus GLO-30, forest removed with the
                                   Meta/WRI canopy height map (canopy.py), bridges/tunnels aware
                     ─► layers.py  terrain (carved road bed), land cover, Sentinel-2 colour,
                                   buildings, roads, water, places, in LOD tiles
                     ─► pack.py    <id>.wwgeo + <id>.profile.bin + <id>.report.json
```

## Setup

Python 3.11+ with

```
pip install pyarrow numpy scipy shapely pyproj rasterio networkx Pillow matplotlib
```

Network: anonymous S3/HTTPS to `overturemaps-us-west-2` (Overture GeoParquet),
`copernicus-dem-30m.s3.amazonaws.com`, `esa-worldcover.s3.eu-central-1.amazonaws.com` and
`esa-worldcover-s2.s3.eu-central-1.amazonaws.com`. `HTTPS_PROXY` is honoured.

## Run

```
# one or more routes (waypoints from tools/geodata/waypoints/<id>.json)
python3 -I tools/geodata/build.py alpe-dhuez tour26-12 --preview

# every route with a waypoint file, and copy the validated profiles into core/src/main/resources
python3 -I tools/geodata/build.py --all --install-profiles

# only path + profile (no world tiles), e.g. while tuning waypoints
python3 -I tools/geodata/build.py tour26-12 --skip-world

# waypoint files from elsewhere (searched before tools/geodata/waypoints)
python3 -I tools/geodata/build.py tour26-05 --waypoints /path/to/waypoints

# previews of an existing pack, decoded from the pack itself
python3 -I tools/geodata/preview.py alpe-dhuez

# fixture for the Kotlin reader test (core/src/test/resources/geo/fixture.wwgeo)
python3 -I tools/geodata/make_test_pack.py

# checks against published facts; install a profile only if its gate passed; SUMMARY.md
python3 -I tools/geodata/validate.py tour26-12
python3 -I tools/geodata/validate.py --install tour26-12
python3 -I tools/geodata/validate.py --summary

# landmark signs on the real road (also run by build.py and validate.py --install)
python3 -I tools/geodata/landmarks.py tour26-12
```

The recommended way to install profiles is `validate.py --install` after a look at the previews:
its gate checks summits, climb grades, detours and artefacts, not just the length.

`--install-profiles` copies `<id>.profile.bin` to
`core/src/main/resources/app/rollentrainer/core/geo/profiles/<id>.bin` only when the routed length is
within `--max-length-error` (default 2 %) of the official length (`--force` overrides). `Routes.all`
and `Stages.all` pick these up through `RealProfiles.apply`; routes without a resource keep their
hand-made profile.

Directories (environment variables):

| variable | default | content |
|---|---|---|
| `WWGEO_WORK` | `build/geodata` | parent of cache and out |
| `WWGEO_CACHE` | `$WWGEO_WORK/cache` | Overture cells (`overture/<release>/<theme>/<cx>_<cy>.parquet`, 0.1°), raster COG blocks (`raster/<source>/<tile>/ov<k>/<bx>_<by>.npy`) |
| `WWGEO_OUT` | `$WWGEO_WORK/out` | packs, reports, previews |
| `WWGEO_WAYPOINTS` | – | extra waypoint directory |
| `WWGEO_OVERTURE_RELEASE` | `2026-10-21.0` | Overture release |

Everything fetched is cached, so a second run of a route needs no network. A full run of all 39
routes needs roughly 5–8 GB of cache (mostly Sentinel-2 blocks). Overture is read through a
row-group index per theme (`overture/<release>/<theme>.rgindex.npz`, built once from the parquet
footers in ~30 s), so a build stays below ~1.5 GB of memory.

## Waypoint files

`waypoints/<id>.json`: `{"id", "lengthKm", "waypoints": [{"km", "lat", "lon", "kind", "road", "name"}]}`
(a bare list works too). `kind` decides how exactly the route must pass:

* `start`, `finish` (and the first/last point): exactly there (the nearest road is split).
* `via`, `junction`, `turn`, `roundabout`: within 40–60 m.
* `summit`, `pass`, `climb`: within 120–150 m.
* `town`, `village`, `place`, `city`, `sprint`: anywhere within 350–700 m, preferring main roads.
  A town centroid therefore does not drag the route into back streets.
* Soft waypoints get a graph node on the nearest one or two roads within their radius (main roads
  first, both carriageways of a divided road), so a waypoint on a long segment without nearby
  junctions (an expressway, a col road) is passed on that road and not on a bridge across it.
* `radius` overrides the distance. `road` naming `track`, `path`, `footway`, `cycleway`,
  `pedestrian` or `service` (or kind `gravel`/`cycleway`) unlocks that road class within 2.5 km of
  the waypoints that name it (gravel finishes, cycle paths like the Col de la Loze or the Voie Verte
  du Haag); elsewhere on the route the class keeps its normal cost, so one car-free kilometre does
  not open every footpath and car park of a 180 km stage.

Routing ignores one-way rules, never uses motorways, footways, steps or paths unless unlocked (a
waypoint whose `road` names `motorway` unlocks an expressway Overture classes as one, like the C-17
tunnels on stage 3), and
prefers primary/secondary/tertiary roads over residential and service roads. Legs whose routed
length differs by more than 5 % from the waypoints' km difference are listed in the report; that is
the signal to add a waypoint.

## Elevation profile

GLO-30 is a surface model (canopy, buildings, bridges included) with 30 m pixels. The profile is
fitted every 10 m to a ±30 m cross-section (hillside slope removed, lower quartile kept) with an
asymmetric smoothing fit: samples above the profile count 8 % (canopy, buildings), bridges and
tunnels count nothing, hairpin apexes and hairpin stacks are smoothed more, a gradient cap of 22 %.
On steep hillsides (gorges, roads cut into a cliff) a few metres of lateral error become several
metres of height, so samples there count less and the curvature penalty grows: such roads are
engineered with an even grade.

Forest: radar reads part of the canopy, so GLO-30 steps up by 5–15 m where a road enters a wood and
down where it leaves. On a rolling stage those steps were short fake ramps of 8–12 % (stage 12 had 52
100 m sections over 7 %, 62 % of them next to a wood, which covers 17 % of the route). `canopy.py`
reads the Meta/WRI 1 m canopy height map (S3 `dataforgood-fb-data`, CC BY 4.0) along the road,
averaged to ~9.6 m cells and cached in squares of ~2.4 km (`cache/raster/chm`, ~10 MB for a stage);
every observation is lowered by 0.8 × the mean canopy height within ±45 m (`CANOPY_SHARE`, least
squares at the edges of the woods along stage 12). Stage 12 then has 31 such sections, a steepest
100 m of 11.5 % instead of 14.1 % and 1754 m of ascent instead of 1852 m (published ~1800 m); on
climbs the foot in a wood comes down by a few metres. The first build of a stage spends ~3 min on
the canopy tiles (the map has one deflated strip per row and no overviews, so whole rows are read).

Smoothing and grade limits: the curvature penalty works on a ~200 m wavelength (about a 100 m moving
average; at 110 m the DEM's few metres of noise made 100 m grades 2–4 points steeper than published).
Each road class has a gradient cap (motorway 10 %, trunk 12 %, primary 14 %, secondary 16 %, tertiary
20 %, anything else 24 %, never above the app's 25 %), and a climb is capped at its published steepest
100 m + 1.5 points (`published_max.json`, climbfinder.com, or the steepest published km of `Routes.kt`
if that is higher): a steeper stretch in the surface model is a gully or a cliff beside the road, and
its height is spread over a longer ramp. The observation grid is evenly spaced to the exact end, and
the profile resource has no last segment shorter than 10 m (a 2 m stub read as a 39 % "ramp" at the
Col de la Loze in the app).

Passing the same road twice (laps, a stretch ridden twice, a crossing at grade): stretches of the fit
far apart along the route gave the same spot two heights (5 m apart where stage 14 comes back onto
the D431 at the Col du Haag), so the carved verge of one pass sat 1.5 m above the other. Every
observation within 6 m of another part of the route (more than 200 m away along it, bridges and
tunnels excepted) is now tied to the interpolated point of every other pass there
(`profile.route_ties`, weight 100; ties to the nearest pass only left the eight laps of stage 21 up
to 2 m apart): stages 2, 14, 20 and 21 and the Champs-Élysées lap agree to 0.3 m there.

The coarse world tiles of a coastal stage reach beyond the last DEM tile into the open sea; such a
box reads as nodata (sea level) on the grid of the nearest tile instead of failing the build
(stage 2 along the Garraf coast).

**Grade gate** (`build.py`, `report.json` → `gradeGate`, also enforced by `validate.py`): the steepest
100 m (sliding every 10 m) must stay ≤ the published maximum + 2 points and below 25 %, and no segment
of the 20 m profile resource may reach 25 %. A stage has no single published maximum: its entry in
`published_max.json` lists climbs instead (foot and summit waypoint by name, steepest published
100 m, source), and only those stretches are capped and gated (`gradeGate.climbs`). A stage climb
that is also a climb route (Tourmalet on stage 6, Alpe d'Huez on 19, Galibier on 20) uses that
route's figure. Other stage roads get the class caps and the 25 % limit. `capSections` caps a
stretch where the surface model is known to be wrong and nothing is published (the Souloise gorge
rim beside the road on stage 19) with an estimate from the heights either side; it is not gated.

## Validation (report.json)

* legs: routed vs expected km, total vs official length;
* start/finish/max elevation, ascent (2 m hysteresis), per-km grades;
* for stages: seed places (elevation at their km) and climbs (summit elevation, average grade);
* `hairpins`: detected turns > 140°.

`validate.py <id>` checks a built route against published facts and writes `<id>.check.json`:
length vs official (gate 2 % for stages, 3 % for climbs), summits whose signed elevation is in the
waypoint name (`"Col d'Aspin (1489 m)"`, gate 25 m), climbs vs `Routes.kt` (start, summit, average
within 0.6 points, per-km grades), seed climbs and ascent for stages, legs much longer than their km
difference (detours), the path doubling back within 3 km (a spur ridden in and out; also a short one
of 20-200 m, the path running the other way at the second pass: a waypoint on a roundabout arm or a
bridge ramp; `DOUBLE_BACK_OK` lists the real ones with their km range), 100 m grades
over 20 % and spikes against a 300 m running median. `validate.py --install <id>` copies the profile
into the core resources only when the gate passed; `validate.py --summary` writes `SUMMARY.md`.

`--preview` writes `<id>.map.png` (imagery, buildings, water, roads, path), `<id>.detail.png`,
`<id>.relief.png` (carved terrain, 10 m contours) and `<id>.profile.png` (new profile vs the old one).

Terrain carving (`layers.RoadBed`): the road bed is flat across the road plus a 1.5 m shoulder, with
cut/fill slopes of 0.75 beyond. In the 10 m height grid the flat bed reaches at least 7.5 m from the
centre line, so the four grid points around any point of the road lie on it and the grid returns the
road's height there (with the narrow bed, corners on the cut slope of a 15 % ramp put the grid 1 m
off the road: Col de la Loze). The corridor strips, from which the app builds the verge, keep the
narrow bed.

## Runtimes and sizes (4 cores; table from milestone 1, cold cache)

| route | length | route | profile | world | total cold | total warm | pack | peak RSS |
|---|---|---|---|---|---|---|---|---|
| alpe-dhuez | 14.0 km | 20 s | 0.2 s | 45 s | 65 s | 9 s | 1.7 MB | 0.34 GB |
| tour26-12 | 185.6 km | 42 s | 6 s | 385 s | 433 s | 204 s | 12.8 MB | ~5.6 GB |

Path and profile alone (`--skip-world`) take 2–10 s per route once the Overture cells are cached
(a stage's first build adds 1–5 min of canopy squares, cached afterwards). The figures above are
from milestone 1. Milestone 2 (all 39 routes, one at a time on 4 cores, shared machine): a climb
builds in 8–35 s warm, a stage in 1–5 min warm and 5–11 min with a cold canopy and Overture cache;
a stage peaks at ~1.0–1.3 GB. `--all` still runs one subprocess per route, one after the other.

## Delivery to the app

Packs are too large for git (≈ 1–3 MB per climb, ≈ 8–20 MB per stage, ≈ 300 MB in all). Naming
contract between this pipeline, the release and the app:

* **One file per route**, named exactly `<id>.wwgeo` with the route id of `Routes.kt` / `Stages.kt`
  (`alpe-dhuez.wwgeo`, `tour26-12.wwgeo`, ...). No version or date in the name: the pack's META
  carries `pipeline`, `built` and the Overture release.
* **Release assets**: the same files, uploaded flat (no archive) to one GitHub release, e.g. tag
  `geodata-v1`: `gh release upload geodata-v1 <out>/*.wwgeo --clobber`. A rebuilt route replaces
  its asset; a new pipeline version that changes the format gets a new tag.
* **In the APK**: `app/src/main/assets/geo/<id>.wwgeo`. CI downloads them before `assembleDebug`:
  `gh release download geodata-v1 -p '*.wwgeo' -D app/src/main/assets/geo --clobber`.
* **Stored uncompressed** (`androidResources { noCompress += "wwgeo" }`): the app opens a pack with
  `AssetManager.openFd("geo/<id>.wwgeo")` and reads it through
  `FileByteSource(File(apkPath), fd.startOffset, fd.length)` (random access into the APK) or copies it
  once to `filesDir`. Chunks inside are already deflated or JPEG, so APK compression would gain nothing.
* A route without a pack still rides: the profile resources
  (`core/src/main/resources/app/rollentrainer/core/geo/profiles/<id>.bin`, a few KB each) stay in git,
  so the trainer profile is right even in local builds without the packs; the renderer falls back to
  its synthetic world.
* `core` tests check every pack in `app/src/main/assets/geo` (or `WWGEO_PACK_DIR`) when present.

## Licences

Overture Maps (ODbL, © OpenStreetMap contributors; CDLA Permissive 2.0), Copernicus DEM GLO-30
(© DLR e.V. 2010-2014 and © Airbus Defence and Space GmbH 2014-2018, provided under COPERNICUS by the
European Union and ESA), ESA WorldCover 2021 and its Sentinel-2 composite (CC BY 4.0, contains
modified Copernicus Sentinel data 2021), Meta and WRI High Resolution Canopy Height Maps 2024 (CC BY
4.0). The exact strings are in every pack's META `attribution`.
