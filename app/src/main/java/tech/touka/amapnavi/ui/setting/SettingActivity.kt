package tech.touka.amapnavi.ui.setting

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import tech.touka.amapnavi.R
import tech.touka.amapnavi.data.prefs.UserSettingsRepository
import tech.touka.amapnavi.ui.main.MainActivity

/**
 * 1:1 完全对齐原版功能设置工作台（毫秒级硬件 ViewFlipper 瞬切，零延迟、全参数覆盖）
 * R-1：退化为纯壳，仅负责挂载统一视图控制器 SettingViewController，
 * 三 Tab（通用/领航/超车）的控件绑定与保存逻辑全部由 Controller 承载，
 * 与 In-Window SAA 内嵌路径共用同一实现，保证两种进入方式行为一致。
 */
class SettingActivity : AppCompatActivity() {

    private lateinit var repo: UserSettingsRepository
    private var controller: SettingViewController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setting)

        repo = UserSettingsRepository(this)

        // 壳只挂载统一控制器：Tab 切换/控件绑定/保存回调均在 SettingViewController 内
        controller = SettingViewController(
            activity = this,
            rootView = findViewById(android.R.id.content),
            repo = repo,
            onCloseRequested = { returnToHome() }
        )
        controller?.showTab(intent.getIntExtra("tab", 0))
    }

    /** 返回主界面：FLAG_ACTIVITY_REORDER_TO_FRONT 复用已存在的 MainActivity 实例，避免重复创建。 */
    private fun returnToHome() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        startActivity(intent)
        overridePendingTransition(0, 0)
    }

    override fun onBackPressed() {
        returnToHome()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val tab = intent?.getIntExtra("tab", 0) ?: 0
        controller?.showTab(tab)
    }
}
