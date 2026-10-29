package app.rollentrainer.ui.world.geo

/**
 * GLSL ES 3.00 sources of the geodata renderer. Everything is drawn camera-relative: the CPU computes
 * (anchor - camera) in Double per draw, so floats stay precise on a 200 km stage. Lighting is linear
 * HDR with a filmic (ACES fit) tone map and sRGB encoding at the end of every fragment shader, so no
 * extra full-screen pass is needed and the default framebuffer keeps its MSAA.
 *
 * Per-frame constants live in one std140 uniform block "Frame" (binding 0), updated once per frame.
 */
object GeoShaders {
    /** Must match [FrameBlock] on the CPU side (std140, 16 vec4 = 256 bytes after the two matrices). */
    private const val FRAME = """
layout(std140) uniform Frame {
    mat4 uViewProj;      // camera-relative world -> clip
    mat4 uShadowMat;     // camera-relative world -> shadow map [0,1]^3
    vec4 uSunDir;        // xyz towards the sun, w = shadow map texel size (uv units)
    vec4 uSunColor;      // rgb sun irradiance, w = time in seconds (wraps hourly)
    vec4 uSkyAmb;        // rgb ambient from the sky dome, w = exposure
    vec4 uGroundAmb;     // rgb ambient bounced from the ground, w = earth curvature 1 / (2 R)
    vec4 uFog;           // x extinction at camera height (1/m), y height falloff (1/m), z max fog, w shadow on (0/1)
    vec4 uExtinction;    // rgb relative extinction per channel, w = mie glow strength
    vec4 uSkyZenith;     // rgb zenith colour, w = cloud cover 0..1
    vec4 uSkyHorizon;    // rgb horizon colour, w = camera altitude (m)
    vec4 uCamWorld;      // xyz camera world position modulo 1200 m (texture space), w = shadow distance (m)
    vec4 uWind;          // xy wind direction, z strength, w gust phase
    vec4 uGrade;         // the rider's picture settings: x exposure, y contrast, z saturation, w ground and road detail
};
"""

    /** Sky colour model shared by the sky dome and the aerial perspective of everything else. */
    private const val SKY = """
vec3 skyColor(vec3 dir) {
    float y = dir.y;
    float up = clamp(y, 0.0, 1.0);
    // Gradient from a pale, bright horizon to the deep blue zenith (a fit of the clear-sky luminance).
    vec3 col = mix(uSkyHorizon.rgb, uSkyZenith.rgb, pow(up, 0.42));
    // Below the horizon the haze continues (the far terrain fades into it).
    col = mix(col, uSkyHorizon.rgb * 0.92, smoothstep(0.0, -0.08, y));
    float mu = max(dot(dir, uSunDir.xyz), 0.0);
    // Mie forward scattering: a wide glow and a tighter aureole around the sun.
    vec3 sun = uSunColor.rgb;
    col += sun * uExtinction.w * (0.035 * pow(mu, 6.0) + 0.09 * pow(mu, 48.0));
    // The horizon is brighter in the sun's direction.
    col += sun * 0.025 * pow(mu, 3.0) * (1.0 - up);
    return col;
}
"""

    private const val TONEMAP = """
vec3 acesFit(vec3 x) {
    return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
}
vec3 linearToSrgb(vec3 c) {
    vec3 lo = c * 12.92;
    vec3 hi = 1.055 * pow(max(c, vec3(0.0)), vec3(1.0 / 2.4)) - 0.055;
    return mix(lo, hi, step(vec3(0.0031308), c));
}
vec4 finalColor(vec3 hdr) {
    vec3 c = linearToSrgb(acesFit(hdr * uSkyAmb.w * uGrade.x));
    // Grading in display space, like the sliders of a photo app: saturation around the luminance,
    // contrast around mid grey.
    float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
    c = mix(vec3(l), c, uGrade.z);
    c = (c - 0.5) * uGrade.y + 0.5;
    return vec4(clamp(c, 0.0, 1.0), 1.0);
}
"""

    /** Aerial perspective: analytic exponential height fog with wavelength-dependent extinction. */
    private const val ATMOSPHERE = """
vec3 atmosphere(vec3 col, vec3 rel) {
    float dist = length(rel);
    vec3 dir = rel / max(dist, 0.001);
    float b = uFog.y;
    float dy = rel.y * b;
    // Integral of exp(-b h) along the ray, normalised by the density at the camera.
    float k = abs(dy) > 1e-4 ? (1.0 - exp(-dy)) / dy : 1.0;
    float depth = uFog.x * dist * k;
    vec3 t = exp(-depth * uExtinction.rgb);
    t = max(t, vec3(1.0 - uFog.z));
    vec3 haze = skyColor(normalize(vec3(dir.x, max(dir.y, 0.0) * 0.5, dir.z)));
    return col * t + haze * (1.0 - t);
}
"""

    /** Sun, sky and ground light with a GGX specular lobe; [shadow] 0..1, [ao] cavity. */
    private const val LIGHT = """
const float PI = 3.14159265;
float ggx(float nh, float a) {
    float a2 = a * a;
    float d = nh * nh * (a2 - 1.0) + 1.0;
    return a2 / (PI * d * d);
}
vec3 shade(vec3 albedo, vec3 n, vec3 v, float rough, float ao, float shadow, float specScale) {
    vec3 l = uSunDir.xyz;
    float nl = max(dot(n, l), 0.0);
    float hemi = 0.5 + 0.5 * n.y;
    vec3 ambient = (uSkyAmb.rgb * hemi + uGroundAmb.rgb * (1.0 - hemi)) * ao;
    vec3 diffuse = uSunColor.rgb * (nl * shadow);
    vec3 h = normalize(l + v);
    float nh = max(dot(n, h), 0.0);
    float nv = max(dot(n, v), 0.001);
    float a = max(rough * rough, 0.02);
    float fres = 0.04 + 0.96 * pow(1.0 - max(dot(h, v), 0.0), 5.0);
    // Visibility term folded in (Kelemen approximation), so rough surfaces stay dim.
    float vis = 0.25 / max(dot(l, h) * dot(l, h), 0.1);
    float spec = ggx(nh, a) * fres * vis * nl * shadow * specScale;
    // Sky reflection at grazing angles (the sheen of a road towards the light).
    float fv = pow(1.0 - nv, 5.0) * (1.0 - rough) * specScale;
    return albedo * (ambient + diffuse) + uSunColor.rgb * spec * 0.9 + uSkyAmb.rgb * fv * 0.5 * ao;
}
"""

