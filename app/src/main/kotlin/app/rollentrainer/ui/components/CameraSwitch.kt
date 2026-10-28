package app.rollentrainer.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** The app's accent; the HUD over the 3D view has fixed colours, so it does not come from the theme there. */
private val HudAccent = Color(0xFFFF5A1F)

private const val CHASE_LABEL = "Hinter dem Rad"
private const val FIRST_PERSON_LABEL = "Ego-Perspektive"

/**
 * Camera choice for the virtual routes as two small pictures with a sliding switch between them:
 * left the chase camera (a rider seen from behind), right the rider's own view over the handlebars.
 * Tapping a picture or the switch changes the camera; the chosen side is highlighted.
 *
 * [compact] drops the captions and shrinks the pictures for the ride overlay; [onDark] uses fixed
 * light-on-dark colours for the translucent HUD over the 3D world instead of the theme's.
 */
@Composable
fun CameraSwitch(
    firstPerson: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onDark: Boolean = false,
) {
    if (compact) {
        CameraSwitchRow(firstPerson, onChange, modifier, compact = true, onDark = onDark, pictureWidth = 58.dp, pictureHeight = 38.dp)
        return
    }
    BoxWithConstraints(modifier) {
        // Both pictures, the switch (58 dp) and the gaps (28 dp) must also fit the 280 dp panel of a small
        // phone, so the pictures shrink with the space, down to 72 dp, keeping their proportions.
        val room = if (maxWidth == Dp.Infinity) 104.dp else (maxWidth - 86.dp) / 2
        val width = room.coerceIn(72.dp, 104.dp)
        CameraSwitchRow(firstPerson, onChange, Modifier, compact = false, onDark = onDark, pictureWidth = width, pictureHeight = width * (68f / 104f))
    }
}

@Composable
private fun CameraSwitchRow(
    firstPerson: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier,
    compact: Boolean,
    onDark: Boolean,
    pictureWidth: Dp,
    pictureHeight: Dp,
) {
    val accent = if (onDark) HudAccent else MaterialTheme.colorScheme.primary
    val idleBorder = if (onDark) Color.White.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outline
    val track = if (onDark) Color.White.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceContainerHighest
    val labelIdle = if (onDark) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant
    // 0 = chase camera, 1 = first person; the thumb and both highlights follow the same animated value.
    val position by animateFloatAsState(if (firstPerson) 1f else 0f, tween(260), label = "camera")

    Row(
        modifier.selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 10.dp),
    ) {
        CameraOption(
            label = CHASE_LABEL,
            selected = !firstPerson,
            highlight = 1f - position,
            accent = accent,
            idleBorder = idleBorder,
            labelIdle = labelIdle,
            compact = compact,
            width = pictureWidth,
            height = pictureHeight,
            onClick = { if (firstPerson) onChange(false) },
        ) { drawChaseView() }
        SlideToggle(firstPerson, position, accent, track, compact, onChange)
        CameraOption(
            label = FIRST_PERSON_LABEL,
            selected = firstPerson,
            highlight = position,
            accent = accent,
            idleBorder = idleBorder,
            labelIdle = labelIdle,
            compact = compact,
            width = pictureWidth,
            height = pictureHeight,
            onClick = { if (!firstPerson) onChange(true) },
        ) { drawFirstPersonView() }
    }
}

@Composable
private fun CameraOption(
    label: String,
    selected: Boolean,
    highlight: Float,
    accent: Color,
    idleBorder: Color,
    labelIdle: Color,
    compact: Boolean,
    width: Dp,
    height: Dp,
    onClick: () -> Unit,
    picture: DrawScope.() -> Unit,
) {
    val shape = RoundedCornerShape(if (compact) 6.dp else 10.dp)
    val border by animateColorAsState(if (selected) accent else idleBorder, tween(260), label = "border")
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            // Without a caption the picture needs a name for TalkBack; with one, the caption is read.
            .then(if (compact) Modifier.semantics { contentDescription = label } else Modifier)
            // The compact pictures are smaller than a finger; the padding makes the target 48 dp high.
            .padding(horizontal = 2.dp, vertical = if (compact) 5.dp else 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(
            Modifier
                .size(width, height)
                // The side not chosen fades back, so the choice reads at a glance even without the border.
                .graphicsLayer { alpha = 0.55f + 0.45f * highlight }
                .clip(shape)
                .border(if (selected) 2.dp else 1.dp, border, shape),
        ) { picture() }
        if (!compact) {
            Spacer(Modifier.height(4.dp))
            // As wide as the picture: with a large system font the caption wraps instead of being cut.
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) accent else labelIdle,
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier = Modifier.width(width),
            )
        }
    }
}

