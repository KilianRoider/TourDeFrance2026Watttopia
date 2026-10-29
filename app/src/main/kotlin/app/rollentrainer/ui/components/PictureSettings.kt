package app.rollentrainer.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.rollentrainer.data.AppSettings
import app.rollentrainer.ui.world.FrameState

/** Copies the picture settings into the renderer's frame state. */
fun FrameState.applyPicture(s: AppSettings) {
    exposure = s.gfxExposure / 100f
    contrast = s.gfxContrast / 100f
    saturation = s.gfxSaturation / 100f
    detail = s.gfxDetail / 100f
    photoSky = s.gfxPhotoSky
}

/**
 * Sliders for the picture of the 3D world: exposure, contrast, colour and texture detail, plus the
 * photographed sky. [onLive] gets every change while a slider moves (the ride shows it at once);
 * [update] saves when the finger lifts.
 */
@Composable
fun PictureSettings(
    settings: AppSettings,
    update: ((AppSettings) -> AppSettings) -> Unit,
    onLive: ((AppSettings) -> Unit)? = null,
    textColor: Color = Color.Unspecified,
) {
    Column(Modifier.fillMaxWidth()) {
        PictureSlider("Belichtung", settings.gfxExposure, 50..160, textColor, { v -> onLive?.invoke(settings.copy(gfxExposure = v)) }) { v ->
            update { it.copy(gfxExposure = v) }
        }
        PictureSlider("Kontrast", settings.gfxContrast, 70..150, textColor, { v -> onLive?.invoke(settings.copy(gfxContrast = v)) }) { v ->
            update { it.copy(gfxContrast = v) }
        }
        PictureSlider("Farbe", settings.gfxSaturation, 50..160, textColor, { v -> onLive?.invoke(settings.copy(gfxSaturation = v)) }) { v ->
            update { it.copy(gfxSaturation = v) }
        }
        PictureSlider("Struktur von Straße und Gras", settings.gfxDetail, 0..250, textColor, { v -> onLive?.invoke(settings.copy(gfxDetail = v)) }) { v ->
            update { it.copy(gfxDetail = v) }
        }
        Row(
            Modifier.fillMaxWidth().clickable {
                val v = !settings.gfxPhotoSky
                onLive?.invoke(settings.copy(gfxPhotoSky = v))
                update { it.copy(gfxPhotoSky = v) }
            }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Fotografierter Himmel", color = textColor, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(settings.gfxPhotoSky, { v ->
                onLive?.invoke(settings.copy(gfxPhotoSky = v))
                update { it.copy(gfxPhotoSky = v) }
            })
        }
        TextButton(onClick = {
            val d = AppSettings()
            val reset: (AppSettings) -> AppSettings = {
                it.copy(gfxExposure = d.gfxExposure, gfxContrast = d.gfxContrast, gfxSaturation = d.gfxSaturation, gfxDetail = d.gfxDetail, gfxPhotoSky = d.gfxPhotoSky)
            }
            onLive?.invoke(reset(settings))
            update(reset)
        }) { Text("Zurücksetzen") }
    }
}

@Composable
private fun PictureSlider(title: String, value: Int, range: IntRange, textColor: Color, onLive: (Int) -> Unit, onDone: (Int) -> Unit) {
    // A local value while dragging; the settings are written once when the finger lifts.
    var v by remember(value) { mutableStateOf(value.toFloat()) }
    Text("$title ${v.toInt()} %", color = textColor, style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = v,
        onValueChange = { x -> v = x; onLive(x.toInt()) },
        onValueChangeFinished = { onDone(v.toInt()) },
        valueRange = range.first.toFloat()..range.last.toFloat(),
    )
}