    private const val SHADOW_FN = """
uniform highp sampler2DShadow uShadow;
float shadowAt(vec3 rel, vec3 n) {
    if (uFog.w < 0.5) return 1.0;
    float d = length(rel);
    if (d > uCamWorld.w) return 1.0;
    // Normal offset against acne, larger at grazing light.
    float nl = clamp(dot(n, uSunDir.xyz), 0.0, 1.0);
    vec3 p = rel + n * (0.06 + 0.12 * (1.0 - nl));
    vec4 sc = uShadowMat * vec4(p, 1.0);
    vec3 s = sc.xyz / sc.w;
    if (s.x <= 0.0 || s.x >= 1.0 || s.y <= 0.0 || s.y >= 1.0 || s.z >= 1.0) return 1.0;
    float o = uSunDir.w * 0.8;
    float sum = texture(uShadow, vec3(s.xy + vec2(-o, -o), s.z));
    sum += texture(uShadow, vec3(s.xy + vec2(o, -o), s.z));
    sum += texture(uShadow, vec3(s.xy + vec2(-o, o), s.z));
    sum += texture(uShadow, vec3(s.xy + vec2(o, o), s.z));
    float fade = smoothstep(uCamWorld.w * 0.8, uCamWorld.w, d);
    return mix(sum * 0.25, 1.0, fade);
}
"""

    private const val HEADER_VS = "#version 300 es\nprecision highp float;\nprecision highp int;\nprecision highp sampler2D;\n"
    private const val HEADER_FS = "#version 300 es\nprecision highp float;\nprecision highp int;\nprecision highp sampler2D;\n" +
        "precision highp sampler2DArray;\nprecision highp usampler2D;\n"

    fun vs(body: String, defines: String = ""): String = HEADER_VS + defines + FRAME + body
    fun fs(body: String, defines: String = ""): String = HEADER_FS + defines + FRAME + SKY + TONEMAP + ATMOSPHERE + LIGHT + SHADOW_FN + body

    // ---------------------------------------------------------------------------------------------
    // Terrain

    val TERRAIN_VS = vs(
        """
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec4 aNrm;      // xyz normal, w = distance beyond the road edge / 12.7 m
uniform vec3 uOffset;                   // tile anchor - camera
uniform vec2 uTexOrigin;                // anchor in texture space (x east, y north), modulo 1200 m
uniform float uTileSize;
out vec3 vRel;
out vec3 vNormal;
out vec2 vTile;
out vec2 vWorld;
out float vAlt;
out float vRoadDist;
void main() {
    vec3 rel = aPos + uOffset;
    // Earth curvature (with refraction): the horizon mountains sink like in reality.
    rel.y -= dot(rel.xz, rel.xz) * uGroundAmb.w;
    vRel = rel;
    vNormal = aNrm.xyz;
    vRoadDist = aNrm.w * 12.7;
    vTile = vec2(aPos.x / uTileSize, 1.0 + aPos.z / uTileSize);
    vWorld = uTexOrigin + vec2(aPos.x, -aPos.z);
    vAlt = aPos.y;
    gl_Position = uViewProj * vec4(rel, 1.0);
}
"""
    )

