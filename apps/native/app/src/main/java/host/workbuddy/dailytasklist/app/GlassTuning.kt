package host.workbuddy.dailytasklist.app

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * 液态玻璃的全局可调参数。用户可以在「玻璃设置」页里实时调节这些值，
 * 立即看到通透度 / 折射 / 色散的变化，且会持久化到本地，重启不丢。
 *
 * 所有玻璃组件（LiquidSurface / PrimaryButton / Pill 等）统一从这里取值，
 * 这是「通透度」「折射强度」的单一控制源。
 */
@Stable
class GlassTuning {
    /* 模糊半径：越大越毛玻璃、越不透；越小越通透。用户说的「通透度」主要就是这个。 */
    var blurRadiusDp by mutableFloatStateOf(6f)

    /* 折射：height = 折射影响的高度带宽，amount = 边缘折弯的像素幅度。 */
    var refractionHeightDp by mutableFloatStateOf(14f)
    var refractionAmountDp by mutableFloatStateOf(20f)

    /* 色散（色差）：边缘红蓝分离的强度。 */
    var chromaticAberration by mutableFloatStateOf(0.14f)

    /* 玻璃染色强度：主按钮 / 着色玻璃 / 胶囊的 tintAlpha。
       0 = 完全通透（按钮变成浅玻璃，文字自动转深色），1 = 接近实心色块。
       默认 0.82 让「新增任务 / 保存 / 删除」这类主按钮一眼能认出颜色。 */
    var tintAlpha by mutableFloatStateOf(0.82f)

    /* 明度（brightness）：玻璃采样背景后的整体明暗。正数更亮、负数更暗。 */
    var brightness by mutableFloatStateOf(0f)

    /* 饱和度（saturation）：玻璃折射后的色彩浓度。1.0 = 原色，越高越艳、越低越灰。 */
    var saturation by mutableFloatStateOf(1.5f)

    /* 是否用固定 vibrancy（鲜艳度）滤镜。true 时明度/饱和度被忽略（走库内置饱和滤镜）；
       false 时改用手动 colorControls，明度/饱和度才会真正生效。拖动明度滑块会自动关掉它。 */
    var useVibrancy by mutableStateOf(true)

    /* Surface Tint（表面染色）：在玻璃表面叠一层白色，让底色变白/变亮。0 = 不染色。 */
    var surfaceTintAlpha by mutableFloatStateOf(0f)

    /* 全局圆角：所有玻璃面板的默认圆角半径。0 = 直角。 */
    var cornerRadiusDp by mutableFloatStateOf(22f)

    /* 可读性强度：玻璃底部那层「实色薄底」的倍率。
       1.0 = 标准（文字清晰 + 保留通透）；0 = 完全不垫底（最通透但文字容易糊）；
       越大文字越清楚、玻璃越像磨砂面板。用户觉得看不清时优先调这个。 */
    var readability by mutableFloatStateOf(1f)

    /* 是否叠加 specular 白色高光描边（用户觉得「白边太假」时关掉它）。 */
    var specularEnabled by mutableStateOf(false)

    /* 是否叠加投影（depth shadow），浮层才需要。 */
    var depthShadowEnabled by mutableStateOf(true)

    /* 文字颜色模式：0 = 跟随系统深浅色；1 = 强制深色文字（适合浅背景/亮壁纸）；
       2 = 强制浅色文字（适合暗背景/暗壁纸）。用户反馈跟随系统判断有时和自己
       实际看到的背景不匹配，导致文字看不清，所以开放手动切换。 */
    var textMode by mutableIntStateOf(0)

    fun reset() {
        blurRadiusDp = 6f
        refractionHeightDp = 14f
        refractionAmountDp = 20f
        chromaticAberration = 0.14f
        tintAlpha = 0.82f
        brightness = 0f
        saturation = 1.5f
        useVibrancy = true
        surfaceTintAlpha = 0f
        cornerRadiusDp = 22f
        readability = 1f
        specularEnabled = false
        depthShadowEnabled = true
        textMode = 0
    }

    fun copyFrom(other: GlassTuning) {
        blurRadiusDp = other.blurRadiusDp
        refractionHeightDp = other.refractionHeightDp
        refractionAmountDp = other.refractionAmountDp
        chromaticAberration = other.chromaticAberration
        tintAlpha = other.tintAlpha
        brightness = other.brightness
        saturation = other.saturation
        useVibrancy = other.useVibrancy
        surfaceTintAlpha = other.surfaceTintAlpha
        cornerRadiusDp = other.cornerRadiusDp
        readability = other.readability
        specularEnabled = other.specularEnabled
        depthShadowEnabled = other.depthShadowEnabled
        textMode = other.textMode
    }
}

/** 全局玻璃参数。由 AppRoot 提供，玻璃组件从这里读实时值。 */
internal val LocalGlassTuning = compositionLocalOf<GlassTuning> { GlassTuning() }

/** 玻璃参数持久化。存 SharedPreferences，键名独立于业务数据。 */
object GlassTuningStorage {
    private const val PREFS = "glass_tuning"

    fun load(context: Context, tuning: GlassTuning) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.contains("blur")) return
        tuning.blurRadiusDp = p.getFloat("blur", tuning.blurRadiusDp)
        tuning.refractionHeightDp = p.getFloat("refr_h", tuning.refractionHeightDp)
        tuning.refractionAmountDp = p.getFloat("refr_a", tuning.refractionAmountDp)
        tuning.chromaticAberration = p.getFloat("chroma", tuning.chromaticAberration)
        tuning.tintAlpha = p.getFloat("tint2", tuning.tintAlpha)
        tuning.brightness = p.getFloat("bright", tuning.brightness)
        tuning.saturation = p.getFloat("sat", tuning.saturation)
        tuning.useVibrancy = p.getBoolean("vibrancy", tuning.useVibrancy)
        tuning.surfaceTintAlpha = p.getFloat("surf_tint", tuning.surfaceTintAlpha)
        tuning.cornerRadiusDp = p.getFloat("corner", tuning.cornerRadiusDp)
        tuning.readability = p.getFloat("readability", tuning.readability)
        tuning.specularEnabled = p.getBoolean("specular", tuning.specularEnabled)
        tuning.depthShadowEnabled = p.getBoolean("shadow", tuning.depthShadowEnabled)
        tuning.textMode = p.getInt("text_mode", tuning.textMode)
    }

    fun save(context: Context, tuning: GlassTuning) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putFloat("blur", tuning.blurRadiusDp)
            .putFloat("refr_h", tuning.refractionHeightDp)
            .putFloat("refr_a", tuning.refractionAmountDp)
            .putFloat("chroma", tuning.chromaticAberration)
            .putFloat("tint2", tuning.tintAlpha)
            .putFloat("bright", tuning.brightness)
            .putFloat("sat", tuning.saturation)
            .putBoolean("vibrancy", tuning.useVibrancy)
            .putFloat("surf_tint", tuning.surfaceTintAlpha)
            .putFloat("corner", tuning.cornerRadiusDp)
            .putFloat("readability", tuning.readability)
            .putBoolean("specular", tuning.specularEnabled)
            .putBoolean("shadow", tuning.depthShadowEnabled)
            .putInt("text_mode", tuning.textMode)
            .apply()
    }
}

/** 记住全局玻璃参数，并监听变化自动持久化。 */
@Composable
fun rememberGlassTuning(): GlassTuning {
    val context = LocalContext.current
    val tuning = remember { GlassTuning().also { GlassTuningStorage.load(context, it) } }
    return tuning
}
