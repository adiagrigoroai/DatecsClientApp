package com.itsmart.datecs.client.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itsmart.datecs.client.ui.theme.AppColors

/** Fundalul albastru al antetului (macheta IT Smart Cloud). */
val HeaderBrush = Brush.verticalGradient(listOf(Color(0xFF0A2A8F), Color(0xFF1250E6)))

/** Antet albastru: titlu alb, optional sageata inapoi si actiuni in dreapta. */
@Composable
fun AppHeader(title: String, subtitle: String? = null, onBack: (() -> Unit)? = null, leading: @Composable (() -> Unit)? = null,
              actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().background(HeaderBrush).statusBarsPadding().padding(start = 6.dp, end = 10.dp, top = 8.dp, bottom = 18.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Inapoi", tint = Color.White) }
        } else {
            Spacer(Modifier.width(10.dp))
            leading?.invoke()
            if (leading != null) Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.W800, maxLines = 1)
            if (subtitle != null) Text(subtitle, color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp, maxLines = 1)
        }
        actions()
    }
}

/** Card alb rotunjit, cu umbra fina. */
@Composable
fun SoftCard(modifier: Modifier = Modifier, color: Color = Color.White, border: Color? = null, padding: Dp = 16.dp,
             onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    val m = modifier.fillMaxWidth()
    if (onClick != null) {
        Surface(onClick = onClick, modifier = m, shape = shape, color = color, shadowElevation = 2.dp,
            border = border?.let { BorderStroke(1.dp, it) }) { Column(Modifier.padding(padding), content = content) }
    } else {
        Surface(modifier = m, shape = shape, color = color, shadowElevation = 2.dp,
            border = border?.let { BorderStroke(1.dp, it) }) { Column(Modifier.padding(padding), content = content) }
    }
}

/** Placa din ecranul Acasa: iconita, titlu pe doua randuri, sageata. */
@Composable
fun TileCard(icon: ImageVector, title: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    SoftCard(modifier = modifier.height(118.dp), onClick = onClick, padding = 14.dp) {
        Icon(icon, null, tint = AppColors.Primary, modifier = Modifier.size(32.dp))
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.W700, color = AppColors.TextPrimary, lineHeight = 19.sp,
                modifier = Modifier.weight(1f))
            Icon(Icons.Filled.ChevronRight, null, tint = AppColors.Primary, modifier = Modifier.size(20.dp))
        }
    }
}

/** Banda colorata cu iconita rotunda: stare (verde), informatie (albastru), atentie (portocaliu). */
@Composable
fun NoticeCard(icon: ImageVector, title: String, text: String, tone: Color, bg: Color, onClick: (() -> Unit)? = null,
               trailing: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(bg)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(tone), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.W800, color = AppColors.TextPrimary)
            if (text.isNotBlank()) Text(text, fontSize = 12.5.sp, color = AppColors.TextSecondary, lineHeight = 17.sp)
        }
        trailing?.invoke()
    }
}

/** Buton principal plin, cu iconita. */
@Composable
fun PrimaryButton(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier, enabled: Boolean = true,
                  color: Color = AppColors.Primary, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White)) {
        if (icon != null) { Icon(icon, null, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
        // culoarea explicit: stilurile de text din tema au culoare proprie si ar acoperi contentColor
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.W700, color = if (enabled) Color.White else AppColors.TextTertiary)
    }
}

/** Buton cu contur albastru (actiune secundara). */
@Composable
fun OutlineButton(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier, enabled: Boolean = true,
                  color: Color = AppColors.Primary, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.5.dp, color), colors = ButtonDefaults.outlinedButtonColors(contentColor = color)) {
        if (icon != null) { Icon(icon, null, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.W700, color = if (enabled) color else AppColors.TextTertiary)
    }
}

/** Rand eticheta / valoare cu iconita (detaliile aparatului). */
@Composable
fun InfoLine(icon: ImageVector, label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = AppColors.TextSecondary, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.width(96.dp))
        Text(value, fontSize = 13.5.sp, fontWeight = FontWeight.W600, color = AppColors.TextPrimary, maxLines = 2)
    }
}
