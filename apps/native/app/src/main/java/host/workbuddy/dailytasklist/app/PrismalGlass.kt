package host.workbuddy.dailytasklist.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.styropyr0.prismal.PrismalBackdrop
import com.styropyr0.prismal.PrismalGlass
import com.styropyr0.prismal.PrismalGlassSurface
import com.styropyr0.prismal.PrismalGlassEffectProvider
import com.styropyr0.prismal.drawPrismalGlass
import com.styropyr0.prismal.drawPrismalGlassTint
import com.styropyr0.prismal.effects.applyPrismalGlassEffects
import com.styropyr0.prismal.effects.prismalLens
import com.styropyr0.prismal.components.LocalPrismalParentGlassLayer
import com.styropyr0.prismal.shapes.PrismalRoundedRectangle
import com.styropyr0.prismal.sources.prismalGlassLayer
import com.styropyr0.prismal.sources.rememberPrismalGlassLayer
import com.styropyr0.prismal.specular.PrismalSpecular
/* ===========================================================================
   PrismalAGSL 液态玻璃封装
   ---------------------------------------------------------------------------
   底层全部交给第三方库 com.github.styropyr0:PrismalAGSL（AGSL 折射 / 色散 /
   边缘高光 / 背景模糊）。本文件只做两件事：

     1. 用一个 CompositionLocal 把「背景采样层」透传到所有玻璃组件，
        业务代码不用逐个手动传 backdrop。
     2. 保留旧的 LiquidSurface / LiquidGlass 等组件签名，内部转发给
        PrismalGlassSurface / drawPrismalGlass，调用方几乎零改动。

   设备能力由库内部按 API 自动选管线，无需分支：
     API 25-30  Legacy   —— 背景取样 + 磨砂（无折射）
     API 31-32  Standard —— RenderEffect 背景模糊（无折射/色散）
     API 33+    Liquid   —— AGSL 真折射 + 色散 + 边缘高光
   =========================================================================== */

/** 全局背景采样层。由 [GlassBackground] 提供，玻璃组件从这里拿 backdrop。 */
internal val LocalPrismalBackdrop =
    compositionLocalOf<PrismalBackdrop?> { null }

/**
 * 当前设备是否跑在「真液态玻璃」档位（API 33+）。
 * 封装内部用来自动调整折射/高光强度，业务层无需关心。
 */
@Composable
internal fun liquidPipelineActive(): Boolean = PrismalGlass.supportsRefraction

/**
 * 运行时能力探测：API 33+ 之外，还要确认 RuntimeShader 真的能编译 AGSL。
 *
 * Robolectric 的 layoutlib 里 `Build.VERSION.SDK_INT` 虽然能到 33，但它的
 * RuntimeShader 模拟实现不支持 shader 类型的 swizzle，编译 Prismal 的标准
 * 折射着色器会抛 "cannot swizzle value of type 'shader'"，导致截图测试崩。
 * 真机的系统 RuntimeShader 则完全正常。
 *
 * 用 [forceDisableAgsl] 可以在测试里强制关闭（Robolectric 无法渲染 AGSL），
 * 让封装走 Legacy 雾面，验证排版不崩；真机永远走 AGSL。
 */
@Volatile
private var forceDisableAgsl = false

internal val agslRuntimeAvailable: Boolean by lazy {
    if (forceDisableAgsl) false
    else if (android.os.Build.VERSION.SDK_INT < 33) false
    else {
        try {
            /* 直接编译 Prismal 的色散折射着色器：这是 Robolectric 会失败、真机会成功的写法 */
            android.graphics.RuntimeShader(
                """
                uniform shader content;
                half4 main(float2 coord) {
                    half4 c = content.eval(coord);
                    half4 r = content.eval(coord + float2(1.0, 0.0));
                    half4 color = half4(0.0);
                    color.r += r.r / 3.5;
                    color.g += c.g / 3.5;
                    color.b += c.b / 3.0;
                    color.a += c.a / 7.0;
                    return color;
                }
                """.trimIndent()
            )
            true
        } catch (t: Throwable) {
            false
        }
    }
}