    private const val TERRAIN_FS_BODY = """
in vec3 vRel;
in vec3 vNormal;
in vec2 vTile;
in vec2 vWorld;
in float vAlt;
in float vRoadDist;
uniform sampler2D uSat;
uniform float uSatGain;
uniform sampler2D uNoise;
#ifndef FAR
uniform highp usampler2D uLc;
uniform float uLcCells;
uniform highp sampler2DArray uGround;
uniform highp sampler2DArray uGroundN;
uniform int uClassLayer[16];
uniform vec3 uLayerMean[24];
uniform float uLayerSize[24];
uniform vec4 uRules;     // x alpine grass from (m), y snow line (m), z rock layer, w forest layer
uniform vec4 uRules2;    // x alpine layer, y snow layer, z gravel layer, w soil layer
uniform vec4 uCropLayers;// wheat, ploughed, green cereal, maize
uniform float uDetailFar;
uniform float uShoulderGain;
#endif
#ifdef TUNNEL_CUT
// Tunnel tubes near the camera, in pieces: xyz camera-relative ends on the road, w half width (A) and
// height (B) of the cut; a negative w marks a piece end that is a portal (no round cap past it).
uniform vec4 uTunA[32];
uniform vec4 uTunB[32];
uniform int uTunN;
uniform vec4 uTunBox;    // xz min, xz max of all pieces (with their width)
#endif
out vec4 fragColor;

#ifndef FAR
// Screen-space derivatives of vWorld and vAlt, taken once at the top of main in uniform control flow. The
// layer lookups run per land-cover slot behind per-pixel branches, where implicit derivatives are
// undefined, and across a quad whose pixels give a slot layers of different size absurd (the 1x1 mip).
vec2 gDx;
vec2 gDy;
float gDAltX;
float gDAltY;

// One layer of ground detail, sampled twice (the second sample turned 35.5 degrees and scaled 0.86) and
// blended by a noise field so its 2 m period never shows as a grid. Both transforms keep the tiles'
// 1200 m texture-space wrap seamless: 1200 m is 1200 / size repeats, a multiple of 20 for every ground
// size, and 0.7 and 0.5 times that are whole numbers.
vec4 sampleLayer(int layer, vec2 w, float mixN, out vec4 nrm) {
    float sz = uLayerSize[layer];
    vec2 uv = w / sz;
    vec2 uv2 = vec2(uv.x * 0.7 - uv.y * 0.5, uv.x * 0.5 + uv.y * 0.7) + vec2(0.37, 0.61);
    vec2 dx = gDx / sz;
    vec2 dy = gDy / sz;
    vec2 dx2 = vec2(dx.x * 0.7 - dx.y * 0.5, dx.x * 0.5 + dx.y * 0.7);
    vec2 dy2 = vec2(dy.x * 0.7 - dy.y * 0.5, dy.x * 0.5 + dy.y * 0.7);
    vec4 a = textureGrad(uGround, vec3(uv, float(layer)), dx, dy);
    vec4 b = textureGrad(uGround, vec3(uv2, float(layer)), dx2, dy2);
    vec4 na = textureGrad(uGroundN, vec3(uv, float(layer)), dx, dy);
    vec4 nb = textureGrad(uGroundN, vec3(uv2, float(layer)), dx2, dy2);
    // The second sample is rotated: rotate its normal back (cos and sin of 35.5 degrees).
    vec2 nbxy = nb.xy * 2.0 - 1.0;
    nbxy = vec2(nbxy.x * 0.8137 + nbxy.y * 0.5812, -nbxy.x * 0.5812 + nbxy.y * 0.8137);
    nrm = mix(vec4(na.xy * 2.0 - 1.0, na.zw), vec4(nbxy, nb.zw), mixN);
    return mix(a, b, mixN);
}

int layerOf(uint cls, vec3 sat) {
    int c = int(cls);
    if (c == 3 && vAlt > uRules.x) return int(uRules2.x);
    if (c == 4) {
        // Crops: July colours from the satellite decide between ripe wheat, ploughed soil and green crops.
        float lum = dot(sat, vec3(0.3, 0.55, 0.15));
        if (sat.r > sat.g * 0.92) return lum < 0.07 ? int(uCropLayers.y) : int(uCropLayers.x);
        float h = textureGrad(uNoise, vWorld / 1200.0, gDx / 1200.0, gDy / 1200.0).b;
        return h > 0.55 ? int(uCropLayers.w) : int(uCropLayers.z);
    }
    if (c == 6 && vAlt > uRules.y) return int(uRules2.y);
    return uClassLayer[c & 15];
}
#endif

void main() {
#ifndef FAR
    gDx = dFdx(vWorld);
    gDy = dFdy(vWorld);
    gDAltX = dFdx(vAlt);
    gDAltY = dFdy(vAlt);
#endif
#ifdef TUNNEL_CUT
    // The ground inside a tunnel tube goes: the 10 m grid cannot follow a gallery under a shallow slope or
    // the cut at a portal, and would stand in the tube. The tube's lining hides the hole from inside, its
    // outer shell from outside (the cut stays between the two).
    if (vRel.x > uTunBox.x && vRel.z > uTunBox.y && vRel.x < uTunBox.z && vRel.z < uTunBox.w) {
        for (int i = 0; i < 32; i++) {
            if (i >= uTunN) break;
            vec4 a = uTunA[i];
            vec4 b = uTunB[i];
            vec2 ab = b.xz - a.xz;
            float t = dot(vRel.xz - a.xz, ab) / max(dot(ab, ab), 1e-4);
            if ((t < 0.0 && a.w < 0.0) || (t > 1.0 && b.w < 0.0)) continue;
            t = clamp(t, 0.0, 1.0);
            float y = vRel.y - mix(a.y, b.y, t);
            float lat = length(vRel.xz - (a.xz + ab * t)) / abs(a.w);
            float up = max(y, 0.0) / abs(b.w);
            if (y > -0.5 && lat * lat + up * up < 1.0) discard;
        }
    }
#endif
    vec3 gn = normalize(vNormal);
    float dist = length(vRel);
    vec3 v = -vRel / max(dist, 0.001);
    vec3 sat = texture(uSat, vTile).rgb * uSatGain;
    vec3 albedo = sat;
    vec3 n = gn;
    float rough = 0.85;
    float ao = 1.0;
    float spec = 0.35;
    float water = 0.0;
#ifndef FAR
    if (dist < uDetailFar) {
        // Noise periods divide the 1200 m texture-space wrap (TEX_PERIOD), so tile edges show no seam.
        vec4 nz = texture(uNoise, vWorld / 30.0);
        vec4 nz2 = texture(uNoise, vWorld / 7.5);
        float mixN = smoothstep(0.35, 0.65, nz.r);
        // Land cover with a manual bilinear blend of the four nearest cells, the sample point warped by noise
        // so the 10 m cells become irregular patches.
        vec2 lcf = clamp(vTile, 0.0, 1.0) * uLcCells - 0.5 + (nz.ga - 0.5) * 0.9;
        vec2 f = fract(lcf);
        ivec2 i0 = ivec2(floor(lcf));
        int mx = int(uLcCells) - 1;
        ivec2 a0 = clamp(i0, ivec2(0), ivec2(mx));
        ivec2 a1 = clamp(i0 + ivec2(1), ivec2(0), ivec2(mx));
        uint c00 = texelFetch(uLc, ivec2(a0.x, a0.y), 0).r;
        uint c10 = texelFetch(uLc, ivec2(a1.x, a0.y), 0).r;
        uint c01 = texelFetch(uLc, ivec2(a0.x, a1.y), 0).r;
        uint c11 = texelFetch(uLc, ivec2(a1.x, a1.y), 0).r;
        float w[4];
        w[0] = (1.0 - f.x) * (1.0 - f.y);
        w[1] = f.x * (1.0 - f.y);
        w[2] = (1.0 - f.x) * f.y;
        w[3] = f.x * f.y;
        uint cs[4];
        cs[0] = c00; cs[1] = c10; cs[2] = c01; cs[3] = c11;
        int ls[4];
        for (int i = 0; i < 4; i++) {
            if (cs[i] == 8u) { water += w[i]; w[i] = 0.0; ls[i] = -1; }
            else ls[i] = layerOf(cs[i], sat);
        }
        // Shoulder: a strip of dusty gravel right beside the asphalt, ragged where the grass takes over.
        float shoulder = 1.0 - smoothstep(0.25, 0.7 + nz2.r * 0.9, vRoadDist);
        // Merge equal layers, so a uniform area costs one layer.
        for (int i = 1; i < 4; i++) for (int j = 0; j < 4; j++) {
            if (j < i && ls[j] == ls[i] && w[i] > 0.0) { w[j] += w[i]; w[i] = 0.0; }
        }
        float fade = 1.0 - smoothstep(uDetailFar * 0.45, uDetailFar, dist);
        vec3 detail = vec3(0.0);
        vec3 dn = vec3(0.0);
        float wsum = 0.0;
        float rsum = 0.0;
        float aosum = 0.0;
        vec3 photo = vec3(0.0);
        vec3 photoMean = vec3(0.0);
        for (int i = 0; i < 4; i++) {
            if (w[i] < 0.02 || ls[i] < 0) continue;
            vec4 nrm;
            vec4 a = sampleLayer(ls[i], vWorld, mixN, nrm);
            // Height-based blending: grass grows over the gravel, stones poke through.
            float wh = w[i] * (0.25 + a.a * 1.5);
            detail += wh * a.rgb / uLayerMean[ls[i]];
            photo += wh * a.rgb;
            photoMean += wh * uLayerMean[ls[i]];
            dn += wh * vec3(nrm.xy, 0.0);
            aosum += wh * nrm.z;
            rsum += wh * nrm.w;
            wsum += wh;
        }
        if (shoulder > 0.01) {
            int gl = int(uRules2.z);
            vec4 nrm;
            vec4 a = sampleLayer(gl, vWorld, mixN, nrm);
            float wh = shoulder * 4.0 * max(wsum, 0.25);
            // The shoulder keeps its own colour (scaled to a dusty mean): the satellite pixel there is mostly asphalt.
            detail += wh * a.rgb * uShoulderGain / max(sat, vec3(0.02));
            dn += wh * vec3(nrm.xy, 0.0);
            aosum += wh * nrm.z;
            rsum += wh * nrm.w;
            wsum += wh;
        }
        if (wsum > 0.0) {
            detail /= wsum;
            dn /= wsum;
            // The photo textures vary little around their mean; near the camera their structure (blades,
            // clods, stones) is stretched so the ground reads as grass and not as a green blur.
            float boost = uGrade.w * mix(1.0, 1.7, 1.0 - smoothstep(15.0, 120.0, dist));
            detail = max(vec3(1.0) + (detail - vec3(1.0)) * boost, vec3(0.04));
            dn *= min(boost, 2.0);
            ao = mix(1.0, aosum / wsum, fade);
            rough = mix(rough, rsum / wsum, fade);
            // Medium-scale brightness variation (clumps, worn patches) that the 10 m imagery does not have,
            // and on a larger scale drier, yellower and lusher, bluer-green patches, so a meadow is not a
            // uniform lawn.
            float macro = 0.86 + 0.28 * nz2.b;
            vec4 nzL = texture(uNoise, vWorld / 150.0);
            vec3 hue = mix(vec3(0.95, 1.0, 1.0), vec3(1.07, 1.0, 0.84), smoothstep(0.3, 0.7, nzL.g));
            albedo = sat * mix(vec3(1.0), detail * macro * hue, fade);
            // Close to the rider the 10 m satellite colour is a blur: there the photo keeps its own colours
            // (green blades, yellow straw, brown soil), only its brightness follows the satellite.
            if (wsum > 0.0) {
                vec3 lw = vec3(0.2126, 0.7152, 0.0722);
                vec3 own = photo / wsum * (dot(sat, lw) / max(dot(photoMean / wsum, lw), 0.01));
                float nearW = 0.7 * (1.0 - smoothstep(20.0, 110.0, dist));
                albedo = mix(albedo, own * macro * hue, nearW);
            }
            n = normalize(gn + vec3(dn.x, 0.0, -dn.y) * 0.6 * fade);
        }
        // Rock on steep slopes: biplanar projection so cliffs are not smeared.
        float slope = 1.0 - gn.y;
        float rockW = smoothstep(0.22, 0.42, slope + (nz.r - 0.5) * 0.12);
        if (rockW > 0.01) {
            int rl = int(uRules.z);
            vec3 an = abs(gn);
            bool alongZ = an.x > an.z;
            vec2 pa = alongZ ? vec2(vWorld.y, vAlt) : vec2(vWorld.x, vAlt);
            vec2 pdx = alongZ ? vec2(gDx.y, gDAltX) : vec2(gDx.x, gDAltX);
            vec2 pdy = alongZ ? vec2(gDy.y, gDAltY) : vec2(gDy.x, gDAltY);
            float sz = uLayerSize[rl] * 1.6;
            vec4 ra = textureGrad(uGround, vec3(pa / sz, float(rl)), pdx / sz, pdy / sz);
            vec4 rb = textureGrad(uGround, vec3(vWorld / sz, float(rl)), gDx / sz, gDy / sz);
            vec4 r = mix(rb, ra, smoothstep(0.3, 0.6, slope));
            // On cuts and cliffs the imagery is smeared down the face in streaks (one pixel spans the
            // whole wall): take its colour from a coarse mip there.
            vec3 satRock = mix(sat, textureLod(uSat, vTile, 3.0).rgb * uSatGain, smoothstep(0.35, 0.65, slope));
            vec3 rockCol = satRock * mix(vec3(1.0), r.rgb / uLayerMean[rl], fade);
            albedo = mix(albedo, rockCol, rockW);
            rough = mix(rough, 0.75, rockW);
        }
    } else {
        // Past the detail range one land cover lookup still finds lakes and rivers, which then get
        // their sky reflection (at grazing angles water is the brightest thing in the valley).
        ivec2 lc = clamp(ivec2(clamp(vTile, 0.0, 1.0) * uLcCells), ivec2(0), ivec2(int(uLcCells) - 1));
        water = uLcCells > 0.5 && texelFetch(uLc, lc, 0).r == 8u ? 1.0 : 0.0;
    }
#endif
    // Beyond the detail textures the imagery is the whole surface: the grass blades, stones and tree
    // crowns that shadow each other are below its resolution, so it reads too bright and flat when lit.
#ifdef FAR
    albedo *= 0.8;
    float farK = 1.0;
#else
    albedo *= mix(1.0, 0.8, smoothstep(uDetailFar * 0.5, uDetailFar, dist));
    float farK = smoothstep(uDetailFar * 0.4, uDetailFar, dist);
#endif
    if (farK > 0.0) {
        // Mountains far away: the 10 m imagery is smooth, so add what the eye expects of a slope seen from
        // afar. Patchy brightness on two scales (periods divide the 1200 m texture wrap), bare grey rock with
        // strata where it is steep, and darker gullies.
        vec4 m1 = texture(uNoise, vWorld / 400.0);
        vec4 m2 = texture(uNoise, vWorld / 100.0);
        float slopeF = 1.0 - gn.y;
        float patchy = 0.8 + 0.4 * (m1.r * 0.55 + m2.g * 0.45);
        float lumA = dot(albedo, vec3(0.2126, 0.7152, 0.0722));
        float rk = smoothstep(0.28, 0.55, slopeF + (m2.b - 0.5) * 0.2);
        float strata = 1.0 + 0.16 * sin(vAlt / 9.0 + m1.g * 6.0) * rk;
        vec3 rockC = vec3(lumA) * vec3(1.06, 1.03, 0.98) * 1.25 * strata;
        vec3 farCol = mix(albedo * patchy, rockC * (0.85 + 0.3 * m2.r), rk * 0.65);
        farCol *= 1.0 - 0.25 * smoothstep(0.45, 0.8, slopeF) * (1.0 - m2.a);
        albedo = mix(albedo, farCol, farK * uGrade.w);
    }
    float shadow = shadowAt(vRel, gn);
    vec3 col = shade(albedo, n, v, rough, ao, shadow, spec);
#ifndef FAR
    if (water > 0.01) {
        // Water: dark body colour, a Fresnel reflection and fine wind ripples. The ripples calm down with
        // distance (a far river is a mirror, not a field of bright blotches), and rays reflected low see
        // the far bank rather than the sky, which keeps a river in its valley from glowing white.
        float calm = 1.0 - smoothstep(15.0, 160.0, dist);
        vec4 r1 = texture(uNoise, vWorld / 3.75 + uSunColor.w * vec2(0.011, 0.006));
        vec4 r2 = texture(uNoise, vWorld / 1.25 - uSunColor.w * vec2(0.008, 0.013));
        vec2 rs = ((r1.rg - 0.5) * 0.07 + (r2.ba - 0.5) * 0.04) * (0.25 + 0.75 * calm);
        vec3 wn = normalize(vec3(rs.x, 1.0, rs.y));
        float fr = 0.02 + 0.98 * pow(1.0 - max(dot(wn, v), 0.0), 5.0);
        vec3 rd = reflect(-v, wn);
        vec3 body = sat * 0.6 * (uSkyAmb.rgb + uSunColor.rgb * max(dot(wn, uSunDir.xyz), 0.0) * shadow);
        vec3 refl = mix(skyColor(rd), body * 0.7, (1.0 - smoothstep(0.015, 0.12, rd.y)) * 0.6);
        float sp = pow(max(dot(reflect(-uSunDir.xyz, wn), v), 0.0), 300.0) * 6.0 * shadow;
        vec3 wc = mix(body, refl, fr) + uSunColor.rgb * sp;
        col = mix(col, wc, clamp(water, 0.0, 1.0));
    }
#endif
    fragColor = finalColor(atmosphere(col, vRel));
}
"""

