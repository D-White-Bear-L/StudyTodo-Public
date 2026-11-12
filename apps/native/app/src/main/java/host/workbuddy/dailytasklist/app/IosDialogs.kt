package host.workbuddy.dailytasklist.app

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.styropyr0.prismal.PrismalBackdrop
import com.styropyr0.prismal.drawPrismalGlass
import com.styropyr0.prismal.effects.applyPrismalGlassEffects
import com.styropyr0.prismal.shapes.PrismalRoundedRectangle
import kotlinx.coroutines.delay

/* ===========================================================================
   仿 iOS 弹窗（移植自 PrismalAGSL 组件库的 PlaygroundDialogs）
   ---------------------------------------------------------------------------
   两种形态：
     IosAlertDialog   —— 屏幕正中、270dp 宽的警告框，分隔线 + 蓝色/红色按钮，
                         入场是「先压扁再弹开」的 squish 弹性动效（iOS 原生手感）。
     IosSheetDialog   —— 居中的玻璃信息面板，同样是 squish 入场。

   玻璃表面：Dialog 虽然是独立窗口，但在 Compose 里仍然继承 CompositionLocal，
   所以能拿到 LocalPrismalBackdrop 做真折射；拿不到时退回半透明渐变 + 描边，
   保证任何环境下都不会变成一块不透明的塑料板。
   =========================================================================== */

private const val ModalScrimDim = 0.38f
private val ModalSquishEnter = spring<Float>(dampingRatio = 0.54f, stiffness = 420f)

/** iOS 警告框的圆角（比普通卡片大很多，这是它的标志性造型） */
private val AlertCornerRadius = 30.dp

/** 弹窗表面的实底：iOS 的模态面本身就是「强模糊 + 半透明材质」，不会全透 */
@Composable
internal fun modalMaterial(): Color =
    if (darkText()) Color(0xFF1C1C1E).copy(alpha = 0.88f)
    else Color.White.copy(alpha = 0.82f)

/* ---------------- 遮罩 ---------------- */

@Composable
private fun ModalScrim(alpha: Float, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = alpha))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            )
    )
}

/* ---------------- 玻璃表面 ---------------- */

/**
 * 弹窗用的玻璃表面。有背景采样层就做真折射 + 模糊；没有就退化为渐变玻璃。
 *
 * @param cornerRadius 圆角
 * @param solid 是否叠加模态实底（警告框要，纯信息浮层可以更透）
 */
@Composable
internal fun ModalGlassSurface(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = AlertCornerRadius,
    solid: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    val backdrop: PrismalBackdrop? = LocalPrismalBackdrop.current
    val density = LocalDensity.current
    val shape: Shape = RoundedCornerShape(cornerRadius)
    val night = darkText()
    /* modalMaterial() 是 @Composable，必须在组合期算好，不能带进 DrawScope 里调用 */
    val material = modalMaterial()

    if (backdrop != null) {
        Box(
            modifier = modifier.drawPrismalGlass(
                backdrop = backdrop,
                shape = { PrismalRoundedRectangle(cornerRadius) },
                effects = {
                    applyPrismalGlassEffects(
                        density = density,
                        adaptiveLuminance = false,
                        luminance = 0.3f,
                        /* 弹窗要「厚」一点：模糊比页面上的玻璃更大，背景越糊越像 iOS */
                        blurRadiusPx = with(density) { 18.dp.toPx() },
                        refractionHeightPx = with(density) { 18.dp.toPx() },
                        refractionAmountPx = with(density) { 36.dp.toPx() },
                        useVibrancy = true
                    )
                },
                specular = null,
                depthShadow = null,
                onDrawSurface = {
                    if (solid) drawRect(material)
                }
            )
        ) {
            content()
        }
    } else {
        /* 拿不到背景层：用上下渐变 + 描边模拟毛玻璃，而不是一整块死白 */
        Box(
            modifier = modifier
                .clip(shape)
                .background(
                    if (night) {
                        Brush.verticalGradient(
                            listOf(
                                Color(0xFF2A3140).copy(alpha = 0.97f),
                                Color(0xFF141B27).copy(alpha = 0.98f),
                                Color(0xFF0D1420).copy(alpha = 0.99f)
                            )
                        )
                    } else {
                        Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = 0.97f),
                                Color(0xFFF3F7FD).copy(alpha = 0.98f),
                                Color(0xFFEAF1FB).copy(alpha = 0.99f)
                            )
                        )
                    }
                )
                .border(
                    0.8.dp,
                    if (night) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.95f),
                    shape
                ),
            content = content
        )
    }
}