/** 仅供 Robolectric 截图测试：强制关闭 AGSL 渲染，走 Legacy 雾面降级。 */
internal fun disableAgslForTesting() {
    forceDisableAgsl = true
}

/* ---------------- 背景（采样源） ---------------- */

/**
 * 页面背景 + 液态玻璃采样层。
 *
 * 背景不是静态渐变，而是复用旧引擎的 [SceneField] 色场（大色块 + 光斑 + 极光带），
 * 这样玻璃边缘的折射/色散才有可被「折弯」的结构，否则压在一片均匀色上根本看不出效果。
 *
 * 它把自己注册成 PrismalGlassLayer（`prismalGlassLayer`），并通过
 * [LocalPrismalBackdrop] 提供给整棵子树，所有玻璃组件都从它采样身后的颜色。
 */
@Composable
fun GlassBackground(
    wallpaperPath: String = "",
    content: @Composable BoxScope.() -> Unit
) {
    val dark = isSystemInDarkTheme()
    val backdrop = rememberPrismalGlassLayer()

    var sizePx by remember { androidx.compose.runtime.mutableStateOf(IntSize.Zero) }
    val bgBitmap: ImageBitmap? = remember(dark, sizePx) {
        if (sizePx.width > 0 && sizePx.height > 0) {
            buildBackdropBitmap(sizePx.width, sizePx.height, dark)
        } else null
    }

    /* 用户自选壁纸：从本地文件路径直接解码（BitmapFactory.decodeFile 最可靠，
       不经过 ContentResolver，避免 file:// / content:// URI 权限和解析问题）。 */
    val wallpaper: ImageBitmap? = remember(wallpaperPath) {
        if (wallpaperPath.isBlank()) {
            null
        } else {
            runCatching {
                val bmp = android.graphics.BitmapFactory.decodeFile(wallpaperPath)
                bmp?.asImageBitmap()
            }.getOrNull()
        }
    }

    Box(Modifier.fillMaxSize()) {
        /* 采样源：真正的整屏背景画面（用户壁纸优先，否则内置色场） */
        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { sizePx = it }
                .prismalGlassLayer(backdrop)
                .background(if (dark) Color(0xFF0A1018) else Color(0xFFF4F7FD))
        ) {
            if (wallpaper != null) {
                /* 壁纸按 Crop 裁满整屏，玻璃采到的就是这张真实图片 */
                val src = wallpaper
                Canvas(Modifier.fillMaxSize()) {
                    drawImageCrop(src, size.width, size.height)
                }
            } else if (bgBitmap != null) {
                Canvas(Modifier.fillMaxSize()) {
                    drawImageScaled(bgBitmap, size.width, size.height)
                }
            }
        }

        /* 玻璃组件都画在这层之上（同级、覆盖在上），才能采到身后的背景 */
        CompositionLocalProvider(LocalPrismalBackdrop provides backdrop) {
            content()
        }
    }
}

/** 按 Crop（覆盖裁切）方式把位图铺满目标尺寸，保持比例不变形 */
private fun DrawScope.drawImageCrop(bitmap: ImageBitmap, w: Float, h: Float) {
    val bw = bitmap.width.toFloat()
    val bh = bitmap.height.toFloat()
    if (bw <= 0f || bh <= 0f) return
    val scale = maxOf(w / bw, h / bh)
    val cw = w / scale
    val ch = h / scale
    val sx = (bw - cw) / 2f
    val sy = (bh - ch) / 2f
    drawImage(
        image = bitmap,
        srcOffset = androidx.compose.ui.unit.IntOffset(sx.toInt(), sy.toInt()),
        srcSize = androidx.compose.ui.unit.IntSize(cw.toInt(), ch.toInt()),
        dstOffset = androidx.compose.ui.unit.IntOffset.Zero,
        dstSize = androidx.compose.ui.unit.IntSize(w.toInt(), h.toInt()),
        filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium
    )
}