/** A pill with a thumb that slides to the chosen side; the thumb carries a small camera. */
@Composable
private fun SlideToggle(firstPerson: Boolean, position: Float, accent: Color, track: Color, compact: Boolean, onChange: (Boolean) -> Unit) {
    val trackWidth = if (compact) 40.dp else 50.dp
    val trackHeight = if (compact) 24.dp else 28.dp
    val inset = 3.dp
    val thumb = trackHeight - inset * 2
    Box(
        Modifier
            .clip(RoundedCornerShape(24.dp))
            .toggleable(value = firstPerson, role = Role.Switch, onValueChange = onChange)
            .semantics {
                contentDescription = "Kamera umschalten"
                stateDescription = if (firstPerson) FIRST_PERSON_LABEL else CHASE_LABEL
            }
            // Touch target: the visible pill is 24-28 dp high, the tappable area 48 dp.
            .padding(horizontal = 4.dp, vertical = (48.dp - trackHeight) / 2),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(trackWidth, trackHeight).clip(CircleShape).background(track)) {
            Box(
                Modifier
                    .offset {
                        val travel = (trackWidth - thumb - inset * 2).toPx()
                        IntOffset((inset.toPx() + travel * position).roundToInt(), inset.roundToPx())
                    }
                    .size(thumb)
                    .clip(CircleShape)
                    .background(accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Videocam, contentDescription = null, tint = Color.White, modifier = Modifier.size(thumb * 0.62f))
            }
        }
    }
}

// --- The two pictures, drawn as vector shapes so they stay sharp at any size and need no assets. ---

private val SkyTop = Color(0xFF6FAEE6)
private val SkyHorizon = Color(0xFFCFE5F6)
private val Hills = Color(0xFF8CAF86)
private val GrassNear = Color(0xFF4F8A3B)
private val GrassFar = Color(0xFF79AE58)
private val Asphalt = Color(0xFF575C63)
private val RoadLine = Color(0xFFF4F4F4)
private val Skin = Color(0xFFE2B48E)
private val Shorts = Color(0xFF1E2126)
private val Jersey = Color(0xFFF7D02C)
private val JerseyShade = Color(0xFFD9AE12)
private val Helmet = Color(0xFF2C3036)
private val BikeDark = Color(0xFF1C1F24)
private val Hood = Color(0xFF30353C)

/** Sky, hills and grass up to the horizon at [horizon] (fraction of the height). */
private fun DrawScope.landscape(horizon: Float) {
    val hy = size.height * horizon
    drawRect(Brush.verticalGradient(listOf(SkyTop, SkyHorizon), startY = 0f, endY = hy), Offset.Zero, Size(size.width, hy))
    val hills = Path().apply {
        moveTo(0f, hy)
        lineTo(0f, hy - size.height * 0.06f)
        quadraticTo(size.width * 0.18f, hy - size.height * 0.14f, size.width * 0.36f, hy - size.height * 0.05f)
        quadraticTo(size.width * 0.58f, hy - size.height * 0.17f, size.width * 0.8f, hy - size.height * 0.06f)
        quadraticTo(size.width * 0.92f, hy - size.height * 0.1f, size.width, hy - size.height * 0.07f)
        lineTo(size.width, hy)
        close()
    }
    drawPath(hills, Hills)
    drawRect(Brush.verticalGradient(listOf(GrassFar, GrassNear), startY = hy, endY = size.height), Offset(0f, hy), Size(size.width, size.height - hy))
}

