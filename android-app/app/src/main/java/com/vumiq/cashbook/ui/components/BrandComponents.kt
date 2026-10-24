package com.vumiq.cashbook.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vumiq.cashbook.R
import com.vumiq.cashbook.ui.theme.LocalVumiqColors
import com.vumiq.cashbook.ui.theme.VumiqForest
import com.vumiq.cashbook.ui.theme.VumiqMint
import com.vumiq.cashbook.ui.theme.VumiqSplashObsidian

@Composable
fun VumiqBrandEmblem(
    size: Dp = 36.dp,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.size(size),
        shape = RoundedCornerShape(size * 0.28f),
        color = VumiqForest,
        shadowElevation = 0.dp
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = this.size.width
            val h = this.size.height

            val outerPath = Path().apply {
                moveTo(w * 0.25f, h * 0.29f)
                lineTo(w * 0.50f, h * 0.74f)
                lineTo(w * 0.75f, h * 0.29f)
            }
            drawPath(
                path = outerPath,
                color = Color.White,
                style = Stroke(
                    width = w * 0.105f,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )

            val innerPath = Path().apply {
                moveTo(w * 0.38f, h * 0.29f)
                lineTo(w * 0.50f, h * 0.51f)
                lineTo(w * 0.62f, h * 0.29f)
            }
            drawPath(
                path = innerPath,
                color = VumiqMint,
                style = Stroke(
                    width = w * 0.085f,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }
    }
}

/**
 * Premium 2026 VUMIQ Launch Splash Screen displaying the VUMIQ wordmark,
 * Cashbook identity, and "Owned by Mr. Amin M. Gutale".
 */
@Composable
fun VumiqLaunchSplashOverlay(
    visible: Boolean,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(180)),
        exit = fadeOut(animationSpec = tween(320)),
        modifier = modifier
    ) {
        var reveal by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { reveal = true }

        val logoScale by animateFloatAsState(
            targetValue = if (reveal) 1f else 0.92f,
            animationSpec = tween(durationMillis = 480, easing = FastOutSlowInEasing),
            label = "splashScale"
        )
        val contentAlpha by animateFloatAsState(
            targetValue = if (reveal) 1f else 0f,
            animationSpec = tween(durationMillis = 420),
            label = "splashAlpha"
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF102A1E),
                            VumiqSplashObsidian
                        ),
                        center = Offset(540f, 800f),
                        radius = 1350f
                    )
                )
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 32.dp, vertical = 28.dp)
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .scale(logoScale)
                    .alpha(contentAlpha),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                VumiqBrandEmblem(size = 84.dp)
                Spacer(modifier = Modifier.height(22.dp))
                Text(
                    text = stringResource(R.string.brand_wordmark),
                    color = Color(0xFFFAF8F4),
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 3.5.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.brand_subtitle),
                    color = VumiqMint,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.8.sp
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .alpha(contentAlpha)
                    .padding(bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                HorizontalDivider(
                    modifier = Modifier.width(56.dp),
                    thickness = 1.dp,
                    color = Color(0xFF234737)
                )
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = stringResource(R.string.brand_ownership),
                    color = Color(0xFFD8E4DF),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    letterSpacing = 0.3.sp
                )
            }
        }
    }
}

@Composable
fun VumiqSurfaceCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = LocalVumiqColors.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = colors.cardSurface,
        border = BorderStroke(1.dp, colors.hairlineBorder),
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            content = content
        )
    }
}

@Composable
fun OwnershipFooterBlock(modifier: Modifier = Modifier) {
    val colors = LocalVumiqColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            VumiqBrandEmblem(size = 20.dp)
            Text(
                text = "VUMIQ Cashbook",
                style = MaterialTheme.typography.labelLarge,
                color = colors.textPrimary
            )
        }
        Text(
            text = stringResource(R.string.brand_owned_label),
            style = MaterialTheme.typography.labelSmall,
            color = colors.textSecondary,
            letterSpacing = 0.6.sp
        )
        Text(
            text = stringResource(R.string.brand_owner_name),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary
        )
    }
}
