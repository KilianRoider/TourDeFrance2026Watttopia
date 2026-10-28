# `.wwgeo` route pack, version 1

One file per built-in route (`<route id>.wwgeo`), written by `tools/geodata/build.py`, read by
`app.rollentrainer.core.geo.GeoPack` (Kotlin) and `tools/geodata/wwgeo_read.py` (Python).
Designed for streaming on a phone: a small directory up front, every tile an independent chunk that
can be read with one ranged read and decoded on its own.

All numbers are **little-endian**. Strings are UTF-8.

## Coordinates

* Every route has its own **transverse Mercator** projection (WGS84 ellipsoid, scale 1) centred on
  `lat0`/`lon0` (centre of the waypoints' bounding box). Coordinates are metres:
  **x = east, y = north**, elevation **z** in metres above sea level (EGM2008 geoid, as GLO-30).
  A renderer with y up maps `(x, y, z)` to `(x, z, -y)`.
* Scale error is below 1e-4 within 90 km of the centre, so distances measured in the plane equal
  real distances to 0.01 %.
* Tiles store their contents relative to the tile's **south-west corner** (`tx * tileM`,
  `ty * tileM`), so floats keep centimetre precision anywhere on a 200 km stage.

## File layout

| offset | size | content |
|---|---|---|
| 0 | 64 | header |
| 64 | … | chunks, back to back |
| `dirOffset` | 32 × `chunkCount` | directory, sorted by (type, lod, tx, ty) |

### Header (64 bytes)

| offset | type | field |
|---|---|---|
| 0 | 8 bytes | magic `57 57 47 45 4F 00 00 01` (`WWGEO\0\0\1`) |
| 8 | u16 | version = 1 |
| 10 | u16 | flags = 0 |
| 12 | u32 | chunkCount |
| 16 | u64 | dirOffset |
| 24 | u32 | dirLength (= 32 × chunkCount) |
| 28 | u32 | reserved |
| 32 | f64 | lat0 (projection centre, also in META) |
| 40 | f64 | lon0 |
| 48 | 16 bytes | zero |

### Directory entry (32 bytes)

| type | field |
|---|---|
| u16 | chunk type |
| u8 | lod |
| u8 | codec: 0 raw, 1 zlib (deflate with zlib header), 2 JPEG, 3 PNG, 4 WebP |
| i32 | tx |
| i32 | ty |
| u64 | offset of the stored bytes |
| u32 | stored length |
| u32 | decoded length (for zlib: inflated size) |
| u32 | CRC-32 of the stored bytes |

Non-tile chunks use lod = tx = ty = 0.

## Chunk types

| type | name | codec | content |
|---|---|---|---|
| 1 | META | zlib | JSON, see below |
| 2 | PATH | zlib | road centre line every 5 m |
| 3 | PLACES | zlib | JSON: named places and town stretches |
| 4 | CORRIDOR | zlib | carved terrain cross-sections beside the road, every path sample (tx = section) |
| 10 | HEIGHT | zlib | terrain heights of a tile |
| 11 | LANDCOVER | zlib | land cover classes of a tile |
| 12 | IMAGE | JPEG | satellite colour of a tile |
| 20 | BUILDINGS | zlib | building footprints (LOD 0 only) |
| 21 | ROADS | zlib | other roads and railways (LOD 0 only) |
| 22 | WATER | zlib | water areas and lines (areas at all LODs, lines at LOD 0) |

## LODs and tiles

Tile `(tx, ty)` of a LOD covers `x ∈ [tx·T, (tx+1)·T)`, `y ∈ [ty·T, (ty+1)·T)`. A LOD-1 tile is
exactly 4 × 4 LOD-0 tiles, a LOD-2 tile 4 × 4 LOD-1 tiles. Tiles exist where the square comes within
`reach` of the road.

| lod | tile T | heights | land cover | imagery | reach | vectors |
|---|---|---|---|---|---|---|
| 0 | 1280 m | 129² samples, 10 m, road bed carved | 128², 10 m | 128², 10 m/px | 1.2 km | buildings (≤ 350 m from the road), roads, water |
| 1 | 5120 m | 129², 40 m | 128², 40 m | 256², 20 m/px | 7 km | water areas ≥ 2000 m² |
| 2 | 20480 m | 129², 160 m | – | 256², 80 m/px | 30 km | water areas ≥ 0.05 km² |

All rasters are **row-major with row 0 at the north edge** (like an image). Height grids have
`grid + 1` samples per side, sample `(c, r)` at `(x0 + c·T/grid, y0 + T − r·T/grid)`; the edge rows and
columns repeat the neighbour's, so meshes join without cracks at the same LOD. Land cover cell and
image pixel `(c, r)` have their centre at `(x0 + (c + ½)·T/n, y0 + T − (r + ½)·T/n)`.

The renderer is expected to use LOD 0 within ~1 km of the rider, LOD 1 beyond, LOD 2 for the horizon,
with skirts (or a slight drop) where rings of different LOD meet.

## PATH

