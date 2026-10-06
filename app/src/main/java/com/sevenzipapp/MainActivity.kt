package com.sevenzipapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
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

    private val selectFileLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                pendingUri = uri
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

    private fun copyUriToTemp(uri: Uri): File {
        val tempFile = File(cacheDir, "temp_${System.currentTimeMillis()}.7z")
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output ->
                IOUtils.copy(input, output)
            }
        }
        return tempFile
    }

    private fun copyAndExtract(uri: Uri, password: String?) {
        var tempFile: File? = null
        try {
            tvStatus.text = "正在处理..."
            tempFile = copyUriToTemp(uri)

            val outputDir = File(filesDir, "extracted_" + System.currentTimeMillis())
            outputDir.mkdirs()

            // 按密码有无构造 SevenZFile；char[] 由库内部按 7z 规则处理，避免手动转编码
            val sevenZFile = if (password.isNullOrEmpty()) {
                SevenZFile(tempFile)
            } else {
                SevenZFile(tempFile, password.toCharArray())
            }

            sevenZFile.use { szf ->
                var entry: SevenZArchiveEntry? = szf.nextEntry
                while (entry != null) {
                    try {
                        // 打日志：看压缩/加密方法，排错用
                        Log.d("SevenZip", "entry=${entry.name} methods=${entry.contentMethods}")
                        val outFile = File(outputDir, entry.name)
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            szf.getInputStream(entry).use { ins ->
                                FileOutputStream(outFile).use { fos ->
                                    IOUtils.copy(ins, fos)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // 单个文件失败不整体中断，记录后继续
                        Log.e("SevenZip", "单文件解压失败: ${entry.name}", e)
                        tvStatus.append("\n跳过失败: ${entry.name} -> ${e.message}")
                    }
                    entry = szf.nextEntry
                }
            }

            tvStatus.text = "解压完成！文件保存在：${outputDir.absolutePath}"
            Toast.makeText(this, "解压成功！", Toast.LENGTH_LONG).show()

        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            Log.e("SevenZip", "整体解压失败", e)
            if (msg.contains("password", ignoreCase = true)
                || msg.contains("encrypted", ignoreCase = true)
                || e is org.apache.commons.compress.PasswordRequiredException
            ) {
                tvStatus.text = "该文件已加密，需要密码"
                showPasswordDialog()
            } else {
                tvStatus.text = "解压失败：${e.javaClass.simpleName}: $msg"
                Toast.makeText(this, "解压失败：${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            }
        } finally {
            // 清临时文件，避免下次复用旧实例
            tempFile?.delete()
        }
    }

    private fun showPasswordDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("输入解压密码")

        val input = EditText(this)
        input.hint = "请输入压缩包密码"
        input.inputType = android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        builder.setView(input)

        builder.setPositiveButton("确定") { _, _ ->
            val pwd = input.text.toString().trim()
            pendingUri?.let { copyAndExtract(it, pwd) }
        }
        builder.setNegativeButton("取消") { dialog, _ ->
            dialog.cancel()
            tvStatus.text = "已取消解压"
        }
        builder.show()
    }
}
