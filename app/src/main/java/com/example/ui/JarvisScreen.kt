package com.example.ui

import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.StopScreenShare
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ScreenShare
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import com.example.ai.JarvisConnectionState
import com.example.ui.components.VoiceCoreOrb
import com.example.ui.theme.BorderGlass
import com.example.ui.theme.BorderRose
import com.example.ui.theme.CrimsonPulse
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.EmeraldLive
import com.example.ui.theme.HudMuted
import com.example.ui.theme.HudWhite
import com.example.ui.theme.MidnightAbyss
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.RoseMagenta
import com.example.ui.theme.SurfaceElevated
import com.example.ui.theme.SurfaceGlass
import com.example.vision.CameraLens
import com.example.vision.CameraVisionManager

@Composable
fun JarvisScreen(
    uiState: JarvisUiState,
    onPowerMicClick: () -> Unit,
    onToggleCameraVision: () -> Unit,
    onFlipCamera: () -> Unit,
    onToggleScreenVision: () -> Unit,
    onCameraFrameCaptured: (ByteArray) -> Unit,
    onCameraError: (String) -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(ObsidianVoid, MidnightAbyss, ObsidianVoid)
                )
            )
    ) {
        // Futuristic subtle grid & ambient atmosphere backdrop
        FuturisticBackdropCanvas(
            connectionState = uiState.connectionState,
            modifier = Modifier.fillMaxSize()
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top HUD Status Strip
            TopHudBar(
                connectionState = uiState.connectionState,
                isScreenVisionActive = uiState.isScreenVisionActive,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 600.dp)
            )

            // Middle Interactive Stage (Orb + Optional Floating Camera Eyes PiP)
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .widthIn(max = 600.dp),
                contentAlignment = Alignment.Center
            ) {
                val orbDimension = (minOf(maxWidth, maxHeight) * 0.72f).coerceIn(220.dp, 340.dp)

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    VoiceCoreOrb(
                        connectionState = uiState.connectionState,
                        inputAmplitude = uiState.inputAmplitude,
                        outputAmplitude = uiState.outputAmplitude,
                        onPowerMicClick = onPowerMicClick,
                        orbSize = orbDimension
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    val stateCue = when (uiState.connectionState) {
                        JarvisConnectionState.DISCONNECTED -> stringResource(R.string.hint_tap_to_talk)
                        JarvisConnectionState.CONNECTING -> stringResource(R.string.hint_connecting)
                        JarvisConnectionState.LISTENING -> stringResource(R.string.hint_listening)
                        JarvisConnectionState.SPEAKING -> stringResource(R.string.hint_speaking)
                    }

                    Text(
                        text = stateCue,
                        style = MaterialTheme.typography.labelLarge,
                        color = when (uiState.connectionState) {
                            JarvisConnectionState.LISTENING -> ElectricCyan
                            JarvisConnectionState.SPEAKING -> RoseMagenta
                            JarvisConnectionState.CONNECTING -> ElectricViolet
                            JarvisConnectionState.DISCONNECTED -> HudMuted
                        }
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    AnimatedVisibility(
                        visible = !uiState.activeToolTelemetry.isNullOrBlank(),
                        enter = fadeIn() + scaleIn(initialScale = 0.92f),
                        exit = fadeOut() + scaleOut(targetScale = 0.92f)
                    ) {
                        TelemetryChip(
                            label = uiState.activeToolTelemetry.orEmpty(),
                            accentColor = ElectricCyan
                        )
                    }
                }

                // Floating Camera Eyes Live Preview Card (top-right inside stage)
                androidx.compose.animation.AnimatedVisibility(
                    visible = uiState.isCameraVisionActive,
                    enter = fadeIn() + scaleIn(initialScale = 0.85f),
                    exit = fadeOut() + scaleOut(targetScale = 0.85f),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 8.dp)
                ) {
                    CameraEyesPreviewCard(
                        lens = uiState.cameraLens,
                        onFlipCamera = onFlipCamera,
                        onFrameCaptured = onCameraFrameCaptured,
                        onError = onCameraError
                    )
                }
            }

            // Transparent Error Banner (only shown if a real runtime error occurs)
            AnimatedVisibility(visible = !uiState.errorMessage.isNullOrBlank()) {
                ErrorTelemetryBanner(
                    message = uiState.errorMessage.orEmpty(),
                    onDismiss = onDismissError,
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 560.dp)
                        .padding(bottom = 12.dp)
                )
            }

            // Bottom Vision Control Dock
            VisionControlDock(
                isCameraActive = uiState.isCameraVisionActive,
                isScreenActive = uiState.isScreenVisionActive,
                onToggleCamera = onToggleCameraVision,
                onToggleScreen = onToggleScreenVision,
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .padding(bottom = 8.dp)
            )
        }
    }
}