```
u32 n            samples (every `step` m; the last one at `length`)
f32 step         5.0
f64 length       metres
i32 x0, y0       first position, centimetres
i32 z0           first road elevation, millimetres
i16[n-1] dx      deltas of x in cm
i16[n-1] dy      deltas of y in cm
i16[n-1] dz      deltas of z in mm
u8[n] width      road width in decimetres
u8[n] flags      1 bridge, 2 tunnel, 4 unpaved, 8 cobbles, 16 link/ramp, 32 roundabout
u8[n] class      road class code (below)
```

`z` is the corrected road profile (not the raw surface model), i.e. the elevation the trainer uses.
Sample `i` lies at distance `min(i·step, length)`.

Road class codes: 0 unknown, 1 motorway, 2 trunk, 3 primary, 4 secondary, 5 tertiary,
6 unclassified, 7 residential, 8 living_street, 9 service, 10 pedestrian, 11 cycleway, 12 track,
13 footway, 14 path, 15 steps, 16 bridleway, 20 railway.

## CORRIDOR

The 10 m terrain grid cannot show a 6 m road bed with its verges and embankments, so the pack also
has the carved terrain on cross-sections at every path sample (5 m), left and right of the road.
One chunk per section of `sectionSamples` (2048, i.e. 10.24 km) path samples, `tx` = section index:

```
u32 firstSample
u32 count
u8  k                    number of offsets (9)
f32[k] offsets           metres beyond the road edge: 0, 1.5, 3, 6, 10, 15, 22, 30, 40
count × {
  u8 freeLeft            how many offsets on the left are clear of other parts of the route
  u8 freeRight
  i16[k] left            terrain height relative to the road elevation z, dm
  i16[k] right
}
```

Left is left of the direction of travel; the point of offset `o` on the left of sample `i` is
`p_i + n_i · (width_i / 2 + o)` with `n_i` the left normal. Offset 0 is the road edge (≈ −0.15 m: the
bed under the road). At a hairpin the cross-section of one leg runs into the next leg; `free…` tells
the renderer where to stop the verge so the two legs do not overlap. Typical use: build a road +
verge + embankment mesh from these (5 m resolution) and push the terrain grid down under it.

## HEIGHT

```
u16 cols, rows   grid + 1
f32 base         metres
f32 step         metres per unit (0.1 at LOD 0/1, 0.25 at LOD 2)
u16[rows*cols]   residuals, row-major from the north-west corner
```

Height = `base + q·step` with `q` a u16. The stored values are the residuals of the predictor
`left + up − upleft` (0 outside the grid), taken modulo 2¹⁶. Decoding is therefore a 2D prefix sum:
`q[r][c] = (res[r][c] + q[r][c−1] + q[r−1][c] − q[r−1][c−1]) mod 65536`.

LOD-0 heights are the Copernicus surface model with buildings flattened (grey opening in built-up
cells) and the **road bed carved**: within half the road width + 1.5 m of the centre line the terrain
lies 0.15 m below the road profile; beside it cut/fill slopes of 0.75 (≈ 37°) meet the natural
terrain, fading out 45–55 m from the road. Bridges and tunnels are not carved. The road surface
itself is drawn from the PATH by the renderer.

## LANDCOVER

```
u16 cols, rows
u8[rows*cols]    class codes
```

0 none, 1 tree, 2 shrub, 3 grass, 4 crop, 5 built-up, 6 bare/sparse, 7 snow/ice, 8 water,
9 wetland, 10 mangrove, 11 moss/lichen (ESA WorldCover 2021), 12 vineyard, 13 orchard
(Overture land use), 14 road (Overture roads and the route, rasterised with their width; LOD 0 only).
Trees are not stored individually: the runtime scatters them on class 1 (and hedges, orchards,
vineyard rows on 2, 12, 13).

## IMAGE

A baseline JPEG (4:2:0) of the Sentinel-2 cloudless composite 2021 (ESA WorldCover S2 RGB),
tone-mapped to sRGB. Decodable by Android `BitmapFactory` and Java `ImageIO`.

## Vector chunks

All vector coordinates are integers in `unit` metres (`unitM` of the LOD: 0.1 m at LOD 0, 0.4 m at
LOD 1, 1.6 m at LOD 2) relative to the tile's south-west corner. A **polyline** is

```
u16 n
i16 x0, y0       first vertex
i16[2(n-1)]      dx, dy deltas
```

Rings are not closed (the last vertex connects back to the first).

### BUILDINGS (LOD 0)

```
u32 count, f32 unit
count × {
  u8  class            index into META buildingClasses (0 unknown, 1 house, …)
  u8  flags            1 height estimated, 2 floors known
  u8  roofShape        0 unknown, 1 flat, 2 gabled, 3 hipped, 4 pyramidal, 5 skillion, 6 dome, 7 onion,
                       8 round, 9 gambrel, 10 mansard, 11 saltbox, 12 half_hipped, 13 butterfly, 14 spherical
  u8  floors           0 unknown
  u16 height           dm, ground to top (estimated from floors or footprint area when unknown)
  u16 minHeight        dm (building parts on stilts, bridges)
  u16 roofHeight       dm, 0 unknown
  u16 reserved
  f32 baseZ            m: lowest carved terrain under the footprint
  u32 roofColour       ARGB, 0 unknown
  u32 facadeColour     ARGB, 0 unknown
  polyline footprint   outer ring
}
```

