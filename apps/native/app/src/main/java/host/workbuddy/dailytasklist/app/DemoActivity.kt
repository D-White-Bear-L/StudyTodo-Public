package host.workbuddy.dailytasklist.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider

/**
 * 液态玻璃 Demo 启动器（独立图标，不进主应用页面树）。
 *
 * 为什么单独开一个 Activity 而不是在主应用里加一页：
 *   · 这个页面要自己控制「哪一层被采样」，不能受主应用的 GlassBackground、
 *     LazyColumn、底部 Tab 影响；
 *   · 测试时点图标就能进，不用先翻到设置页。
 * 玻璃参数仍然复用 rememberGlassTuning()，所以设置页里调的那些值在这里同样生效。
 */
class DemoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val tuning = rememberGlassTuning()
            CompositionLocalProvider(LocalGlassTuning provides tuning) {
                FloatingNavDemo()
            }
        }
    }
}