/** 把背景小图按整屏尺寸双线性放大绘制（背景本身就是柔和色场，放大后更平滑） */
private fun DrawScope.drawImageScaled(bitmap: ImageBitmap, w: Float, h: Float) {
    drawImage(
        image = bitmap,
        srcOffset = androidx.compose.ui.unit.IntOffset.Zero,
        srcSize = androidx.compose.ui.unit.IntSize(bitmap.width, bitmap.height),
        dstOffset = androidx.compose.ui.unit.IntOffset.Zero,
        dstSize = androidx.compose.ui.unit.IntSize(w.toInt(), h.toInt()),
        filterQuality = androidx.compose.ui.graphics.FilterQuality.Low
    )
}

/* ---------------- 形状换算 ---------------- */

/** 把 Compose 圆角 Shape 映射成 Prismal 圆角矩形（折射需要形状暴露圆角半径给着色器） */
private fun prismalShape(radius: Dp): Shape = PrismalRoundedRectangle(radius)

/* ===========================================================================
   统一的玻璃材质（单一真相源）
   ---------------------------------------------------------------------------
   问题：自绘的 LiquidSurface 读 GlassTuning，而库里的分段控件 / 刻度尺 / 按钮 /
   滑块 / 开关各自带一套默认参数（固定模糊 8dp、固定白底、固定色散、固定饱和）。
   于是同一页会出现「三种通透度」：卡片一档、库组件一档、按钮又一档；
   用户调「模糊 / 明度 / 饱和度」时，只有其中一部分跟着变。

   这里把「一块玻璃该长什么样」收成一个值对象：模糊、折射、色散、明度、饱和度、
   底部薄底（scrim）、着色，全部从 GlassTuning 算一次。自绘玻璃直接用；
   库组件通过 `effects` / `dropletEffects` / `onDrawSurface` 参数把这套值灌进去。
   不管组件是谁写的，看到的是同一块玻璃。
   =========================================================================== */

/** 所有玻璃共用的「可读性薄底」基准。乘以设置页的「可读性」倍率。 */
private const val ScrimBase = 0.16f

/** 按压时的整体放大幅度。3.5% 是小控件上的「有弹性」而不是「弹出来」。 */
private const val PressScale = 0.035f

/*
 * 折射带高度上限：短边的 42%。
 *
 * 库的折射是「从形状边缘往里推」的一圈带子，高度就是 refractionHeight。
 * 当它超过短边的一半时，上下（左右）两条带子会在正中间相遇——相遇处的
 * SDF 梯度是 normalize(vec2(0,0))：
 *
 *     prismalGradSdRoundRect 在 cornerCoord = (0,0) 时返回 sign(0) * normalize(0,0)
 *
 * 这个 0/0 是 NaN，于是正中那一行像素的采样坐标变成 NaN，整行画不出来，
 * 看起来就是「按钮中间多了一条线」，胶囊/椭圆按钮最明显（短边正好是它的高）。
 *
 * 压到 42% 之后中线永远落在「无折射区」（那段代码直接 return 原图），
 * 接缝在数学上不可能出现。大卡片短边几百 dp，用户把滑块拉满也照样完整生效，
 * 只有小按钮会被悄悄封顶——这也是上限该有的行为：它是几何约束，不是审美偏好。
 */
private const val MaxRefractRatio = 0.42f

/** 按当前表面尺寸把折射带高度封顶，见 [MaxRefractRatio]。 */
private fun PrismalGlassEffectProvider.safeRefractHeight(wantedPx: Float): Float {
    if (wantedPx <= 0f) return 0f
    val s = size
    if (!s.isSpecified) return wantedPx
    val cap = s.minDimension * MaxRefractRatio
    if (cap <= 0f) return wantedPx
    return minOf(wantedPx, cap)
}

