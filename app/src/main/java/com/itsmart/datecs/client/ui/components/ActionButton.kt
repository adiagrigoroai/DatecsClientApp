package com.itsmart.datecs.client.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itsmart.datecs.client.ui.theme.AppColors

@Composable
fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = AppColors.Primary,
    danger: Boolean = false,
    outline: Boolean = false,
    small: Boolean = false,
    full: Boolean = false,
    enabled: Boolean = true
) {
    val bg = if (danger) AppColors.Danger else color
    val btnModifier = modifier.then(
        if (full) Modifier.fillMaxWidth() else Modifier
    ).height(if (small) 44.dp else 50.dp)

    if (outline) {
        OutlinedButton(
            onClick = onClick,
            modifier = btnModifier,
            enabled = enabled,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.5.dp, if (enabled) bg else bg.copy(alpha = 0.45f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = bg)
        ) {
            Text(text, fontWeight = FontWeight.W700, fontSize = if (small) 13.sp else 14.sp)
        }
    } else {
        Button(
            onClick = onClick,
            modifier = btnModifier,
            enabled = enabled,
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = bg)
        ) {
            Text(text, fontWeight = FontWeight.W700, fontSize = if (small) 13.sp else 14.sp,
                color = Color.White)
        }
    }
}