    val TERRAIN_FS = fs(TERRAIN_FS_BODY)
    /** The near terrain while a tunnel is close: cuts the ground out of the tubes (a separate program, so the
     *  discard costs early depth testing only then). */
    val TERRAIN_CUT_FS = fs(TERRAIN_FS_BODY, "#define TUNNEL_CUT 1\n")
    val TERRAIN_FAR_FS = fs(TERRAIN_FS_BODY, "#define FAR 1\n")

    // ---------------------------------------------------------------------------------------------
    // Road surface, kerbs, pavements, bridges, side roads (MeshBuffer layout)

    val ROAD_VS = vs(
        """
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec2 aUv;       // metres along, metres across (left positive)
layout(location = 3) in vec3 aColor;    // surface kind, road class, half width
layout(location = 4) in float aExtra;   // tunnel darkness 0..1
uniform vec3 uOffset;
uniform vec2 uTexOrigin;
out vec3 vRel;
out vec3 vNormal;
out vec2 vUv;
out vec3 vAttr;
out vec2 vWorld;
out float vDark;
void main() {
    vec3 rel = aPos + uOffset;
    rel.y -= dot(rel.xz, rel.xz) * uGroundAmb.w;
    vRel = rel;
    vNormal = aNormal;
    vUv = aUv;
    vAttr = aColor;
    vDark = aExtra;
    vWorld = uTexOrigin + vec2(aPos.x, -aPos.z);
    gl_Position = uViewProj * vec4(rel, 1.0);
}
"""
    )