@Stable
class GlassMaterial internal constructor(
    val blurDp: Dp,
    val refractHeightDp: Dp,
    val refractAmountDp: Dp,
    val chroma: Float,
    val brightness: Float,
    val saturation: Float,
    val useVibrancy: Boolean,
    val scrim: Float,
    val surfaceTint: Float,
    val tintAlpha: Float,
    val specularEnabled: Boolean,
    val depthShadowEnabled: Boolean
) {
    /** 灌给库组件的 effects / dropletEffects 参数。 */
    fun applyEffects(
        provider: PrismalGlassEffectProvider,
        density: Density,
        luminance: Float = 0.5f,
        blurScale: Float = 1f,
        /* 按压时把折射推强一点：玻璃被手指压住，边缘的体积感会变重。
           这是「挤压」手感的一半，另一半是外层的轻微放大。 */
        refractBoost: Float = 1f
    ) {
        with(provider) {
            applyPrismalGlassEffects(
                density = density,
                adaptiveLuminance = false,
                luminance = luminance,
                blurRadiusPx = with(density) { (blurDp * blurScale).toPx() },
                refractionHeightPx = safeRefractHeight(with(density) { refractHeightDp.toPx() }),
                refractionAmountPx = with(density) { refractAmountDp.toPx() } * refractBoost,
                brightness = brightness,
                saturation = saturation,
                depthEffect = false,
                chromaticAberration = chroma,
                useVibrancy = useVibrancy
            )
        }
    }

    /** 玻璃表面那层「可读性薄底」+ 可选着色 + 用户手动叠加的白色 surface tint。 */
    fun drawSurface(
        scope: DrawScope,
        dark: Boolean,
        tint: Color? = null,
        tintAlphaOverride: Float = tintAlpha
    ) {
        with(scope) {
            if (scrim > 0f) {
                drawRect(
                    if (dark) Color.Black.copy(alpha = scrim)
                    else Color.White.copy(alpha = scrim)
                )
            }
            if (tint != null && tintAlphaOverride > 0f) {
                drawPrismalGlassTint(tint, tintAlphaOverride.coerceIn(0f, 1f))
            }
            if (surfaceTint > 0f) drawRect(Color.White.copy(alpha = surfaceTint))
        }
    }

    val specularProvider: (() -> PrismalSpecular?)?
        get() = if (specularEnabled) ({ PrismalSpecular.Default }) else null

    /**
     * 给库组件的「液滴」灌效果时**必须**用这个，而不是直接 [applyEffects]。
     *
     * 库的实现是：只要调用方传了 `dropletEffects`，它就把内置那套
     * （模糊 + prismalLens 真折射）整套替换掉。只灌模糊 / 明度 / 饱和度的话，
     * 液滴会退化成一块糊掉的白色胶囊——文字和刻度都不会被折弯，
     * 这正是「看着像玻璃、但没有渲染交互」的原因。这里补回 lens。
     */
    fun applyDropletLens(
        provider: PrismalGlassEffectProvider,
        density: Density,
        luminance: Float,
        lensHeightPx: Float,
        lensAmountPx: Float,
        depthEffect: Boolean = true
    ) {
        with(provider) {
            /* 液滴里**一个像素的模糊都不能有**。
               这里原来是 applyEffects(blurScale = 0.35f) + prismalLens，
               等于「先按设置把文字糊 6~8dp，再折弯」——文字当然看不清。
               库自带的 PrismalSegmentedControl / PrismalRulerSelector
               默认效果里同样没有 blurRadiusPx，只有 prismalLens。
               所以这里只保留「明度 / 饱和度 / 薄底」这些不改锐度的整体调色，
               模糊与全层色散都置零，全部交给 prismalLens 在边缘做。 */
            applyPrismalGlassEffects(
                density = density,
                adaptiveLuminance = false,
                luminance = luminance,
                blurRadiusPx = 0f,
                refractionHeightPx = 0f,
                refractionAmountPx = 0f,
                brightness = brightness,
                saturation = saturation,
                depthEffect = false,
                chromaticAberration = 0f,
                useVibrancy = useVibrancy
            )
            /* 色散只作用在液滴边缘的折射带上，而且压到 35%：
               色散会把一个字形拆成红/蓝两瓣，强度一高字就「重影」。
               设置页的色散倍率仍然生效，只是这条通道有可读性上限。 */
            prismalLens(
                refractionHeight = safeRefractHeight(lensHeightPx),
                refractionAmount = lensAmountPx,
                depthEffect = depthEffect,
                chromaticAberration = (chroma * 0.35f).coerceIn(0f, 0.3f)
            )
        }
    }
}

