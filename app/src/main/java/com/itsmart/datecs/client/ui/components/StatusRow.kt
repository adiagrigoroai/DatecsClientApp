package com.itsmart.datecs.client.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Divider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itsmart.datecs.client.ui.theme.AppColors

@Composable
fun StatusRow(
    label: String,
    value: String,
    mono: Boolean = false,
    accent: Color? = null
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = AppColors.TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = value,
                fontWeight = FontWeight.W700,
                fontSize = 13.sp,
                color = accent ?: AppColors.TextPrimary,
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1.2f)
            )
        }
        Divider(color = AppColors.Divider, thickness = 1.dp)
    }
}
