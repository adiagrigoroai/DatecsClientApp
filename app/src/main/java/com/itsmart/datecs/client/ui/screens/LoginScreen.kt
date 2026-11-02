package com.itsmart.datecs.client.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.itsmart.datecs.client.ui.components.SafeCircularProgress
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itsmart.datecs.client.ui.theme.AppColors

@Composable
fun LoginScreen(
    isLoading: Boolean,
    errorMessage: String,
    onLogin: (String, String) -> Unit,
    onEnroll: () -> Unit = {}
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp)
                .align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Logo/Title
            androidx.compose.foundation.Image(
                androidx.compose.ui.res.painterResource(com.itsmart.datecs.client.R.drawable.itsmart_mark), null,
                modifier = Modifier.size(84.dp).padding(bottom = 12.dp)
            )
            Text(
                "IT Smart Client",
                color = AppColors.PrimaryDark,
                fontSize = 28.sp,
                fontWeight = FontWeight.W800
            )
            Text(
                "Revizie, rapoarte si export XML pentru casele de marcat",
                color = AppColors.TextSecondary,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp)
            )
            Box(Modifier.width(120.dp).height(3.dp).background(Brush.horizontalGradient(AppColors.BrandGradient), RoundedCornerShape(2.dp)))
            Spacer(modifier = Modifier.height(28.dp))

            // Login card
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = Color.White,
                shadowElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Autentificare",
                        fontWeight = FontWeight.W700,
                        fontSize = 18.sp,
                        color = AppColors.TextPrimary,
                        modifier = Modifier.padding(bottom = 20.dp)
                    )

                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Email") },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                    )

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Parola") },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = null,
                                    tint = AppColors.TextTertiary
                                )
                            }
                        }
                    )

                    if (errorMessage.isNotEmpty()) {
                        Text(
                            errorMessage,
                            color = AppColors.Danger,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.W600,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(AppColors.DangerBg, RoundedCornerShape(8.dp))
                                .padding(10.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    Button(
                        onClick = { onLogin(username, password) },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        enabled = !isLoading && username.isNotEmpty() && password.isNotEmpty(),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Primary)
                    ) {
                        if (isLoading) {
                            SafeCircularProgress(
                                modifier = Modifier.size(20.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                "Autentificare",
                                fontWeight = FontWeight.W700,
                                fontSize = 15.sp,
                                color = Color.White
                            )
                        }
                    }
                }
            }

            TextButton(onClick = onEnroll, modifier = Modifier.padding(top = 12.dp)) {
                Text("Nu aveti cont? Inregistrati firma", color = AppColors.Primary, fontWeight = FontWeight.W700, fontSize = 14.sp)
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "cloud.itsmart.ro",
                color = AppColors.TextTertiary,
                fontSize = 10.sp
            )
        }
    }
}
