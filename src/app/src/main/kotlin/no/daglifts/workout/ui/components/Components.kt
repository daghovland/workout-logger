package no.daglifts.workout.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import no.daglifts.workout.ui.theme.BorderColor
import no.daglifts.workout.ui.theme.LocalWorkoutColors
import no.daglifts.workout.ui.theme.Surface2Color

/**
 * Stepper control: [−] [value] [+]
 * Mirrors the .stepper / .sbtn / .sinput elements in the PWA.
 */
@Composable
fun Stepper(
    value: String,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
) {
    val colors = LocalWorkoutColors.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surface2)
            .border(1.dp, colors.border, RoundedCornerShape(10.dp))
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onDecrement, modifier = Modifier.width(40.dp)) {
            Icon(Icons.Default.Remove, contentDescription = "Decrease", tint = colors.text)
        }
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.width(60.dp),
            textStyle = androidx.compose.ui.text.TextStyle(
                color = colors.text,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            ),
            placeholder = { Text(placeholder, textAlign = TextAlign.Center, color = colors.muted) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        )
        IconButton(onClick = onIncrement, modifier = Modifier.width(40.dp)) {
            Icon(Icons.Default.Add, contentDescription = "Increase", tint = colors.text)
        }
    }
}

/**
 * Chip showing a logged set value, e.g. "80 kg × 5"
 */
@Composable
fun SetChip(
    label: String,
    index: Int,
    isLatest: Boolean = false,
    onDelete: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LocalWorkoutColors.current
    val borderColor = if (isLatest) colors.accent else colors.border
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surface2)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .padding(horizontal = 9.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${index + 1}", color = colors.muted, fontSize = 11.sp)
        Text(label, color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        if (onDelete != null) {
            Text(
                "×",
                color = colors.muted,
                fontSize = 15.sp,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDelete,
                ),
            )
        }
    }
}