    val ROAD_FS = fs(
        """
in vec3 vRel;
in vec3 vNormal;
in vec2 vUv;
in vec3 vAttr;
in vec2 vWorld;
in float vDark;
uniform highp sampler2DArray uRoad;      // asphalt fresh, polish, worn, cobbles
uniform highp sampler2DArray uRoadN;
uniform highp sampler2DArray uGround;    // pavers, kerb, gravel live in the ground array
uniform highp sampler2DArray uGroundN;
uniform sampler2D uNoise;
uniform vec4 uRoadSize;                  // metres per texture: asphalt, cobbles, pavers, kerb
uniform vec4 uGroundIdx;                 // ground layers: pavers, kerb, gravel, soil
uniform vec4 uGravelSize;                // gravel, soil size, asphalt base layer, paint style
uniform vec4 uRoadGain;                  // albedo scale per road layer (to the region's mean)
uniform vec4 uGroundGain;                // albedo scale: pavers, kerb, gravel, soil
out vec4 fragColor;

// Cotangent frame from screen-space derivatives: normal mapping without stored tangents.
vec3 perturb(vec3 n, vec3 p, vec2 uv, vec2 nxy, float strength) {
    vec3 dp1 = dFdx(p);
    vec3 dp2 = dFdy(p);
    vec2 du1 = dFdx(uv);
    vec2 du2 = dFdy(uv);
    vec3 dp2perp = cross(dp2, n);
    vec3 dp1perp = cross(n, dp1);
    vec3 t = dp2perp * du1.x + dp1perp * du2.x;
    vec3 b = dp2perp * du1.y + dp1perp * du2.y;
    float invmax = inversesqrt(max(dot(t, t), dot(b, b)) + 1e-12);
    return normalize(n + (t * nxy.x + b * nxy.y) * invmax * strength);
}

float hash1(float n) { return fract(sin(n * 12.9898 + 4.1414) * 43758.5453); }
float gainOf(float layer) { return layer < 0.5 ? uRoadGain.x : (layer < 1.5 ? uRoadGain.y : (layer < 2.5 ? uRoadGain.z : uRoadGain.w)); }

float lineMask(float x, float halfW, float aa) {
    return 1.0 - smoothstep(halfW - aa, halfW + aa, abs(x));
}

void main() {
    vec3 gn = normalize(vNormal);
    if (!gl_FrontFacing) gn = -gn;
    float dist = length(vRel);
    vec3 v = -vRel / max(dist, 0.001);
    int kind = int(vAttr.x + 0.5);
    int cls = int(vAttr.y + 0.5);
    float hw = vAttr.z;
    float u = vUv.x;
    float across = vUv.y;
    // A period that divides the 1200 m texture-space wrap: no seam where chunks of different anchors meet.
    vec4 nz = texture(uNoise, vWorld / 40.0);
    vec4 nz2 = texture(uNoise, vec2(u / 9.0, across / 9.0));
    vec3 albedo;
    vec4 nrm;
    vec2 tuv;
    float specScale = 1.0;
    float aa = max(fwidth(across), 0.004) * 0.75;
    if (kind == 0 || kind == 1) {
        // Asphalt: the region's base mix, with large patches of older and repaired surface.
        tuv = vec2(u, across);
        float layer = kind == 1 ? 3.0 : uGravelSize.z;
        float sz = kind == 1 ? uRoadSize.y : uRoadSize.x;
        vec2 uv1 = tuv / sz;
        // u restarts every RoadBuilder.U_PERIOD (2340 m, 1170 repeats): 0.9 (and the patches' 1.1) times that
        // is a whole number, so the restart shows no seam.
        vec2 uv2 = vec2(uv1.y * 0.9 + 0.31, -uv1.x * 0.9 + 0.17);
        float m = smoothstep(0.35, 0.65, nz.r);
        vec4 a = mix(texture(uRoad, vec3(uv1, layer)), texture(uRoad, vec3(uv2, layer)), m);
        a.rgb *= gainOf(layer);
        // Stretch the asphalt's grain around its mean (the texture's last mips) so chippings and stains show.
        vec3 avgA = textureLod(uRoad, vec3(0.5, 0.5, layer), 9.0).rgb * gainOf(layer);
        a.rgb = max(avgA + (a.rgb - avgA) * (1.6 * uGrade.w), vec3(0.0));
        vec4 na = mix(texture(uRoadN, vec3(uv1, layer)), texture(uRoadN, vec3(uv2, layer)), m);
        float seam = 0.0;
        if (kind == 0) {
            // Repairs: in one 30 m cell of three a rectangle of newer asphalt (one lane or the whole width,
            // 3 to 18 m long) with a sealed seam around it. Crisp edges, as on real roads; blotchy noise
            // patches read as dirt.
            float ci = floor(u / 30.0);
            float r0 = hash1(ci);
            if (r0 < 0.34) {
                float r1 = hash1(ci + 31.7), r2 = hash1(ci + 57.3), r3 = hash1(ci + 91.1);
                float u0 = ci * 30.0 + r1 * 11.0;
                float u1 = u0 + 3.0 + r2 * 15.0;
                float a0 = r3 < 0.4 ? -hw - 1.0 : (r3 < 0.8 ? -0.12 : -hw - 1.0);
                float a1 = r3 < 0.4 ? 0.12 : hw + 1.0;
                float d = min(min(u - u0, u1 - u), min(across - a0, a1 - across));
                float aaU = max(fwidth(u), 0.004);
                float patchW = smoothstep(-aaU, aaU, d);
                float pl = uGravelSize.z < 0.5 ? 1.0 : 0.0;
                vec4 b = texture(uRoad, vec3(uv1 * 1.1 + 0.5, pl));
                a = mix(a, vec4(b.rgb * gainOf(pl) * 0.8, b.a), patchW);
                na = mix(na, texture(uRoadN, vec3(uv1 * 1.1 + 0.5, pl)), patchW);
                seam = (1.0 - smoothstep(0.035, 0.035 + aaU * 1.5, abs(d))) * (1.0 - smoothstep(20.0, 60.0, dist));
            }
            // Crack sealing ("pontage"): meandering bands of black tar along and across older roads,
            // the most common mark on French departmental roads. More on worn surfaces than on fresh ones.
            float cj = floor(u / 22.0);
            float q0 = hash1(cj + 7.3);
            float pCrack = uGravelSize.z < 0.5 ? 0.12 : (uGravelSize.z < 1.5 ? 0.35 : 0.55);
            if (q0 < pCrack && dist < 70.0) {
                float q1 = hash1(cj + 13.1), q2 = hash1(cj + 23.9), q3 = hash1(cj + 41.7);
                float c0 = cj * 22.0 + q1 * 8.0;
                float aaC = max(fwidth(across), 0.003) + max(fwidth(u), 0.003);
                float crack;
                if (q2 < 0.6) {
                    // Along the road, near a lane centre or the crown, 6 to 14 m long.
                    float off = (q3 < 0.5 ? -0.5 : 0.5) * hw * (q3 < 0.25 || q3 > 0.75 ? 1.0 : 0.05);
                    float m = off + 0.18 * sin(u * 0.83 + q3 * 6.0) + 0.06 * sin(u * 2.7 + q1 * 9.0);
                    float inside = step(c0, u) * step(u, c0 + 6.0 + q2 * 13.0);
                    crack = lineMask(across - m, 0.03, aaC) * inside;
                } else {
                    // Across the road (thermal cracks), edge to edge.
                    float m = c0 + 0.25 * sin(across * 1.3 + q3 * 6.0) + 0.08 * sin(across * 4.1 + q1 * 5.0);
                    crack = lineMask(u - m, 0.03, aaC) * step(abs(across), hw - 0.1);
                }
                crack *= 1.0 - smoothstep(35.0, 70.0, dist);
                seam = max(seam, crack);
            }
        }
        albedo = a.rgb * (1.0 - 0.45 * seam);
        nrm = vec4(na.xy * 2.0 - 1.0, na.zw);
        nrm.w = mix(nrm.w, 0.5, seam);
        if (kind == 0) {
            // Tyre tracks: two smoother, darker bands per lane; the crown between them a little lighter.
            float lane = hw * 0.5;
            float t1 = (abs(abs(across) - lane) - 0.75) / 0.28;
            float tracks = exp(-t1 * t1) * smoothstep(1.0, 2.0, hw);
            albedo *= 1.0 - 0.18 * tracks;
            nrm.w = mix(nrm.w, nrm.w * 0.72, tracks);
            nrm.xy *= 1.0 - 0.45 * tracks;
            // Paint: centre dashes (3 m line, 10 m gap) on two-lane roads, edge lines on main roads.
            float style = uGravelSize.w;
            vec3 paint = vec3(0.80, 0.80, 0.76) * (0.82 + 0.18 * nz2.r);
            float wearP = smoothstep(0.15, 0.6, nz2.g + 0.25);
            float p = 0.0;
            bool twoLane = hw >= 2.4 && (cls >= 1 && cls <= 6);
            if (twoLane && style > 0.5) p = max(p, lineMask(across, 0.06, aa) * step(fract(u / 13.0), 3.0 / 13.0));
            bool edges = hw >= 2.9 && (cls >= 1 && cls <= 4);
            if (edges) p = max(p, lineMask(abs(across) - (hw - 0.25), 0.065, aa) * step(fract(u / 6.5 + 0.2), cls <= 3 ? 1.0 : 0.46));
            p *= wearP;
            albedo = mix(albedo, paint, p);
            nrm.w = mix(nrm.w, 0.55, p);
            nrm.xy *= 1.0 - 0.8 * p;
        }
    } else if (kind == 2 || kind == 4) {
        // Kerb stones and concrete (bridge parapets, tunnel portals); the builder puts metres along the
        // face into uv, so the texture stays put.
        tuv = vec2(u, across);
        vec2 uv1 = vec2(u / uRoadSize.w, across / (uRoadSize.w * 0.25));
        vec4 a = texture(uGround, vec3(uv1, uGroundIdx.y));
        vec4 na = texture(uGroundN, vec3(uv1, uGroundIdx.y));
        albedo = a.rgb * uGroundGain.y * (kind == 4 ? vec3(0.95, 0.94, 0.92) : vec3(1.0));
        nrm = vec4(na.xy * 2.0 - 1.0, na.zw);
    } else if (kind == 3) {
        // Pavement: concrete pavers in towns, weathered and a little darker than the fresh texture.
        tuv = vec2(u, across);
        vec2 uv1 = tuv / uRoadSize.z;
        vec4 a = texture(uGround, vec3(uv1, uGroundIdx.x));
        vec4 na = texture(uGroundN, vec3(uv1, uGroundIdx.x));
        albedo = a.rgb * uGroundGain.x * (0.85 + 0.3 * nz.b);
        nrm = vec4(na.xy * 2.0 - 1.0, na.zw);
    } else if (kind == 5) {
        // Unpaved: gravel.
        tuv = vec2(u, across);
        vec2 uv1 = tuv / uGravelSize.x;
        vec4 a = texture(uGround, vec3(uv1, uGroundIdx.z));
        vec4 na = texture(uGroundN, vec3(uv1, uGroundIdx.z));
        albedo = a.rgb * uGroundGain.z;
        nrm = vec4(na.xy * 2.0 - 1.0, na.zw);
    } else {
        // Tunnel lining / bridge underside: plain concrete, darker.
        tuv = vec2(u, across);
        vec2 uv1 = tuv / uRoadSize.w;
        vec4 a = texture(uGround, vec3(uv1, uGroundIdx.y));
        albedo = a.rgb * 0.7;
        nrm = vec4(0.0, 0.0, 1.0, 0.9);
        specScale = 0.3;
    }
    float fadeN = 1.0 - smoothstep(25.0, 140.0, dist);
    vec3 n = perturb(gn, vRel, tuv, nrm.xy, fadeN * 0.8 * clamp(uGrade.w * 1.5, 0.0, 2.0));
    float shadow = shadowAt(vRel, gn);
    if (kind == 6) shadow = 0.0;
    // Inside a tunnel: no sun and a quarter of the sky light some 30 m from the portals, where the shadow
    // map (no sky, ~100 m) cannot tell.
    shadow *= 1.0 - vDark;
    float ao = mix(1.0, nrm.z, 0.8) * (1.0 - 0.75 * vDark);
    vec3 col = shade(albedo, n, v, clamp(nrm.w, 0.25, 1.0), ao, shadow, specScale);
    fragColor = finalColor(atmosphere(col, vRel));
}
"""
    )