@Composable
private fun FuturisticBackdropCanvas(
    connectionState: JarvisConnectionState,
    modifier: Modifier = Modifier
) {
    val auraColor = when (connectionState) {
        JarvisConnectionState.DISCONNECTED -> ElectricViolet.copy(alpha = 0.06f)
        JarvisConnectionState.CONNECTING -> ElectricViolet.copy(alpha = 0.14f)
        JarvisConnectionState.LISTENING -> ElectricCyan.copy(alpha = 0.13f)
        JarvisConnectionState.SPEAKING -> RoseMagenta.copy(alpha = 0.16f)
    }

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height * 0.46f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(auraColor, Color.Transparent),
                center = center,
                radius = size.maxDimension * 0.55f
            ),
            radius = size.maxDimension * 0.55f,
            center = center
        )

        // Subtle futuristic coordinate crosshair lines
        val gridColor = Color(0x0D94A3B8)
        val step = 48.dp.toPx()
        var x = step
        while (x < size.width) {
            drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            x += step
        }
        var y = step
        while (y < size.height) {
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            y += step
        }
    }
}

@Composable
private fun TopHudBar(
    connectionState: JarvisConnectionState,
    isScreenVisionActive: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(SurfaceGlass.copy(alpha = 0.78f))
                .border(1.dp, BorderGlass, RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val dotColor = when (connectionState) {
                JarvisConnectionState.DISCONNECTED -> HudMuted
                JarvisConnectionState.CONNECTING -> ElectricViolet
                JarvisConnectionState.LISTENING -> ElectricCyan
                JarvisConnectionState.SPEAKING -> RoseMagenta
            }
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.hud_brand_label),
                style = MaterialTheme.typography.labelMedium,
                color = HudWhite
            )
        }

        val stateLabel = when (connectionState) {
            JarvisConnectionState.DISCONNECTED -> stringResource(R.string.state_disconnected)
            JarvisConnectionState.CONNECTING -> stringResource(R.string.state_connecting)
            JarvisConnectionState.LISTENING -> stringResource(R.string.state_listening)
            JarvisConnectionState.SPEAKING -> stringResource(R.string.state_speaking)
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isScreenVisionActive) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(EmeraldLive.copy(alpha = 0.16f))
                        .border(1.dp, EmeraldLive.copy(alpha = 0.6f), RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "SCR",
                        style = MaterialTheme.typography.labelSmall,
                        color = EmeraldLive
                    )
                }
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(SurfaceGlass.copy(alpha = 0.78f))
                    .border(
                        width = 1.dp,
                        color = if (connectionState == JarvisConnectionState.SPEAKING) BorderRose else BorderGlass,
                        shape = RoundedCornerShape(50)
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = stateLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = when (connectionState) {
                        JarvisConnectionState.DISCONNECTED -> HudMuted
                        JarvisConnectionState.CONNECTING -> ElectricViolet
                        JarvisConnectionState.LISTENING -> ElectricCyan
                        JarvisConnectionState.SPEAKING -> RoseMagenta
                    }
                )
            }
        }
    }
}

