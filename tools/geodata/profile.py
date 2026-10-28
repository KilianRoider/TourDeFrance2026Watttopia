"""Road elevation profile from the Copernicus GLO-30 surface model.

GLO-30 is a surface model: forest canopy, buildings and bridges are in it, and a 30 m pixel is wider
than the road. The road profile therefore is not the DEM under the centre line but a lower envelope:

1. Observation per 10 m: a cross-section of +-30 m is sampled; a line fitted across the road removes
   the hillside slope, and a low quantile of the residuals drops canopy and buildings on one side.
2. Forest: radar reads part of the canopy, so the surface model steps up by 5-15 m where the road
   enters a wood and down where it leaves; on a rolling stage these steps become short 8-12 %
   ramps that do not exist. Every observation is lowered by a share of the mean canopy height
   across the road (canopy.py, Meta/WRI canopy height map), fitted against the DEM on stage 12.
3. Weights: bridges and tunnels get no weight (the DEM shows the valley or the mountain); samples in
   forest or town get a lower weight, as their observation is biased upwards.
4. An asymmetric, smoothness-penalised fit (iteratively reweighted least squares): observations above
   the profile count much less than those below, so the profile rides the lower envelope; a curvature
   penalty gives road-like vertical curves, and a gradient limit removes impossible ramps.
"""
from __future__ import annotations

import numpy as np
from scipy import sparse
from scipy.sparse.linalg import spsolve

from geo_common import DEM, WORLDCOVER, sample_grid, log

WC_TREE, WC_SHRUB, WC_GRASS, WC_CROP, WC_BUILT, WC_BARE, WC_SNOW, WC_WATER, WC_WETLAND, WC_MANGROVE, WC_MOSS = \
    10, 20, 30, 40, 50, 60, 70, 80, 90, 95, 100


def path_lonlat_box(lon, lat, margin=0.01):
    return lon.min() - margin, lat.min() - margin, lon.max() + margin, lat.max() + margin


def headings(x, y):
    dx = np.gradient(x)
    dy = np.gradient(y)
    n = np.hypot(dx, dy) + 1e-9
    return dx / n, dy / n


