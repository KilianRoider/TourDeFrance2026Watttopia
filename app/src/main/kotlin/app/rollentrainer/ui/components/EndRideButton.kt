package app.rollentrainer.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * "Beenden" during a ride: always in the top right corner, red with a white rim, so it reads on a bright
 * sky as well as on the dark studio. It only asks; the end dialog of the screen does the rest.
 */
@Composable
fun EndRideButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, compact: Boolean = false) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(2.dp, Color.White.copy(alpha = 0.9f)),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F), contentColor = Color.White),
        contentPadding = PaddingValues(horizontal = if (compact) 12.dp else 18.dp),
    ) {
        Icon(Icons.Default.Stop, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text("Beenden", fontWeight = FontWeight.Bold, maxLines = 1)
    }
}