    // ---------------------------------------------------------------------------------------------
    // Instanced objects (scenery, race dressing, one-off meshes) with the vertex animations of SceneApi

    private const val OBJECT_VS_BODY = """
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec2 aUv;
layout(location = 3) in vec3 aColor;
layout(location = 4) in float aExtra;
layout(location = 5) in vec4 aInstA;    // x y z yaw
layout(location = 6) in vec4 aInstB;    // scale tint phase variant
uniform vec3 uOffset;                   // batch origin - camera
uniform int uAnim;                      // 0 none, 1 wind, 2 cheer, 3 flag
uniform vec3 uCells;                    // atlas cell by variant: columns (0 = off), cell size u, v
#ifdef SHADOW
uniform mat4 uLightVP;
#else
out vec3 vRel;
out vec3 vNormal;
out vec3 vColor;
#endif
out vec2 vUv;
vec3 unpackTint(float t) {
    float r = floor(t / 65536.0);
    float g = floor((t - r * 65536.0) / 256.0);
    float b = t - r * 65536.0 - g * 256.0;
    return vec3(r, g, b) / 255.0;
}
void main() {
    vec3 p = aPos;
    float time = uSunColor.w;
    float ph = aInstB.z * 6.2831853;
    if (uAnim == 1) {
        // Wind: the whole crown leans with gusts, leaves flutter; weight grows with height (extra).
        float gust = 0.6 + 0.4 * sin(time * 0.37 + ph + aInstA.x * 0.013);
        float sway = sin(time * 1.3 + ph) * 0.5 + sin(time * 2.1 + ph * 1.7) * 0.25;
        vec2 wd = uWind.xy * uWind.z * gust;
        p.xz += (wd * (0.6 + sway) + vec2(sway, -sway) * 0.15) * aExtra * 0.35;
        p.y -= dot(wd, wd) * aExtra * 0.04;
        p += aNormal * sin(time * 7.0 + ph * 3.0 + aPos.y * 2.0) * 0.03 * aExtra;
    } else if (uAnim == 2) {
        // Cheering: the body bobs and jumps now and then, arms (extra) wave. The variant is the energy:
        // 0 a calm crowd sways slowly and nobody jumps, 1 cheers, 2 is a party where most of them jump.
        float energy = clamp(aInstB.w, 0.0, 2.0);
        float amp = 0.35 + 0.65 * min(energy, 1.0) + 0.45 * max(energy - 1.0, 0.0);
        float t = time * (5.5 + aInstB.z * 2.0) * (0.6 + 0.4 * min(energy, 1.0) + 0.15 * max(energy - 1.0, 0.0)) + ph;
        float jumper = step(0.6 - 0.35 * max(energy - 1.0, 0.0), fract(aInstB.z * 7.0)) * step(0.5, energy);
        float bob = abs(sin(t * 0.5)) * 0.06 * amp + max(0.0, sin(t * 0.21 + ph)) * 0.18 * amp * jumper;
        p.y += bob;
        p.y += aExtra * (0.18 + 0.14 * sin(t)) * min(1.0, amp);
        p.x += aExtra * 0.10 * amp * sin(t * 1.3 + 1.0) * sign(aPos.x);
    } else if (uAnim == 3) {
        // Flag: cloth in the local x-y plane, waving along z with a wave travelling away from the pole.
        float t = time * 4.5 + ph;
        p.z += aExtra * 0.22 * sin(t - aExtra * 2.6);
        p.y -= aExtra * 0.04 * (1.0 + sin(t * 0.7 - aExtra));
    }
    float c = cos(aInstA.w);
    float s = sin(aInstA.w);
    p *= aInstB.x;
    // yaw 0 = facing -z (north), counter-clockwise seen from above.
    vec3 rp = vec3(p.x * c + p.z * s, p.y, -p.x * s + p.z * c);
    vec3 rel = rp + aInstA.xyz + uOffset;
    rel.y -= dot(rel.xz, rel.xz) * uGroundAmb.w;
    vUv = aUv;
    // Atlas materials (flags, barrier covers): the mesh addresses cell 0 and the variant picks the cell.
    if (uCells.x > 0.5) vUv += vec2(mod(aInstB.w, uCells.x), floor(aInstB.w / uCells.x)) * uCells.yz;
#ifdef SHADOW
    gl_Position = uLightVP * vec4(rel, 1.0);
#else
    vec3 rn = vec3(aNormal.x * c + aNormal.z * s, aNormal.y, -aNormal.x * s + aNormal.z * c);
    vRel = rel;
    vNormal = rn;
    vColor = aColor * unpackTint(aInstB.y);
    gl_Position = uViewProj * vec4(rel, 1.0);
#endif
}
"""

