package com.sevenzipapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.utils.IOUtils
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var btnSelectFile: Button

    private var pendingUri: Uri? = null
    private var pendingTempFile: File? = null

    private val selectFileLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                pendingUri = uri
                // 初次选择文件，先尝试不传密码解压
                copyAndExtract(uri, null)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        btnSelectFile = findViewById(R.id.btnSelectFile)

        btnSelectFile.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            selectFileLauncher.launch(intent)
        }
    }

    private fun copyAndExtract(uri: Uri, password: String?) {
        try {
            tvStatus.text = "正在处理..."

            val tempFile = File(cacheDir, "temp.7z")
            pendingTempFile = tempFile
            
            // 复制输入流到临时文件
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    IOUtils.copy(input, output)
                }
            }

            // 获取文件名
            var fileName = "unknown"
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) fileName = cursor.getString(nameIndex)
                }
            }

            // 创建输出目录
            val outputDir = File(filesDir, "extracted_" + System.currentTimeMillis())
            outputDir.mkdirs()

            // 核心改动：如果有密码则传入密码（转成 char[]），否则不传
            val sevenZFile = if (password.isNullOrEmpty()) {
                SevenZFile(tempFile)
            } else {
                SevenZFile(tempFile, password.toCharArray())
            }

            // 解压逻辑
            sevenZFile.use { szf ->
                var entry: SevenZArchiveEntry? = szf.nextEntry
                while (entry != null) {
                    val outputFile = File(outputDir, entry.name)
                    if (entry.isDirectory) {
                        outputFile.mkdirs()
                    } else {
                        outputFile.parentFile?.mkdirs()
                        szf.getInputStream(entry).use { inputStream ->
                            FileOutputStream(outputFile).use { fos ->
                                IOUtils.copy(inputStream, fos)
                            }
                        }
                    }
                    entry = szf.nextEntry
                }
            }

            tvStatus.text = "解压完成！文件保存在：${outputDir.absolutePath}"
            Toast.makeText(this, "解压成功！", Toast.LENGTH_LONG).show()

        } catch (e: Exception) {
            val msg = e.message ?: "未知错误"
            // 核心改动：如果报错提示需要密码，则弹出密码输入框
            if (msg.contains("password", ignoreCase = true) || msg.contains("encrypted", ignoreCase = true)) {
                showPasswordDialog()
            } else {
                tvStatus.text = "解压失败：$msg"
                Toast.makeText(this, "解压失败：$msg", Toast.LENGTH_LONG).show()
            }
            e.printStackTrace()
        }
    }

    // 核心改动：弹出密码输入框
    private fun showPasswordDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("输入解压密码")

        val input = EditText(this)
        input.hint = "请输入压缩包密码"
        // 设置为密码密文显示模式[4](@ref)
        input.inputType = android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        
        builder.setView(input)

        builder.setPositiveButton("确定") { _, _ ->
            val pwd = input.text.toString()
            pendingUri?.let { copyAndExtract(it, pwd) }
        }
        builder.setNegativeButton("取消") { dialog, _ ->
            dialog.cancel()
            tvStatus.text = "已取消解压"
        }
        builder.show()
    }
}