/**
 * 按层级取一份玻璃材质。
 *
 * 关键：**模糊半径和薄底不再随层级变化**（原来 Floating 0.07 / Row 0.20，
 * 同一页就能看出三种通透度）。层级只影响「折射幅度」和「色散」这种边缘效果，
 * 那是体积感，不是透明度。用户调一次模糊，全页一起变。
 */
@Composable
internal fun glassMaterial(
    level: GlassLevel = GlassLevel.Control,
    refractK: Float = 1f
): GlassMaterial {
    val tuning = LocalGlassTuning.current
    val liquid = PrismalGlass.supportsRefraction

    val refractScale = when (level) {
        GlassLevel.Floating -> 1.15f
        GlassLevel.Control -> 1.00f
        GlassLevel.Panel -> 0.85f
        GlassLevel.Row -> 0.65f
    }
    val chromaScale = when (level) {
        GlassLevel.Floating -> 1.00f
        GlassLevel.Control -> 1.00f
        GlassLevel.Panel -> 0.90f
        GlassLevel.Row -> 0.80f
    }

    return GlassMaterial(
        blurDp = tuning.blurRadiusDp.dp,
        refractHeightDp = tuning.refractionHeightDp.dp,
        refractAmountDp = tuning.refractionAmountDp.dp * refractScale * refractK.coerceIn(0f, 2f),
        chroma = if (liquid) {
            (tuning.chromaticAberration * chromaScale).coerceIn(0f, 1f)
        } else 0f,
        brightness = tuning.brightness,
        saturation = tuning.saturation,
        useVibrancy = tuning.useVibrancy,
        scrim = ScrimBase * tuning.readability,
        surfaceTint = tuning.surfaceTintAlpha,
        tintAlpha = tuning.tintAlpha.coerceIn(0f, 1f),
        specularEnabled = tuning.specularEnabled,
        depthShadowEnabled = tuning.depthShadowEnabled
    )
}

/* ===========================================================================
   核心表面：LiquidSurface
   ---------------------------------------------------------------------------
   参数尽量对齐旧版 LiquidSurface，便于 MainActivity / Components 无缝迁移。
   =========================================================================== */

/**
 * 液态玻璃表面。底层用 PrismalAGSL 采样 [LocalPrismalBackdrop] 提供的背景层，
 * 做 AGSL 折射 + 色散 + 边缘高光。内容画在最上层。
 *
 * @param level       玻璃层级（决定折射/高光/模糊强度的预设）
 * @param radius      圆角半径
 * @param shape       形状（默认按 radius 生成圆角矩形）
 * @param tint        着色（如 AccentBlue 做出「蓝色玻璃」）
 * @param tintK       着色透明度系数（映射到 Prismal tintAlpha）
 * @param refract     折射强度系数（1.0 = 默认；越大边缘折得越狠）
 * @param veilScale   背景压暗/提亮强度（浅色提高通透、深色压低）
 * @param rimScale    边缘高光强度系数
 * @param interactive 是否响应按压涟漪 + 缩放
 * @param nested      是否把自己这一层「玻璃成品」导出给子控件采样（嵌套玻璃）。
 *                    卡片开启后，卡片里的分段控件 / 滑块 / 开关 / 按钮采样的就不再是
 *                    整屏壁纸，而是这张卡片自己的表面 —— 它们才会看起来「叠在卡片上」，
 *                    而不是在卡片上开了一个通向壁纸的洞。
 */