@Composable
private fun CameraEyesPreviewCard(
    lens: CameraLens,
    onFlipCamera: () -> Unit,
    onFrameCaptured: (ByteArray) -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val cameraManager = remember {
        CameraVisionManager(
            context = context,
            onFrameCaptured = onFrameCaptured,
            onError = onError
        )
    }

    LaunchedEffect(lens, lifecycleOwner) {
        cameraManager.bindCamera(
            lifecycleOwner = lifecycleOwner,
            previewView = previewView,
            lens = lens
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraManager.release()
        }
    }

    Box(
        modifier = Modifier
            .size(width = 112.dp, height = 152.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(SurfaceElevated)
            .border(1.5.dp, ElectricCyan.copy(alpha = 0.75f), RoundedCornerShape(20.dp))
    ) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .clip(RoundedCornerShape(50))
                .background(ObsidianVoid.copy(alpha = 0.72f))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Text(
                text = "CAM",
                style = MaterialTheme.typography.labelSmall,
                color = ElectricCyan
            )
        }

        IconButton(
            onClick = onFlipCamera,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .minimumInteractiveComponentSize()
                .testTag("flip_camera_button")
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(ObsidianVoid.copy(alpha = 0.75f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Cameraswitch,
                    contentDescription = stringResource(R.string.camera_flip_cd),
                    tint = HudWhite,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun TelemetryChip(
    label: String,
    accentColor: Color
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(accentColor.copy(alpha = 0.14f))
            .border(1.dp, accentColor.copy(alpha = 0.45f), RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = accentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ErrorTelemetryBanner(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(CrimsonPulse.copy(alpha = 0.16f))
            .border(1.dp, CrimsonPulse.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
            .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = HudWhite,
            modifier = Modifier.weight(1f)
        )
        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("dismiss_error_button")
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Dismiss error",
                tint = HudWhite
            )
        }
    }
}

@Composable
private fun VisionControlDock(
    isCameraActive: Boolean,
    isScreenActive: Boolean,
    onToggleCamera: () -> Unit,
    onToggleScreen: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(SurfaceGlass.copy(alpha = 0.85f))
            .border(1.dp, BorderGlass, RoundedCornerShape(28.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        VisionActionPill(
            label = stringResource(R.string.camera_eyes_label),
            icon = if (isCameraActive) Icons.Rounded.Videocam else Icons.Outlined.VideocamOff,
            contentDescription = if (isCameraActive) {
                stringResource(R.string.camera_vision_disable_cd)
            } else {
                stringResource(R.string.camera_vision_enable_cd)
            },
            isActive = isCameraActive,
            activeColor = ElectricCyan,
            onClick = onToggleCamera,
            modifier = Modifier
                .weight(1f)
                .testTag("camera_vision_button")
        )

        VisionActionPill(
            label = stringResource(R.string.screen_eyes_label),
            icon = if (isScreenActive) Icons.Rounded.ScreenShare else Icons.Outlined.StopScreenShare,
            contentDescription = if (isScreenActive) {
                stringResource(R.string.screen_vision_disable_cd)
            } else {
                stringResource(R.string.screen_vision_enable_cd)
            },
            isActive = isScreenActive,
            activeColor = EmeraldLive,
            onClick = onToggleScreen,
            modifier = Modifier
                .weight(1f)
                .testTag("screen_vision_button")
        )
    }
}

@Composable
private fun VisionActionPill(
    label: String,
    icon: ImageVector,
    contentDescription: String,
    isActive: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor = if (isActive) activeColor.copy(alpha = 0.18f) else SurfaceElevated.copy(alpha = 0.65f)
    val borderColor = if (isActive) activeColor.copy(alpha = 0.7f) else Color(0x2694A3B8)
    val contentTint = if (isActive) activeColor else HudMuted

    Row(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .height(50.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(20.dp))
            .clickable(
                onClickLabel = contentDescription,
                onClick = onClick
            )
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = contentTint,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (isActive) HudWhite else HudMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
