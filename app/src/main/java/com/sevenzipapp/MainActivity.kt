package com.sevenzipapp

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
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
import java.io.FileInputStream
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
        var tempFile: File? = null
        try {
            tvStatus.text = "正在处理..."

            val tempFileNew = File(cacheDir, "temp_${System.currentTimeMillis()}.7z")
            pendingTempFile = tempFileNew
            
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFileNew).use { output ->
                    IOUtils.copy(input, output)
                }
            }
            tempFile = tempFileNew

            var fileName = "unknown"
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) fileName = cursor.getString(nameIndex)
                }
            }

            val outputDir = File(filesDir, "extracted_" + System.currentTimeMillis())
            outputDir.mkdirs()

            val sevenZFile = if (password.isNullOrEmpty()) {
                SevenZFile(tempFile)
            } else {
                SevenZFile(tempFile, password.toCharArray())
            }

            sevenZFile.use { szf ->
                var entry: SevenZArchiveEntry? = szf.nextEntry
                while (entry != null) {
                    try {
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
                        e.printStackTrace()
                        tvStatus.append("\n跳过失败: ${entry.name} -> ${e.message}")
                    }
                    entry = szf.nextEntry
                }
            }

            tvStatus.text = "解压完成！正在导出到下载目录..."
            
            // 新增：遍历解压目录，把文件拷贝到下载目录
            outputDir.listFiles()?.forEach { file ->
                if (file.isFile) {
                    copyToDownloads(this, file)
                }
            }

        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            if (msg.contains("password", ignoreCase = true) || msg.contains("encrypted", ignoreCase = true)) {
                tvStatus.text = "该文件已加密，需要密码"
                showPasswordDialog()
            } else {
                tvStatus.text = "解压失败：${e.javaClass.simpleName}: $msg"
                Toast.makeText(this, "解压失败：${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            }
            e.printStackTrace()
        } finally {
            tempFile?.delete()
        }
    }

    // 新增：导出到公共下载目录的方法
    private fun copyToDownloads(context: Context, sourceFile: File) {
        try {
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, sourceFile.name)
                put(MediaStore.MediaColumns.MIME_TYPE, "*/*")
                put(MediaStore.MediaColumns.SIZE, sourceFile.length())
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                uri?.let {
                    resolver.openOutputStream(it).use { outputStream ->
                        FileInputStream(sourceFile).use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                }
            } else {
                val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val destFile = File(downloadDir, sourceFile.name)
                FileInputStream(sourceFile).use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            Toast.makeText(this, "文件已导出到：下载目录/${sourceFile.name}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "导出到下载目录失败：${e.message}", Toast.LENGTH_LONG).show()
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