/** A road from the vanishing point at the horizon to [bottomLeft]..[bottomRight] (fractions of the width), with dashes. */
private fun DrawScope.road(horizon: Float, bottomLeft: Float, bottomRight: Float) {
    val hy = size.height * horizon
    val cx = size.width * 0.5f
    val top = size.width * 0.025f
    val path = Path().apply {
        moveTo(cx - top, hy)
        lineTo(cx + top, hy)
        lineTo(size.width * bottomRight, size.height)
        lineTo(size.width * bottomLeft, size.height)
        close()
    }
    drawPath(path, Asphalt)
    val edge = size.minDimension * 0.02f
    drawLine(RoadLine.copy(alpha = 0.7f), Offset(cx - top, hy), Offset(size.width * bottomLeft, size.height), edge)
    drawLine(RoadLine.copy(alpha = 0.7f), Offset(cx + top, hy), Offset(size.width * bottomRight, size.height), edge)
    // Centre dashes grow towards the viewer, which sells the perspective.
    for ((a, b) in listOf(0.08f to 0.16f, 0.27f to 0.42f, 0.56f to 0.8f)) {
        val y0 = hy + (size.height - hy) * a
        val y1 = hy + (size.height - hy) * b
        drawLine(RoadLine, Offset(cx, y0), Offset(cx, y1), size.minDimension * (0.012f + 0.03f * b), StrokeCap.Butt)
    }
}

/** Chase camera: the road ahead and a rider in a yellow jersey seen from behind. */
internal fun DrawScope.drawChaseView() {
    landscape(0.42f)
    road(0.42f, 0.1f, 0.9f)
    val u = size.height * 0.062f // one unit; the rider stands about ten units tall
    val cx = size.width * 0.5f
    val ground = size.height * 0.95f
    fun p(x: Float, y: Float) = Offset(cx + x * u, ground - y * u)
    // Shadow on the road.
    drawOval(Color.Black.copy(alpha = 0.28f), topLeft = p(-1.6f, 0.35f), size = Size(3.2f * u, 0.7f * u))
    // Rear wheel seen edge-on.
    drawRoundRect(BikeDark, topLeft = p(-0.42f, 4.6f), size = Size(0.84f * u, 4.6f * u), cornerRadius = CornerRadius(0.42f * u))
    // Legs: the left pedal down, the right one up; shorts on the thighs, skin below, white shoes.
    val thigh = 1.05f * u
    val shin = 0.8f * u
    drawLine(Shorts, p(-0.9f, 5.6f), p(-1.15f, 3.7f), thigh, StrokeCap.Round)
    drawLine(Skin, p(-1.15f, 3.7f), p(-0.85f, 1.3f), shin, StrokeCap.Round)
    drawLine(Shorts, p(0.9f, 5.6f), p(1.25f, 4.3f), thigh, StrokeCap.Round)
    drawLine(Skin, p(1.25f, 4.3f), p(0.9f, 2.9f), shin, StrokeCap.Round)
    drawRoundRect(RoadLine, topLeft = p(-1.3f, 1.4f), size = Size(0.9f * u, 0.55f * u), cornerRadius = CornerRadius(0.25f * u))
    drawRoundRect(RoadLine, topLeft = p(0.45f, 3.0f), size = Size(0.9f * u, 0.55f * u), cornerRadius = CornerRadius(0.25f * u))
    // Saddle and shorts across the hips.
    drawRoundRect(Shorts, topLeft = p(-1.55f, 6.4f), size = Size(3.1f * u, 1.3f * u), cornerRadius = CornerRadius(0.6f * u))
    // Arms reach forward to the bars, so from behind they angle out and down from the shoulders.
    drawLine(Jersey, p(-1.65f, 8.3f), p(-2.05f, 7.3f), 0.8f * u, StrokeCap.Round)
    drawLine(Skin, p(-2.05f, 7.3f), p(-2.2f, 6.3f), 0.62f * u, StrokeCap.Round)
    drawLine(Jersey, p(1.65f, 8.3f), p(2.05f, 7.3f), 0.8f * u, StrokeCap.Round)
    drawLine(Skin, p(2.05f, 7.3f), p(2.2f, 6.3f), 0.62f * u, StrokeCap.Round)
    // The back: narrow at the waist, wide at the shoulders.
    val back = Path().apply {
        val a = p(-1.45f, 6.1f)
        moveTo(a.x, a.y)
        val b = p(1.45f, 6.1f); lineTo(b.x, b.y)
        val c = p(1.95f, 8.5f); lineTo(c.x, c.y)
        val d = p(0f, 8.95f); quadraticTo(p(1.0f, 8.95f).x, d.y, d.x, d.y)
        val e = p(-1.95f, 8.5f); quadraticTo(p(-1.0f, 8.95f).x, d.y, e.x, e.y)
        close()
    }
    drawPath(back, Jersey)
    drawLine(JerseyShade, p(0f, 6.2f), p(0f, 8.6f), 0.18f * u, StrokeCap.Round)
    // Helmet with a light edge, the head tucked low between the shoulders.
    drawOval(Helmet, topLeft = p(-1.0f, 10.2f), size = Size(2.0f * u, 1.75f * u))
    drawArc(Color.White.copy(alpha = 0.35f), 200f, 140f, false, topLeft = p(-0.8f, 10.0f), size = Size(1.6f * u, 1.4f * u), style = Stroke(0.16f * u))
}

