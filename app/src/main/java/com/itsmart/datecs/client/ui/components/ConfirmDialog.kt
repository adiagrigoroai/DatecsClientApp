package com.itsmart.datecs.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.itsmart.datecs.client.ui.theme.AppColors

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    danger: Boolean = false,
    doubleConfirm: Boolean = false,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    var step by remember { mutableIntStateOf(0) }

    Dialog(onDismissRequest = onCancel) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = AppColors.Card,
            shadowElevation = 16.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (danger) "\u26A0\uFE0F" else "\u2753",
                    fontSize = 40.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    text = title,
                    fontWeight = FontWeight.W800,
                    fontSize = 16.sp,
                    color = if (danger) AppColors.Danger else AppColors.TextPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    text = message,
                    fontSize = 12.5.sp,
                    color = AppColors.TextSecondary,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(bottom = 20.dp)
                )

                if (doubleConfirm && step == 0) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ActionButton(
                            text = "Anulare", onClick = onCancel,
                            outline = true, color = AppColors.TextSecondary,
                            modifier = Modifier.weight(1f)
                        )
                        ActionButton(
                            text = "Continua", onClick = { step = 1 },
                            danger = danger, color = if (danger) AppColors.Danger else AppColors.Primary,
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else if (doubleConfirm && step == 1) {
                    Text(
                        text = "Confirmati din nou - aceasta actiune este IREVERSIBILA!",
                        fontSize = 11.5.sp,
                        color = AppColors.Danger,
                        fontWeight = FontWeight.W700,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(AppColors.DangerBg, RoundedCornerShape(8.dp))
                            .padding(8.dp, 12.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ActionButton(
                            text = "Anulare", onClick = onCancel,
                            outline = true, color = AppColors.TextSecondary,
                            modifier = Modifier.weight(1f)
                        )
                        ActionButton(
                            text = "CONFIRM DEFINITIV", onClick = onConfirm,
                            danger = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ActionButton(
                            text = "Anulare", onClick = onCancel,
                            outline = true, color = AppColors.TextSecondary,
                            modifier = Modifier.weight(1f)
                        )
                        ActionButton(
                            text = "Confirma", onClick = onConfirm,
                            danger = danger, color = if (danger) AppColors.Danger else AppColors.Primary,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}
