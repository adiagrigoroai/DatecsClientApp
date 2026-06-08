package com.itsmart.datecs.client.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itsmart.datecs.client.ui.theme.AppColors

@Composable
fun SectionCard(
    title: String? = null,
    accent: Color? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val accentColor = accent ?: AppColors.Primary
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .border(1.dp, AppColors.Border, RoundedCornerShape(14.dp))
            .then(
                if (accent != null) Modifier.drawBehind {
                    drawRect(
                        color = accentColor,
                        topLeft = Offset.Zero,
                        size = androidx.compose.ui.geometry.Size(4.dp.toPx(), size.height)
                    )
                } else Modifier
            ),
        shape = RoundedCornerShape(14.dp),
        color = AppColors.Card,
        shadowElevation = 1.dp
    ) {
        Column {
            if (title != null) {
                Text(
                    text = title.uppercase(),
                    color = accentColor,
                    fontWeight = FontWeight.W700,
                    fontSize = 13.sp,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)
                )
            }
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp, top = 4.dp),
                content = content
            )
        }
    }
}