    val OBJECT_VS = vs(OBJECT_VS_BODY)
    val OBJECT_SHADOW_VS = vs(OBJECT_VS_BODY, "#define SHADOW 1\n")

    val OBJECT_FS = fs(
        """
in vec3 vRel;
in vec3 vNormal;
in vec3 vColor;
in vec2 vUv;
uniform sampler2D uAlbedo;
uniform sampler2D uNormalMap;
uniform int uTex;           // bit 1: albedo, bit 2: normal map
uniform vec2 uUvScale;
uniform float uCutout;
uniform float uRough;
uniform float uFlipBack;    // 0 for foliage cards: their normals already point out of the crown
uniform float uTunnelDark;  // riders only: how deep the player is in a tunnel, 0..1
out vec4 fragColor;
vec3 perturb(vec3 n, vec3 p, vec2 uv, vec2 nxy) {
    vec3 dp1 = dFdx(p);
    vec3 dp2 = dFdy(p);
    vec2 du1 = dFdx(uv);
    vec2 du2 = dFdy(uv);
    vec3 dp2perp = cross(dp2, n);
    vec3 dp1perp = cross(n, dp1);
    vec3 t = dp2perp * du1.x + dp1perp * du2.x;
    vec3 b = dp2perp * du1.y + dp1perp * du2.y;
    float invmax = inversesqrt(max(dot(t, t), dot(b, b)) + 1e-12);
    return normalize(n + (t * nxy.x + b * nxy.y) * invmax * 0.8);
}
void main() {
    vec3 n = normalize(vNormal);
    // Two-sided panels (flags, barrier covers, crowd cards) are lit from the side the camera sees. Leaf cards
    // are not: W2 bends their normals outward from the crown, so a crown is lit as one rounded volume, and
    // flipping them on the back faces would make every card turned away from the camera dark.
    if (!gl_FrontFacing && uFlipBack > 0.5) n = -n;
    float dist = length(vRel);
    vec3 v = -vRel / max(dist, 0.001);
    vec3 albedo = vColor;
    float rough = uRough;
    float ao = 1.0;
    vec2 uv = vUv * uUvScale;
    if ((uTex & 1) != 0) {
        vec4 t = texture(uAlbedo, uv);
        if (t.a < uCutout) discard;
        albedo *= t.rgb;
    }
    if ((uTex & 2) != 0 && dist < 120.0) {
        vec4 nm = texture(uNormalMap, uv);
        n = perturb(n, vRel, uv, nm.xy * 2.0 - 1.0);
        ao = nm.z;
        rough = nm.w;
    }
    float shadow = shadowAt(vRel, n);
    // Riders in a tunnel (0 elsewhere, the default of an unset uniform): no sun, a third of the sky light.
    shadow *= 1.0 - uTunnelDark;
    ao *= 1.0 - 0.65 * uTunnelDark;
    vec3 col = shade(albedo, n, v, rough, ao, shadow, 0.5);
    fragColor = finalColor(atmosphere(col, vRel));
}
"""
    )

    val SHADOW_FS = "#version 300 es\nprecision mediump float;\nin vec2 vUv;\nvoid main() {}\n"

    val SHADOW_CUTOUT_FS = """#version 300 es
precision highp float;
precision highp sampler2D;
in vec2 vUv;
uniform sampler2D uAlbedo;
uniform vec2 uUvScale;
uniform float uCutout;
void main() { if (texture(uAlbedo, vUv * uUvScale).a < uCutout) discard; }
"""

