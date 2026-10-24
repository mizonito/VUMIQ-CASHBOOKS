package com.vumiq.cashbook.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vumiq.cashbook.R
import com.vumiq.cashbook.ui.components.VumiqBrandEmblem
import com.vumiq.cashbook.ui.theme.VumiqForest
import com.vumiq.cashbook.ui.theme.VumiqMint
import com.vumiq.cashbook.ui.theme.VumiqSplashObsidian

/**
 * Startup & resume Biometric Lock Gate screen.
 * Automatically triggers the native system BiometricPrompt as soon as the splash screen completes
 * for effortless one-tap Fingerprint or Face unlock, with a manual retry button if dismissed.
 */
@Composable
fun BiometricLockScreen(
    autoPromptTriggerKey: Int,
    errorMessage: String?,
    onRequestBiometricPrompt: () -> Unit
) {
    LaunchedEffect(autoPromptTriggerKey) {
        onRequestBiometricPrompt()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF102A1E),
                        VumiqSplashObsidian
                    ),
                    center = Offset(540f, 760f),
                    radius = 1400f
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            VumiqBrandEmblem(size = 68.dp)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "VUMIQ Cashbook",
                color = Color(0xFFFAF8F4),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp
            )
            Spacer(modifier = Modifier.height(28.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = Color(0xFF111D18),
                border = BorderStroke(1.dp, Color(0xFF21382E))
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 26.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        modifier = Modifier.size(56.dp),
                        shape = CircleShape,
                        color = Color(0xFF163226)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Outlined.Lock,
                                contentDescription = null,
                                tint = VumiqMint,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }

                    Text(
                        text = stringResource(R.string.biometric_lock_headline),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF2F5F4),
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = stringResource(R.string.biometric_lock_subtext),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF9FB0AA),
                        textAlign = TextAlign.Center
                    )

                    if (!errorMessage.isNullOrBlank()) {
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.labelLarge,
                            color = Color(0xFFF87171),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Button(
                        onClick = onRequestBiometricPrompt,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = VumiqForest,
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Fingerprint,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.biometric_unlock_cta),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            HorizontalDivider(
                modifier = Modifier.width(56.dp),
                thickness = 1.dp,
                color = Color(0xFF234737)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.brand_ownership),
                color = Color(0xFFD8E4DF),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
        }
    }
}