@Composable
fun LiquidSurface(
    modifier: Modifier = Modifier,
    level: GlassLevel = GlassLevel.Control,
    radius: Dp = Dp.Unspecified,
    shape: Shape? = null,
    refract: Float? = null,
    rimScale: Float = 1f,
    rimTopAlpha: Float? = null,
    rimBottomAlpha: Float? = null,
    showRim: Boolean = true,
    shadowColor: Color? = null,
    grain: Boolean = true,
    tint: Color? = null,
    tintK: Float = 0f,
    veilScale: Float = 1f,
    interactive: Boolean = false,
    interactionSource: MutableInteractionSource? = null,
    /* 按下时的放大幅度。列表行自己已经有「按下去略微缩」的手势，那里传 0f，
       只留折射增强，免得两套缩放互相打架。 */
    pressScaleFactor: Float = PressScale,
    nested: Boolean = false,
    /* 可选的内容变化钩子：传一个读取滚动状态的 lambda，
       玻璃节点会在 observeReads 里读它，内容一变就重新取样。
       采样对象是「页面内容层」而不是壁纸时，这个钩子能给缓存兜底。 */
    contentTick: (() -> Unit)? = null,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit
) {
    val dark = isSystemInDarkTheme()
    /*
     * 采样源：优先「父卡片的玻璃成品」，其次整屏背景。
     *
     * 这是液态玻璃的一个本质特性——它只采样一个 backdrop，不会自动把视觉上盖住的
     * 兄弟节点（比如卡片）也采进去。所以一张浅色卡片里的控件如果仍然采样整屏壁纸，
     * 就会表现为「卡片是浅的、控件却是壁纸的一小块」，看着像卡片被挖了个洞。
     * 父卡片声明 nested 之后会通过 LocalPrismalParentGlassLayer 导出自己的成品，
     * 这里接上，控件就自然变成「叠在卡片上」。
     */
    val backdrop = LocalPrismalParentGlassLayer.current ?: LocalPrismalBackdrop.current
    val tuning = LocalGlassTuning.current

    /* 圆角：radius 显式传了就优先用；否则用全局 cornerRadiusDp（用户可在设置页调） */
    val effectiveRadius = if (radius == Dp.Unspecified) tuning.cornerRadiusDp.dp else radius

    /* 没有背景层，或当前环境跑不了 AGSL（Robolectric/layoutlib），退化为雾面 */
    if (backdrop == null || !agslRuntimeAvailable) {
        LegacyLiquidSurface(
            modifier = modifier,
            level = level,
            radius = effectiveRadius,
            shape = shape ?: RoundedCornerShape(effectiveRadius),
            refract = refract,
            rimScale = rimScale,
            rimTopAlpha = rimTopAlpha,
            rimBottomAlpha = rimBottomAlpha,
            showRim = showRim,
            shadowColor = shadowColor,
            grain = grain,
            tint = tint,
            tintK = tintK,
            veilScale = veilScale,
            interactive = interactive,
            contentAlignment = contentAlignment,
            content = content
        )
        return
    }

    /* 一块玻璃的全部参数都从 glassMaterial() 取：自绘玻璃和库组件共用同一套值，
       这样「模糊 / 明度 / 饱和度 / 可读性」调一次，整页一起变，不会出现三种通透度。 */
    val material = glassMaterial(level, refract ?: 1f)

    /* 按压反馈。
       之前 `interactive` 这个参数在 AGSL 路径里**一次都没被读过**（只传给降级管线），
       所以除了库自带的底部导航栏，页面上任何自绘玻璃按下去都是死的：
       没有放大、没有形变、没有折射变化。这里补上：
         · 外层轻微放大（3.5%）——这就是「按下去会变大」；
         · effects 里折射量同步加强 —— 玻璃被压住时边缘体积感变重。
       按下状态从外部 clickable 的 MutableInteractionSource 读，不自己抢手势，
       所以不会和外面的点击 / 长按 / 滚动打架。 */
    val pressSource = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by if (interactive) {
        pressSource.collectIsPressedAsState()
    } else {
        remember { mutableStateOf(false) }
    }
    val pressProgress by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 900f),
        label = "glassPress"
    )
    val pressLayer = if (interactive && pressScaleFactor > 0f) {
        Modifier.graphicsLayer {
            val s = 1f + pressScaleFactor * pressProgress
            scaleX = s
            scaleY = s
        }
    } else {
        Modifier
    }

    /* tintK：旧版语义是「着色越强越实」，映射到 Prismal 的 tintAlpha。 */
    val tintAlpha = if (tint != null) {
        (material.tintAlpha * (0.55f + 0.45f * tintK.coerceIn(0f, 1f))).coerceIn(0f, 1f)
    } else 0f

    val finalShape: Shape = shape ?: prismalShape(effectiveRadius)

    val theBackdrop = backdrop
    val density = LocalDensity.current

    /* 只有声明了 nested 的卡片才额外申请一层图层（每帧多一次离屏捕获）。
       任务行、按钮、Tab 栏这些都不声明，不付这个成本。 */
    val exportLayer = if (nested) rememberPrismalGlassLayer() else null

    /* 结构说明（v1.23 起）：
       modifier 交给最外层 Box，它由「内容」自己撑高；玻璃是它里面的一层
       matchParentSize 兄弟节点，只负责画面、不参与测量。
       这样既保留了「玻璃高度 = 内容高度」（靠内容撑高的日期条、底部操作坞不会塌成 0），
       又让玻璃可以独立重建而不碰内容（见下面的 key(圆角)）。

       内容层横向 fillMaxWidth（让 Row 里的 weight 生效）、纵向 wrap，
       绝不能用 fillMaxSize：那会被父容器的 maxHeight 撑满
       （底部操作坞会变成一整屏高，把上面的任务列表挤到看不见）。

       用 drawPrismalGlass（而非 drawPlainPrismalGlass）：specular / depthShadow 是否
       叠加由全局 GlassTuning 控制，用户可在设置页开关。 */
    /* 背景/文字的深浅判断要和文字颜色用同一套逻辑（受 textMode 影响），
       否则会出现「文字被强制成浅色、玻璃底色却仍按系统浅色叠白」这种互相打架的情况。 */
    val darkSurface = when (tuning.textMode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }

    /* 可读性薄底、着色、surface tint 全部由 material 统一画，
       和库组件走的是同一份参数（见 GlassMaterial.drawSurface）。 */

    /* 圆角变化时为什么要重建：库的 drawPrismalGlass 用 placeWithLayer 把圆角裁剪
       快照到图层上，圆角变化时只重绘、不重新布局，结果就是「只有白边跟着变、
       玻璃主体还是旧圆角」。必须让这一层重新 place 一次，新裁剪才会生效。

       ⚠️ 关键：重建范围只能套住「纯玻璃这一层」，绝不能套住内容。
       之前的写法是 key(radius) { Box(玻璃+内容) }，后果是拖圆角滑块时每一帧都在
       把整张设置卡片（连同卡片里的滑块自身）销毁重建，滑块的手势节点被反复销毁，
       所以只有圆角滑块拖不动、其他滑块都正常。现在玻璃是独立的 matchParentSize
       兄弟层，重建它不会碰到内容，也不会打断任何手势。 */
    val glassLayer: @Composable BoxScope.() -> Unit = {
        key(effectiveRadius) {
            Box(
                /* clip(finalShape)：关键。
                   库为了做模糊，会把采样图层按 padding（≈模糊半径）放大后绘制，
                   多出来的那一圈会画到玻璃形状之外，而且没有任何裁剪——
                   一行行任务、底部按钮、Tab 栏的这圈「雾边」叠在一起，就是用户看到的
                   「有一个透明的东西没有被截断，把内容盖住了」。裁到形状上就干净了。 */
                Modifier
                    .matchParentSize()
                    .clip(finalShape)
                    .drawPrismalGlass(
                        backdrop = theBackdrop,
                        shape = { finalShape },
                        effects = {
                            contentTick?.invoke()
                            material.applyEffects(
                                this,
                                density,
                                refractBoost = 1f + 0.28f * pressProgress
                            )
                        },
                        specular = material.specularProvider,
                        depthShadow = if (material.depthShadowEnabled && level == GlassLevel.Floating) {
                            { com.styropyr0.prismal.depth.PrismalDepthShadow.Default }
                        } else null,
                        /* 导出这一层玻璃给子控件采样（嵌套玻璃） */
                        nestedGlassSource = exportLayer,
                        onDrawSurface = {
                            material.drawSurface(this, dark = darkSurface, tint = tint, tintAlphaOverride = tintAlpha)
                        }
                    )
            )
        }
    }

    val body: @Composable () -> Unit = {
        Box(modifier = modifier.then(pressLayer), contentAlignment = contentAlignment) {
            /* 玻璃层必须画在「导出 provider」之外，否则它会把自己当父层，形成自引用。
               它采样的仍然是外层（上一级卡片或整屏背景）。 */
            glassLayer()
            if (exportLayer != null) {
                CompositionLocalProvider(LocalPrismalParentGlassLayer provides exportLayer) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = contentAlignment, content = content)
                }
            } else {
                Box(Modifier.fillMaxWidth(), contentAlignment = contentAlignment, content = content)
            }
        }
    }

    body()
}