def observe(proj, s, x, y, step=10.0, half=30.0, n_off=9, use_canopy=True):
    """Per [step] metres: (s, lower-envelope DEM height, centre DEM height, tree fraction, built fraction, water fraction)."""
    # Evenly spaced up to the exact end: the fit's difference operators assume one spacing, and a
    # last sample 2 m after the previous one would be treated as 10 m away (a false end ramp).
    si = np.linspace(0.0, s[-1], max(2, int(round(s[-1] / step)) + 1))
    xi = np.interp(si, s, x)
    yi = np.interp(si, s, y)
    tx, ty = headings(xi, yi)
    nx, ny = -ty, tx
    offs = np.linspace(-half, half, n_off)
    X = xi[:, None] + nx[:, None] * offs[None, :]
    Y = yi[:, None] + ny[:, None] * offs[None, :]
    lon, lat = proj.to_lonlat(X, Y)
    box = path_lonlat_box(lon, lat)
    dem, l0, t0, dl, dt = DEM.read(*box)
    H = sample_grid(dem[0].astype(np.float32), l0, t0, dl, dt, lon, lat, order=1)
    # Hillside: least-squares line across the road, then a low quantile of what lies above it.
    o = offs - offs.mean()
    slope = (H * o[None, :]).sum(1) / (o * o).sum()
    resid = H - H.mean(1, keepdims=True) - slope[:, None] * o[None, :]
    centre = H[:, n_off // 2]
    low = H.mean(1) + np.quantile(resid, 0.25, axis=1)
    # Land cover in a +-20 m band.
    wc, wl0, wt0, wdl, wdt = WORLDCOVER.read(*box)
    offs_lc = np.linspace(-20, 20, 5)
    Xl = xi[:, None] + nx[:, None] * offs_lc[None, :]
    Yl = yi[:, None] + ny[:, None] * offs_lc[None, :]
    lonl, latl = proj.to_lonlat(Xl, Yl)
    LC = sample_grid(wc[0], wl0, wt0, wdl, wdt, lonl, latl, order=0)
    tree = (LC == WC_TREE).mean(1)
    built = (LC == WC_BUILT).mean(1)
    water = (LC == WC_WATER).mean(1)
    # Canopy height across the road, +-45 m: a 30 m DEM pixel and its bilinear neighbours.
    canopy_m = np.zeros(len(si))
    if use_canopy:
        import canopy
        offs_c = np.linspace(-45, 45, 10)
        lonc, latc = proj.to_lonlat(xi[:, None] + nx[:, None] * offs_c[None, :], yi[:, None] + ny[:, None] * offs_c[None, :])
        canopy_m = canopy.sample(lonc, latc).mean(1).astype(float)
        # The DEM does not drop in a clearing narrower than a few of its pixels (an avalanche
        # couloir, a meadow beside the road): averaged along the road over ~90 m, a short gap in the
        # canopy does not become a 15 m step in the correction.
        from scipy.ndimage import uniform_filter1d
        canopy_m = uniform_filter1d(canopy_m, size=max(3, int(round(90.0 / step)) | 1), mode="nearest")
    # Turning: at a hairpin the 30 m pixels mix the legs above and below and the apex reads high.
    hd = np.unwrap(np.arctan2(ty, tx))
    k = max(1, int(round(25.0 / step)))
    turn = np.abs(np.concatenate([hd[k:], np.repeat(hd[-1], k)]) - np.concatenate([np.repeat(hd[0], k), hd[:-k]]))
    return dict(s=si, low=low, centre=centre, tree=tree, built=built, water=water,
                cross_slope=slope, turn=turn, canopy=canopy_m)


def second_diff(n, h):
    e = np.ones(n)
    return sparse.diags([e[:-2], -2 * e[:-2], e[:-2]], [0, 1, 2], shape=(n - 2, n)) / (h * h)


def first_diff(n, h):
    e = np.ones(n)
    return sparse.diags([-e[:-1], e[:-1]], [0, 1], shape=(n - 1, n)) / h


def fit_profile(obs, bridge_tunnel, step=10.0, smooth_m=200.0, up_weight=0.08, max_grade=0.22, iters=14,
                anchors=None, steep_noise_m=6.0, steep_stiff=30.0, ties=None, tie_weight=100.0):
    """Asymmetric smoothing fit. smooth_m sets the length scale of the curvature penalty (200 m is
    about a 100 m moving average: at 110 m the DEM's few metres of noise still made 100 m grades
    2-4 points steeper than the published maxima, while the per-km grades are the same for both);
    anchors = [(s, z, weight)] pins known elevations (start/finish line) softly; ties = [(i, j, j2, a)]
    make z_i = (1-a) z_j + a z_j2 where the route passes the same spot twice (route_ties)."""
    s = obs["s"]
    h = obs["low"].astype(float).copy()
    n = len(s)
    w0 = np.ones(n)
    w0[bridge_tunnel] = 0.0
    # Canopy and buildings bias the observation upwards; trust such samples less.
    w0 *= 1.0 - 0.6 * obs["tree"] - 0.4 * obs["built"]
    w0 *= 1.0 - 0.8 * np.clip(obs["water"] - 0.3, 0, 1)  # water surfaces next to the road (dams, quays)
    w0 *= 1.0 / (1.0 + (obs["turn"] / np.radians(60)) ** 4)
    # On a steep hillside a few metres of lateral error (road geometry, DEM geolocation, 30 m pixels)
    # become several metres of height: in a gorge the samples jump +-15 m every few hundred metres.
    # Such samples get less weight (noise ~ 6 m * cross slope), and the curvature penalty grows there,
    # because a road cut into a steep slope is engineered with an even grade.
    from scipy.ndimage import uniform_filter1d
    xs = uniform_filter1d(np.abs(obs["cross_slope"]), size=max(3, int(100 / step)))
    w0 *= 1.0 / (1.0 + (steep_noise_m * xs / 2.5) ** 2)
    D2 = second_diff(n, step)
    # lambda such that the penalty balances the data term at wavelength ~ smooth_m.
    lam = (smooth_m / (2 * np.pi)) ** 4
    # In a stack of hairpins the legs are closer than the DEM can resolve and the samples ripple by
    # +-10 m; there the profile follows the trend of the whole stack (much stiffer, symmetric).
    stack = uniform_filter1d((obs["turn"] > np.radians(60)).astype(float), size=max(3, int(400 / step)))
    stiff = 1.0 + 400.0 * np.clip(stack * 4, 0, 1) ** 2
    stiff *= 1.0 + steep_stiff * np.clip((xs - 0.45) / 0.35, 0, 1) ** 2
    lam_i = lam * stiff[1:-1]
    P = D2.T @ sparse.diags(lam_i) @ D2
    D1 = first_diff(n, step)
    Pt = sparse.csr_matrix((n, n))
    if ties is not None and len(ties):
        k = len(ties)
        i, j, j2, a = ties[:, 0].astype(int), ties[:, 1].astype(int), ties[:, 2].astype(int), ties[:, 3]
        r = np.arange(k)
        T = sparse.csr_matrix((np.r_[np.ones(k), a - 1.0, -a], (np.r_[r, r, r], np.r_[i, j, j2])), shape=(k, n))
        Pt = tie_weight * (T.T @ T)
    mg = np.broadcast_to(np.asarray(max_grade, dtype=float), (n - 1,)).copy()
    # Start from a robust running median to have sane residuals in the first iteration.
    z = h.copy()
    valid = w0 > 0
    if valid.sum() < 2:
        raise RuntimeError("no usable elevation samples")
    z = np.interp(s, s[valid], h[valid])
    g_pen = np.zeros(n - 1)
    g_target = np.zeros(n - 1)
    for it in range(iters):
        r = h - z
        w = w0 * np.where(r > 0, np.maximum(up_weight, np.clip(stack * 4, 0, 1)), 1.0)
        # Large residuals below the profile are DEM pits or valleys under unflagged bridges: Huber-like.
        big = r < -8.0
        w[big] *= 8.0 / np.abs(r[big])
        W = sparse.diags(w)
        A = W + P + Pt
        b = w * h
        if anchors:
            for (sa, za, wa) in anchors:
                i = int(np.clip(np.searchsorted(s, sa), 0, n - 1))
                A = A + sparse.csr_matrix(([wa], ([i], [i])), shape=(n, n))
                b[i] += wa * za
        if g_pen.any():
            G = sparse.diags(g_pen)
            A = A + D1.T @ G @ D1
            b = b + D1.T @ (g_pen * g_target)
        z = spsolve(A.tocsc(), b)
        g = np.diff(z) / step
        over = np.abs(g) > mg
        if over.any():
            # Escalate fast: a cliff edge in the surface model pulls hard, and a cap that is only
            # nudged leaves 30 % "grades" on a main road.
            g_pen[over] = np.maximum(g_pen[over] * 3.0, 50.0)
            g_target[over] = np.sign(g[over]) * mg[over] * 0.98
    return z


# Share of the mean canopy height (+-45 m across the road, ~29 m boxes) that GLO-30 shows above the
# ground: least squares on the edges of the woods along stage 12 (Burgundy, mixed deciduous) gave
# 0.75-0.80 for the variants tried; with it the forest steps there vanish and the stage's ascent
# drops from 1854 m to ~1770 m (published ~1800 m).
CANOPY_SHARE = 0.8

# Steepest grade a road of this class plausibly has over 10 m. Main roads are engineered: a primary
# road at 25 % is a cliff in the surface model next to it (a gorge, a tunnel portal), not the road.
MAX_GRADE_BY_CLASS = {"motorway": 0.10, "trunk": 0.12, "primary": 0.14, "secondary": 0.16, "tertiary": 0.20}


def route_ties(so, xo, yo, bt, near=6.0, min_ds=200.0):
    """Where the route passes the same spot again (a lap, a stretch ridden twice, a crossing at
    grade): each observation there is tied to the point of the other pass beside it, interpolated
    between that pass's two nearest observations, as rows (i, j, j2, a) of z_i = (1-a) z_j + a z_j2.
    The road has one height there, but stretches of the fit far apart along the route gave it two:
    5 m apart where stage 14 comes back onto the D431 at the Col du Haag, which put the carved
    verge of one pass 1.5 m above the other. Bridges and tunnels (one pass over the other) keep
    their own heights."""
    from scipy.spatial import cKDTree
    pts = np.column_stack([xo, yo])
    pr = cKDTree(pts).query_pairs(near, output_type="ndarray")
    if len(pr) == 0:
        return np.zeros((0, 4))
    pr = pr[(np.abs(so[pr[:, 1]] - so[pr[:, 0]]) > min_ds) & ~bt[pr[:, 0]] & ~bt[pr[:, 1]]]
    pr = np.vstack([pr, pr[:, ::-1]])  # both passes tied to each other
    d = np.hypot(*(pts[pr[:, 0]] - pts[pr[:, 1]]).T)
    # Tied to every other pass, not only the nearest: on a circuit ridden eight times, ties to the
    # nearest pass alone made chains that left laps 2 m apart.
    best = {}
    for (i, j), dd in zip(pr.tolist(), d.tolist()):
        best.setdefault(i, []).append((so[j], j, dd))
    pairs = []
    for i, cands in best.items():
        cands.sort()
        group = [cands[0]]
        for c in cands[1:] + [None]:
            if c is not None and c[0] - group[-1][0] < 100.0:
                group.append(c)
                continue
            pairs.append((i, min(group, key=lambda g: g[2])[1]))
            if c is not None:
                group = [c]
    n = len(so)
    rows = []
    for i, j in pairs:
        # The segment of the other pass on the side of i, and where i projects onto it.
        cand = [k for k in (j - 1, j + 1) if 0 <= k < n and not bt[k]]
        if not cand:
            continue
        j2 = min(cand, key=lambda k: np.hypot(*(pts[k] - pts[i])))
        v = pts[j2] - pts[j]
        a = float(np.clip(np.dot(pts[i] - pts[j], v) / max(np.dot(v, v), 1e-9), 0.0, 1.0))
        rows.append((i, j, j2, a))
    return np.array(rows) if rows else np.zeros((0, 4))


def profile_for_path(proj, s, x, y, flags, step=10.0, cls=None, bridge_pad_m=40.0, route_cap=None, **kw):
    """Road elevation at every path sample s (metres). [cls] (route class codes per sample) sets
    the gradient cap per road class; [route_cap] (fraction) caps the whole route, from the
    climb's published steepest 100 m: a steeper stretch in the surface model is a cliff or a
    gully next to the road, and its height is spread over a longer ramp instead. On a stage it is
    a list [(s0, s1, cap)] that caps only those climbs."""
    from route import CLASS_CODE, F_BRIDGE, F_TUNNEL
    obs = observe(proj, s, x, y, step=step)
    if cls is not None and "max_grade" not in kw:
        code_cap = np.full(256, 0.24)  # nothing above the app's 25 % (Gearing.MAX_GRADE)
        for name, cap in MAX_GRADE_BY_CLASS.items():
            code_cap[CLASS_CODE[name]] = cap
        mid = (obs["s"][:-1] + obs["s"][1:]) / 2
        kw["max_grade"] = code_cap[np.asarray(cls)[np.clip(np.searchsorted(s, mid), 0, len(s) - 1)]]
    if route_cap is not None:
        if np.isscalar(route_cap):
            capv = route_cap
        else:
            mid = (obs["s"][:-1] + obs["s"][1:]) / 2
            capv = np.full(len(mid), 0.24)
            for s0, s1, c in route_cap:
                capv[(mid >= s0) & (mid <= s1)] = c
        kw["max_grade"] = np.minimum(kw.get("max_grade", 0.24), capv)
    bt_full = (flags & (F_BRIDGE | F_TUNNEL)) != 0
    # Widen bridge/tunnel ranges: the DEM ramps into the gully well before the abutment (a 20 m
    # deck over a ravine sits on embankments, and 30 m pixels blur the ravine's edge further).
    bt = np.interp(obs["s"], s, bt_full.astype(float)) > 0
    from scipy.ndimage import binary_dilation
    h = float(obs["s"][1] - obs["s"][0])
    bt = binary_dilation(bt, iterations=max(1, int(round(bridge_pad_m / h))))
    if obs["canopy"].any():
        cut = CANOPY_SHARE * obs["canopy"]
        log(f"canopy: observations lowered by up to {cut.max():.1f} m, {(cut > 3).mean() * 100:.0f} % of the route by more than 3 m")
        obs = dict(obs, low=obs["low"] - cut)
    ties = route_ties(obs["s"], np.interp(obs["s"], s, x), np.interp(obs["s"], s, y), bt)
    if len(ties):
        log(f"profile: {len(ties)} ties where the route passes the same road twice")
    z = fit_profile(obs, bt, step=float(obs["s"][1] - obs["s"][0]), ties=ties, **kw)
    zf = np.interp(s, obs["s"], z)
    return zf, obs, z