/** First person: the road straight ahead over the stem, the bar, the hoods and the hands on them. */
internal fun DrawScope.drawFirstPersonView() {
    landscape(0.38f)
    road(0.38f, -0.15f, 1.15f)
    val w = size.width
    val h = size.height
    val cx = w * 0.5f
    // Front tyre below the stem.
    drawRoundRect(BikeDark, topLeft = Offset(cx - w * 0.028f, h * 0.84f), size = Size(w * 0.056f, h * 0.3f), cornerRadius = CornerRadius(w * 0.028f))
    // Stem, coming towards the rider.
    val stem = Path().apply {
        moveTo(cx - w * 0.03f, h * 0.73f)
        lineTo(cx + w * 0.03f, h * 0.73f)
        lineTo(cx + w * 0.055f, h * 1.02f)
        lineTo(cx - w * 0.055f, h * 1.02f)
        close()
    }
    drawPath(stem, Hood)
    // The bar: a gentle curve from hood to hood.
    val bar = Path().apply {
        moveTo(w * 0.1f, h * 0.84f)
        quadraticTo(cx, h * 0.68f, w * 0.9f, h * 0.84f)
    }
    drawPath(bar, BikeDark, style = Stroke(h * 0.075f, cap = StrokeCap.Round))
    // Hoods rise forward from the bar ends.
    val hoodWidth = h * 0.085f
    drawLine(Hood, Offset(w * 0.12f, h * 0.86f), Offset(w * 0.17f, h * 0.63f), hoodWidth, StrokeCap.Round)
    drawLine(Hood, Offset(w * 0.88f, h * 0.86f), Offset(w * 0.83f, h * 0.63f), hoodWidth, StrokeCap.Round)
    // Hands resting on the hoods.
    drawOval(Skin, topLeft = Offset(w * 0.08f, h * 0.66f), size = Size(w * 0.13f, h * 0.17f))
    drawOval(Skin, topLeft = Offset(w * 0.79f, h * 0.66f), size = Size(w * 0.13f, h * 0.17f))
    drawLine(Shorts, Offset(w * 0.05f, h * 0.9f), Offset(w * 0.1f, h * 0.8f), h * 0.1f, StrokeCap.Round)
    drawLine(Shorts, Offset(w * 0.95f, h * 0.9f), Offset(w * 0.9f, h * 0.8f), h * 0.1f, StrokeCap.Round)
    // A bike computer on the stem, so the view reads as "from the saddle".
    drawRoundRect(BikeDark, topLeft = Offset(cx - w * 0.065f, h * 0.62f), size = Size(w * 0.13f, h * 0.1f), cornerRadius = CornerRadius(h * 0.02f))
    drawRoundRect(HudAccent, topLeft = Offset(cx - w * 0.045f, h * 0.645f), size = Size(w * 0.09f, h * 0.035f), cornerRadius = CornerRadius(h * 0.01f))
}