/* ---------------- 通用玻璃容器（保留旧语义） ---------------- */

/**
 * 通用玻璃容器。Control / Floating 走 PrismalAGSL 真液态玻璃，
 * Panel / Row 用更轻的雾面（内容面板不该每块都折射得花里胡哨）。
 */
@Composable
fun LiquidGlass(
    modifier: Modifier = Modifier,
    level: GlassLevel = GlassLevel.Panel,
    radius: Dp = Dp.Unspecified,
    customShape: Shape? = null,
    interactive: Boolean = false,
    interactionSource: MutableInteractionSource? = null,
    pressScaleFactor: Float = PressScale,
    ringScale: Float = 1f,
    nested: Boolean = false,
    contentTick: (() -> Unit)? = null,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit
) {
    val tuning = LocalGlassTuning.current
    val effectiveRadius = if (radius == Dp.Unspecified) tuning.cornerRadiusDp.dp else radius
    LiquidSurface(
        modifier = modifier,
        level = level,
        radius = effectiveRadius,
        shape = customShape ?: RoundedCornerShape(effectiveRadius),
        rimScale = ringScale,
        interactive = interactive,
        interactionSource = interactionSource,
        pressScaleFactor = pressScaleFactor,
        nested = nested,
        contentTick = contentTick,
        contentAlignment = contentAlignment,
        content = content
    )
}

/**
 * 子控件该采样哪一层背景。
 *
 * 嵌在卡片里的控件（分段控件 / 滑块 / 开关 / 胶囊按钮）必须采样「父卡片的玻璃成品」，
 * 而不是整屏壁纸——否则卡片是浅色、控件却是透过卡片看到的一小块壁纸，
 * 看起来就像卡片上被挖了个洞。没有父卡片时退回整屏背景。
 */
@Composable
internal fun glassBackdropForChild(): PrismalBackdrop? =
    LocalPrismalParentGlassLayer.current ?: LocalPrismalBackdrop.current
