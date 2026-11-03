package tech.touka.amapnavi.ui.setting

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import tech.touka.amapnavi.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 决策日志管理与导出工作台
 */
class LogManagerActivity : AppCompatActivity() {

    private var currentType = "app" // "app" or "overtake"
    private var selectedFile: File? = null

    private lateinit var tvCurrentLogName: TextView
    private lateinit var tvLogContent: TextView
    private lateinit var lvFiles: ListView
    private lateinit var btnTabApp: Button
    private lateinit var btnTabOvertake: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log_manager)

        initViews()
        loadLogFiles()
    }

    private fun initViews() {
        tvCurrentLogName = findViewById(R.id.tv_current_log_name)
        tvLogContent = findViewById(R.id.tv_log_content)
        lvFiles = findViewById(R.id.lv_log_files)
        btnTabApp = findViewById(R.id.btn_tab_app_log)
        btnTabOvertake = findViewById(R.id.btn_tab_overtake_log)

        findViewById<Button>(R.id.btn_log_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_log_refresh).setOnClickListener { loadLogFiles() }

        btnTabApp.setOnClickListener {
            currentType = "app"
            btnTabApp.setBackgroundColor(0xFF1976D2.toInt())
            btnTabOvertake.setBackgroundColor(0xFF333333.toInt())
            loadLogFiles()
        }

        btnTabOvertake.setOnClickListener {
            currentType = "overtake"
            btnTabOvertake.setBackgroundColor(0xFF1976D2.toInt())
            btnTabApp.setBackgroundColor(0xFF333333.toInt())
            loadLogFiles()
        }

        findViewById<Button>(R.id.btn_log_copy).setOnClickListener {
            val content = tvLogContent.text.toString()
            if (content.isNotEmpty() && selectedFile != null) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.primaryClip = ClipData.newPlainText("AmapNavi Log", content)
                Toast.makeText(this, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btn_log_share).setOnClickListener {
            val file = selectedFile
            if (file != null && file.exists()) {
                shareLogFile(file)
            } else {
                Toast.makeText(this, "请先选择日志文件", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btn_log_clear_all).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("确认删除")
                .setMessage("确定要清空全部 $currentType 日志文件吗？此操作不可逆。")
                .setPositiveButton("全删") { _, _ ->
                    clearAllLogs()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun getLogDir(): File {
        val base = File(Environment.getExternalStorageDirectory(), "logs")
        val dir = File(base, currentType)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun loadLogFiles() {
        val dir = getLogDir()
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
        val names = files.map { f ->
            val dateStr = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(f.lastModified()))
            "${f.name}\n$dateStr  ${f.length() / 1024} KB"
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
        lvFiles.adapter = adapter

        if (files.isNotEmpty()) {
            displayFile(files[0])
        } else {
            selectedFile = null
            tvCurrentLogName.text = "暂无 $currentType 日志"
            tvLogContent.text = "— 暂无内容 —"
        }

        lvFiles.setOnItemClickListener { _, _, position, _ ->
            if (position in files.indices) {
                displayFile(files[position])
            }
        }
    }

    private fun displayFile(file: File) {
        selectedFile = file
        tvCurrentLogName.text = file.name
        try {
            val text = file.readText(Charsets.UTF_8)
            tvLogContent.text = if (text.isNotEmpty()) text else "— 文件为空 —"
        } catch (e: Exception) {
            tvLogContent.text = "读取日志失败: ${e.message}"
        }
    }

    private fun clearAllLogs() {
        val dir = getLogDir()
        dir.listFiles()?.forEach { it.delete() }
        Toast.makeText(this, "已清空日志", Toast.LENGTH_SHORT).show()
        loadLogFiles()
    }

    private fun shareLogFile(file: File) {
        try {
            val uri: Uri = if (android.os.Build.VERSION.SDK_INT >= 24) {
                FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            } else {
                Uri.fromFile(file)
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "分享日志文件"))
        } catch (e: Exception) {
            Toast.makeText(this, "分享失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