/* ---------------- squish 入场容器 ---------------- */

/**
 * 「先压扁再弹开」的入场动效（iOS 模态面板的标志性手感）。
 * 退出时直接淡出缩放，不拖泥带水。
 */
@Composable
private fun SquishOverlay(
    visible: Boolean,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    val transition = updateTransition(visible, label = "iosModal")

    val scrimAlpha by transition.animateFloat(
        transitionSpec = { if (false isTransitioningTo true) tween(220) else tween(180) },
        label = "scrim"
    ) { shown -> if (shown) ModalScrimDim else 0f }

    val scaleX by transition.animateFloat(
        transitionSpec = {
            when {
                false isTransitioningTo true -> ModalSquishEnter
                true isTransitioningTo false -> tween(140)
                else -> snap()
            }
        },
        label = "sx"
    ) { shown -> if (shown) 1f else 1.06f }

    val scaleY by transition.animateFloat(
        transitionSpec = {
            when {
                false isTransitioningTo true -> ModalSquishEnter
                true isTransitioningTo false -> tween(140)
                else -> snap()
            }
        },
        label = "sy"
    ) { shown -> if (shown) 1f else 0.74f }

    val contentAlpha by transition.animateFloat(
        transitionSpec = { tween(180) },
        label = "alpha"
    ) { shown -> if (shown) 1f else 0f }

    Box(Modifier.fillMaxSize()) {
        ModalScrim(alpha = scrimAlpha, onDismiss = onDismiss)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(
                Modifier.graphicsLayer {
                    this.scaleX = scaleX
                    this.scaleY = scaleY
                    alpha = contentAlpha
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                }
            ) {
                content()
            }
        }
    }
}

/** 弹窗自管一个 visible：先播入场动效，关闭时先播退出再真正 dismiss */
@Composable
private fun rememberModalVisible(onClose: () -> Unit): Pair<Boolean, () -> Unit> {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val close: () -> Unit = {
        visible = false
    }
    LaunchedEffect(visible) {
        if (!visible) {
            delay(170)
            onClose()
        }
    }
    return visible to close
}

/* ---------------- iOS 警告框 ---------------- */

/**
 * 仿 iOS 的原生警告框。破坏性操作用红色加粗按钮，普通动作用蓝色。
 *
 * 沿用老的签名（title / message / confirmText / onDismiss / onConfirm），
 * 所以所有「删除任务 / 删除标签」的调用点都不用改。
 */
@Composable
fun IosAlertDialog(
    title: String,
    message: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    cancelText: String = "取消",
    destructive: Boolean = true
) {
    val night = darkText()
    val accent = if (destructive) {
        if (night) Color(0xFFFF453A) else Color(0xFFFF3B30)
    } else {
        if (night) Color(0xFF0A84FF) else Color(0xFF007AFF)
    }
    val separator = if (night) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.10f)

    val (visible, close) = rememberModalVisible(onDismiss)

    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        SquishOverlay(visible = visible, onDismiss = close) {
            ModalGlassSurface(
                modifier = Modifier
                    .width(272.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    ),
                cornerRadius = AlertCornerRadius
            ) {
                Column(Modifier.fillMaxWidth()) {
                    /* 标题 + 说明 */
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 19.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(
                            title,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            message,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = TextSub,
                            textAlign = TextAlign.Center
                        )
                    }

                    HorizontalDivider(thickness = 0.5.dp, color = separator)

                    /* iOS 是「上下两行按钮」，不是左右并排 */
                    AlertRow(if (night) Color(0xFF0A84FF) else Color(0xFF007AFF), "取消") {
                        close()
                    }
                    HorizontalDivider(thickness = 0.5.dp, color = separator)
                    AlertRow(accent, confirmText, bold = true) {
                        onConfirm()
                        close()
                    }
                }
            }
        }
    }
}

@Composable
private fun AlertRow(
    color: Color,
    text: String,
    bold: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(45.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontSize = 17.sp,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            color = color
        )
    }
}