    // ---------------------------------------------------------------------------------------------
    // Riders: rigid parts posed per instance (bike transform, wheel spin about the hub), kit colours

    private const val RIDER_VS_BODY = """
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec2 aUv;
layout(location = 3) in vec3 aColor;
layout(location = 4) in float aExtra;
layout(location = 5) in vec4 aInstA;    // x y z (relative to the batch origin), yaw
layout(location = 6) in vec4 aInstB;    // pitch, lean, kit, spin
layout(location = 7) in vec4 aInstC;    // hub x y z (wheels), scale
uniform vec3 uOffset;
uniform highp sampler2D uKits;          // 8 x kits texels: jersey, accent, shorts, helmet, bike
#ifdef SHADOW
uniform mat4 uLightVP;
#else
out vec3 vRel;
out vec3 vNormal;
out vec3 vColor;
#endif
out vec2 vUv;
vec3 rotX(vec3 p, float a) { float c = cos(a), s = sin(a); return vec3(p.x, p.y * c - p.z * s, p.y * s + p.z * c); }
vec3 rotZ(vec3 p, float a) { float c = cos(a), s = sin(a); return vec3(p.x * c - p.y * s, p.x * s + p.y * c, p.z); }
vec3 rotY(vec3 p, float a) { float c = cos(a), s = sin(a); return vec3(p.x * c + p.z * s, p.y, -p.x * s + p.z * c); }
vec3 pose(vec3 p) {
    // Wheels spin about their hub (forward = -z, so a positive angle rolls forward).
    p = rotX(p, -aInstB.w) + aInstC.xyz;
    p *= aInstC.w;
    p = rotX(p, aInstB.x);   // pitch with the grade (nose up uphill)
    p = rotZ(p, aInstB.y);   // lean into bends
    return rotY(p, aInstA.w);
}
vec3 kitColor(vec3 c) {
    int kit = int(aInstB.z + 0.5);
    int slot = -1;
    if (distance(c, vec3(1.0, 0.0, 1.0)) < 0.02) slot = 0;
    else if (distance(c, vec3(0.0, 1.0, 1.0)) < 0.02) slot = 1;
    else if (distance(c, vec3(1.0, 1.0, 0.0)) < 0.02) slot = 2;
    else if (distance(c, vec3(0.0, 1.0, 0.0)) < 0.02) slot = 3;
    else if (distance(c, vec3(0.0, 0.0, 1.0)) < 0.02) slot = 4;
    if (slot < 0) return c;
    return texelFetch(uKits, ivec2(slot, kit), 0).rgb;
}
void main() {
    vec3 p = pose(aPos);
    vec3 rel = p + aInstA.xyz + uOffset;
    rel.y -= dot(rel.xz, rel.xz) * uGroundAmb.w;
    vUv = aUv;
#ifdef SHADOW
    gl_Position = uLightVP * vec4(rel, 1.0);
#else
    vec3 n = rotY(rotZ(rotX(rotX(aNormal, -aInstB.w), aInstB.x), aInstB.y), aInstA.w);
    vRel = rel;
    vNormal = n;
    vColor = kitColor(aColor);
    gl_Position = uViewProj * vec4(rel, 1.0);
#endif
}
"""

    val RIDER_VS = vs(RIDER_VS_BODY)
    val RIDER_SHADOW_VS = vs(RIDER_VS_BODY, "#define SHADOW 1\n")

    // ---------------------------------------------------------------------------------------------
    // Sky dome with sun and clouds, drawn last where nothing else covered the screen

    val SKY_VS = vs(
        """
layout(location = 0) in vec2 aPos;
out vec2 vNdc;
void main() { vNdc = aPos; gl_Position = vec4(aPos, 1.0, 1.0); }
"""
    )

    val SKY_FS = fs(
        """
in vec2 vNdc;
uniform mat4 uInvViewProj;
uniform sampler2D uNoise;
// Per cloud noise scale (15000, 4300, 1300 m): fract((camera + wind drift) / scale), computed on the CPU in
// Double. Not uCamWorld: it wraps every 1200 m, which none of the scales divides, so the clouds would jump.
uniform vec2 uCloudOff[3];
// Photographed sky (a CC0 HDRI, upper hemisphere, sRGB of radiance / max): x = u of its sun, y = max
// (0 = no panorama, the analytic sky and clouds stay).
uniform sampler2D uSkyTex;
uniform vec2 uSkyParams;
out vec4 fragColor;
void main() {
    vec4 a = uInvViewProj * vec4(vNdc, -1.0, 1.0);
    vec4 b = uInvViewProj * vec4(vNdc, 1.0, 1.0);
    vec3 dir = normalize(b.xyz / b.w - a.xyz / a.w);
    vec3 col = skyColor(dir);
    float mu = dot(dir, uSunDir.xyz);
    bool photo = uSkyParams.y > 0.0;
    if (photo && dir.y > 0.0) {
        // Turned so the panorama's bright side lies where the route's sun is.
        float az = atan(dir.x, -dir.z);
        float saz = atan(uSunDir.x, -uSunDir.z);
        float u = fract((az - saz) / 6.2831853 + uSkyParams.x);
        float v = 1.0 - asin(clamp(dir.y, 0.0, 1.0)) / 1.5707963;
        vec3 ph = textureLod(uSkyTex, vec2(u, v), 0.0).rgb * uSkyParams.y;
        // Low over the horizon the analytic haze stays: it matches the aerial perspective of the terrain.
        col = mix(col, ph, smoothstep(0.02, 0.2, dir.y));
    }
    // Sun disc (0.27 degrees radius) with limb darkening.
    float disc = smoothstep(0.999955, 0.999975, mu);
    col += uSunColor.rgb * disc * 60.0;
    // Fair-weather cumulus on a layer 1800 m above the camera, drifting with the wind.
    if (!photo && dir.y > 0.015 && uSkyZenith.w > 0.0) {
        float t = 1800.0 / dir.y;
        vec2 p = dir.xz * t;
        // Cumulus a few km across: a large blob field, its edges broken up by finer noise.
        float d = texture(uNoise, p / 15000.0 + uCloudOff[0]).b * 0.62 + texture(uNoise, p / 4300.0 + uCloudOff[1] + 0.3).r * 0.26 +
            texture(uNoise, p / 1300.0 + uCloudOff[2] + 0.7).g * 0.12;
        float cover = uSkyZenith.w;
        float dens = smoothstep(1.0 - cover, 1.0 - cover + 0.16, d);
        // Lit tops, grey bases, a silver lining towards the sun.
        float lit = clamp(0.55 + (d - (1.0 - cover)) * 1.8, 0.0, 1.0);
        vec3 base = uSkyHorizon.rgb * 0.95 + uSkyAmb.rgb * 0.4;
        vec3 cloud = mix(base * 0.72, base * 1.05 + uSunColor.rgb * 0.22, lit);
        cloud += uSunColor.rgb * pow(max(mu, 0.0), 12.0) * 0.6 * (1.0 - dens * 0.6);
        float fade = smoothstep(0.015, 0.16, dir.y);
        col = mix(col, cloud, dens * fade * 0.92);
    }
    fragColor = finalColor(col);
}
"""
    )
}
