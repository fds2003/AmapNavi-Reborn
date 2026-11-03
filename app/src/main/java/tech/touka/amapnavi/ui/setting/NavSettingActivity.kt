package tech.touka.amapnavi.ui.setting

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * 领航参数兼容入口（自动定向至通用工作台 Tab 1，毫秒级瞬切）
 */
class NavSettingActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, SettingActivity::class.java).putExtra("tab", 1))
        finish()
    }
}
