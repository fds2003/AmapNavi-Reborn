package tech.touka.amapnavi.ui.setting

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * 超车参数兼容入口（自动定向至通用工作台 Tab 2，毫秒级瞬切）
 */
class OvertakeSettingActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, SettingActivity::class.java).putExtra("tab", 2))
        finish()
    }
}