A building belongs to the tile that contains its centroid; its ring may extend beyond the tile.

### ROADS (LOD 0)

```
u32 count, f32 unit
count × { u8 class, u8 flags (as PATH), u8 width dm, u8 reserved, polyline }
```

Roads and railways within 1.2 km of the route, clipped to the tile. The parts that are the route
itself are removed (the route is drawn from PATH), so side roads end at the route's edge.

### WATER

```
u32 count, f32 unit
count × {
  u8  kind             0 area, 1 line
  u8  class            index into META waterClasses
  u16 ringCount        areas: outer ring then holes; lines: 1
  f32 level            surface elevation of lakes/ponds/reservoirs (m), NaN = follows the terrain
  ringCount × polyline
}
```

## META (JSON)

```json
{
 "format": "wwgeo", "version": 1, "pipeline": 1, "id": "alpe-dhuez", "built": "2026-10-28T16:34:17+00:00",
 "lengthM": 13990.9, "officialLengthKm": 13.8,
 "projection": {"type": "tmerc", "lat0": 45.0768, "lon0": 6.055, "k0": 1.0, "ellipsoid": "WGS84", "proj4": "…"},
 "pathStepM": 5.0, "pathBounds": [xmin, ymin, xmax, ymax],
 "corridor": {"offsetsM": [0.0, 1.5, 3.0, 6.0, 10.0, 15.0, 22.0, 30.0, 40.0], "sectionSamples": 2048},
 "lods": [{"lod": 0, "tileM": 1280.0, "grid": 128, "image": 128, "landcover": 128, "reachM": 1200.0, "unitM": 0.1}, …],
 "landcoverClasses": […], "roadClasses": […], "buildingClasses": […], "waterClasses": […], "roofShapes": {…},
 "hairpins": [{"s": 1500.0, "turnDeg": -159, "z": 821.4}, …],
 "waypoints": [{"name": "…", "kind": "start", "km": 0.0, "lat": …, "lon": …}, …],
 "legs": [{"to": "…", "km": …, "routedKm": …, "legKm": …, "expectedLegKm": …, "errorPct": …}, …],
 "stats": {"startZ": …, "finishZ": …, "maxZ": …, "ascentM": …, "lod0Tiles": …, …},
 "attribution": ["Overture Maps Foundation …", "Copernicus DEM GLO-30 …", "ESA WorldCover …"]
}
```

Readers ignore unknown fields. `hairpins` are turns of more than 140° within 80 m, with the
distance of the apex.

## PLACES (JSON)

```json
{"places": [{"name": "Huez", "subtype": "locality", "class": "village", "population": 1281,
             "x": 412.3, "y": 551.0, "s": 9420.0, "offset": -35.2}, …],
 "towns":  [{"name": "Huez", "sIn": 8650.0, "sOut": 10580.0, "population": 1281}, …]}
```

`places`: Overture localities and neighbourhoods within 3 km; `s` is the distance along the route of
the nearest road point, `offset` the signed distance from the road (positive = left).
`towns`: built-up stretches of the route (≥ 6 buildings within 120 m, ≥ 250 m long) named after the
nearest locality: where the town entry and exit signs stand.

## Profile resource (`core/src/main/resources/app/rollentrainer/core/geo/profiles/<id>.bin`)

The app's elevation profile, small enough for the repository:

```
8 bytes  magic "WWPROF\0\1"
zlib {
  u32 n, f32 step (20 m), f64 length, i32 z0 (cm), u32 metaLength
  meta JSON {"id", "officialKm", "pipeline", "hairpinsAtM", "landmarks"?, "climbs"?}
  i16[n-1] deltas of z in cm; point i at i·step, the last at length
}
```

Optional meta fields (added in milestone 2, readers ignore what they do not know):

* `landmarks`: `[{"m", "label"}]`, the route's landmark signs placed on the real road
  (`tools/geodata/landmarks.py`: matched to waypoints and places, `landmarks.json` corrections).
  When present it replaces the app's list; when absent the app stretches its own landmarks.
* `climbs`: `[{"name", "summitKm", "summitM"}]`, stage climb summits on the real road, by the
  climb name of `Stages.kt`.

## Licences / attribution

Packs contain data from Overture Maps (ODbL, © OpenStreetMap contributors; CDLA), Copernicus DEM
GLO-30 (© DLR e.V. 2010-2014 and © Airbus Defence and Space GmbH 2014-2018, provided under
COPERNICUS by the European Union and ESA), ESA WorldCover 2021 / Sentinel-2 composite (CC BY 4.0,
contains modified Copernicus Sentinel data) and, since milestone 2, the Meta/WRI High Resolution Canopy
Height Maps (CC BY 4.0; used to take the forest out of the road profile). The app must show the
`attribution` strings.
