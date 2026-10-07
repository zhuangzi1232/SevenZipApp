package com.sevenzipapp

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private val REQUEST_MANAGE_STORAGE = 100
    private val PICK_FILE_REQUEST = 200

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 修复：绑定正确的按钮 ID (btnSelectFile)
        val btnSelectFile = findViewById<Button>(R.id.btnSelectFile)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)

        // 申请所有文件访问权限 (Android 11+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:$packageName")
                    startActivityForResult(intent, REQUEST_MANAGE_STORAGE)
                } catch (e: Exception) {
                    Toast.makeText(this, "请手动授予所有文件访问权限", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // 点击选择文件
        btnSelectFile.setOnClickListener {
            pick7zFile()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MANAGE_STORAGE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (android.os.Environment.isExternalStorageManager()) {
                    Toast.makeText(this, "授权成功", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "未获得权限，可能无法解压", Toast.LENGTH_SHORT).show()
                }
            }
        }
        if (requestCode == PICK_FILE_REQUEST && data != null) {
            val uri: Uri? = data.data
            if (uri != null) {
                // 选中文件后，弹出密码输入框
                showPasswordDialog(uri)
            }
        }
    }

    private fun pick7zFile() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "*/*"
        val mimeTypes = arrayOf("application/x-7z-compressed", "application/7z")
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
        startActivityForResult(intent, PICK_FILE_REQUEST)
    }

    // 新增：密码输入弹窗
    private fun showPasswordDialog(uri: Uri) {
        val input = android.widget.EditText(this)
        input.hint = "如果无密码请直接点击确定"
        
        AlertDialog.Builder(this)
            .setTitle("输入解压密码")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val password = input.text.toString()
                extract7zFile(uri, password)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun extract7zFile(uri: Uri, password: String) {
        Thread {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                
                // 修复：7z 解压不支持直接读 InputStream，先存为临时文件
                val tempFile = File(cacheDir, "temp.7z")
                tempFile.outputStream().use { fileOut ->
                    inputStream?.copyTo(fileOut)
                }
                inputStream?.close()

                // 修复：传入密码支持加密解压
                val sevenZFile = if (password.isNotEmpty()) {
                    SevenZFile(tempFile, password.toCharArray())
                } else {
                    SevenZFile(tempFile)
                }
                    
                val outDir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "SevenZipOutput")
                if (!outDir.exists()) outDir.mkdirs()

                val extractedFiles = mutableListOf<String>()
                
                // 修复：变量初始化问题
                var entry: SevenZArchiveEntry? = sevenZFile.nextEntry
                while (entry != null) {
                    val outFile = File(outDir, entry.name)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        val outputStream = FileOutputStream(outFile)
                        val buffer = ByteArray(8192)
                        // 修复：变量 len 初始化问题
                        var len = sevenZFile.read(buffer)
                        while (len != -1) {
                            outputStream.write(buffer, 0, len)
                            len = sevenZFile.read(buffer)
                        }
                        outputStream.close()
                        extractedFiles.add(outFile.name)
                    }
                    entry = sevenZFile.nextEntry
                }
                sevenZFile.close()
                tempFile.delete()

                runOnUiThread { showCompletionDialog(extractedFiles, outDir) }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "解压失败: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun showCompletionDialog(files: List<String>, outDir: File) {
        val message = if (files.isEmpty()) "无文件" else files.joinToString("\n")
        AlertDialog.Builder(this)
            .setTitle("解压完成")
            .setMessage("已解压文件:\n$message")
            .setPositiveButton("打开下载目录") { _, _ -> openDownloadDirectory(outDir) }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun openDownloadDirectory(dir: File) {
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(Uri.parse(dir.path), "*/*")
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开目录", Toast.LENGTH_SHORT).show()
        }
    }
}