/* ---------------- iOS 信息面板（居中 sheet） ---------------- */

/**
 * 居中的玻璃信息面板，替代原先手写的实心渐变卡片。
 *
 * 和警告框共用 squish 入场，但圆角稍小、宽度可配，用来承载表单、列表、导出预览
 * 这类有内容的弹窗。内容仍由调用方通过 [content] 提供，所以老的调用点全部兼容。
 */
@Composable
fun IosSheetDialog(
    onDismiss: () -> Unit,
    widthFraction: Float = 0.92f,
    fillHeight: Float? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val (visible, close) = rememberModalVisible(onDismiss)

    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        SquishOverlay(visible = visible, onDismiss = close) {
            ModalGlassSurface(
                modifier = Modifier
                    .fillMaxWidth(widthFraction)
                    .then(if (fillHeight != null) Modifier.fillMaxHeight(fillHeight) else Modifier)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    ),
                cornerRadius = 30.dp
            ) {
                /* 带 fillHeight 时（例如导出预览那种占 84% 屏高的面板），
                   内层 Column 必须一起撑满，否则里面的 weight(1f) 拿不到剩余空间、
                   内容区会塌成 0 高度。 */
                Column(
                    Modifier
                        .then(if (fillHeight != null) Modifier.fillMaxHeight() else Modifier)
                        .padding(20.dp),
                    content = content
                )
            }
        }
    }
}

/* ---------------- iOS 底部动作面板 ---------------- */

/**
 * 仿 iOS 的底部 sheet：从屏幕底部滑上来，顶部一条拖拽指示条。
 *
 * 用于「导出」「迁移」这类从底部弹出的操作面板。整块面板是玻璃，
 * 外面有一层可点击关闭的暗色遮罩。
 */
@Composable
fun IosBottomSheet(
    onDismiss: () -> Unit,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val (visible, close) = rememberModalVisible(onDismiss)
    val transition = updateTransition(visible, label = "iosSheet")

    val scrimAlpha by transition.animateFloat(
        transitionSpec = { if (false isTransitioningTo true) tween(220) else tween(180) },
        label = "scrim"
    ) { shown -> if (shown) ModalScrimDim else 0f }

    val slide by transition.animateFloat(
        transitionSpec = {
            when {
                false isTransitioningTo true -> spring(
                    dampingRatio = 0.82f,
                    stiffness = Spring.StiffnessMediumLow
                )
                true isTransitioningTo false -> spring(
                    dampingRatio = 0.9f,
                    stiffness = Spring.StiffnessMedium
                )
                else -> snap()
            }
        },
        label = "slide"
    ) { shown -> if (shown) 0f else 1f }

    val scaleY by transition.animateFloat(
        transitionSpec = {
            when {
                false isTransitioningTo true -> ModalSquishEnter
                true isTransitioningTo false -> tween(160)
                else -> snap()
            }
        },
        label = "sy"
    ) { shown -> if (shown) 1f else 0.9f }

    val contentAlpha by transition.animateFloat(
        transitionSpec = { tween(180) },
        label = "alpha"
    ) { shown -> if (shown) 1f else 0f }

    val night = darkText()

    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            ModalScrim(alpha = scrimAlpha, onDismiss = close)

            Box(
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        translationY = slide * size.height
                        this.scaleY = scaleY
                        alpha = contentAlpha
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
            ) {
                ModalGlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 0.dp,
                    solid = true
                ) {
                    /* 底部 sheet 只要上面两个圆角，用外层 clip 补形 */
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(
                                RoundedCornerShape(
                                    topStart = 30.dp,
                                    topEnd = 30.dp,
                                    bottomStart = 0.dp,
                                    bottomEnd = 0.dp
                                )
                            )
                            .padding(bottom = 26.dp, top = 8.dp)
                    ) {
                        /* 拖拽指示条 */
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Box(
                                Modifier
                                    .width(38.dp)
                                    .height(5.dp)
                                    .clip(RoundedCornerShape(100.dp))
                                    .background(
                                        if (night) Color.White.copy(alpha = 0.30f)
                                        else Color.Black.copy(alpha = 0.18f)
                                    )
                            )
                        }
                        if (title != null) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                title,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Column(Modifier.padding(horizontal = 20.dp), content = content)
                    }
                }
            }
        }
    }
}
