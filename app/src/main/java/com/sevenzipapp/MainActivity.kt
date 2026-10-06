package com.sevenzipapp

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.utils.IOUtils
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private val PICK_FILE_REQUEST_CODE = 100
    private val REQUEST_PERMISSION_CODE = 200
    private var pendingUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnSelect = findViewById<Button>(R.id.btnSelect)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)

        // 检查并申请权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                    Toast.makeText(this, "请开启“所有文件访问权限”", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQUEST_PERMISSION_CODE)
            }
        }

        btnSelect.setOnClickListener {
            Toast.makeText(this, "按钮被点击了", Toast.LENGTH_SHORT).show()
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(intent, PICK_FILE_REQUEST_CODE)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_FILE_REQUEST_CODE && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                pendingUri = uri
                copyAndExtract(uri, null)
            }
        }
    }

    private fun copyAndExtract(uri: Uri, password: String?) {
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        tvStatus.text = "开始处理..."
        val tempFile = File(cacheDir, "temp.7z")
        
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(tempFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

            val outputDir = File(filesDir, "extracted")
            if (!outputDir.exists()) outputDir.mkdirs()

            val sevenZFile = if (password.isNullOrEmpty()) {
                SevenZFile(tempFile)
            } else {
                SevenZFile(tempFile, password.toCharArray())
            }

            sevenZFile.use { szf ->
                var entry: SevenZArchiveEntry? = szf.nextEntry
                while (entry != null) {
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
                    entry = szf.nextEntry
                }
            }

            tvStatus.text = "解压完成！正在导出到下载目录..."
            
            outputDir.listFiles()?.forEach { file ->
                if (file.isFile) {
                    copyToDownloads(this, file)
                }
            }
            Toast.makeText(this, "解压完成！", Toast.LENGTH_LONG).show()

        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            if (msg.contains("password", ignoreCase = true) || msg.contains("encrypted", ignoreCase = true)) {
                tvStatus.text = "该文件已加密，需要密码"
                showPasswordDialog()
            } else {
                tvStatus.text = "解压失败：${e.javaClass.simpleName}"
                Toast.makeText(this, "解压失败：${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            }
            e.printStackTrace()
        } finally {
            tempFile.delete()
        }
    }

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
                    resolver.openOutputStream(it)?.use { outputStream ->
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
        builder.setNegativeButton("取消") { dialog: DialogInterface, _: Int ->
            dialog.cancel()
            val tvStatus = findViewById<TextView>(R.id.tvStatus)
            tvStatus.text = "已取消解压"
        }
        builder.show()
    }
}

    private fun copyUriToTemp(uri: Uri): File {
        val tempFile = File(cacheDir, "temp_${System.currentTimeMillis()}.7z")
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
            }
        }
        return tempFile
    }

    private fun copyAndExtract(uri: Uri, password: String?) {
        var tempFile: File? = null
        try {
            val tvStatus = findViewById<android.widget.TextView>(R.id.tvStatus)
            tvStatus.text = "正在处理..."

            tempFile = copyUriToTemp(uri)

            val outputDir = File(filesDir, "extracted_${System.currentTimeMillis()}")
            outputDir.mkdirs()

            val sevenZFile = if (password.isNullOrEmpty()) {
                SevenZFile(tempFile)
            } else {
                SevenZFile(tempFile, password.toCharArray())
            }

            sevenZFile.use { szf ->
                var entry: SevenZArchiveEntry? = szf.nextEntry
                while (entry != null) {
                    val outFile = File(outputDir, entry.name)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        szf.getInputStream(entry).use { ins ->
                            FileOutputStream(outFile).use { fos ->
                                ins.copyTo(fos)
                            }
                        }
                    }
                    entry = szf.nextEntry
                }
            }

            tvStatus.text = "解压完成！正在导出到下载目录..."

            outputDir.listFiles()?.forEach { file ->
                if (file.isFile) {
                    copyToDownloads(this, file)
                }
            }
            Toast.makeText(this, "解压完成！", Toast.LENGTH_LONG).show()

        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            val tvStatus = findViewById<android.widget.TextView>(R.id.tvStatus)
            if (msg.contains("password", ignoreCase = true) || msg.contains("encrypted", ignoreCase = true)) {
                tvStatus.text = "该文件已加密，需要密码"
                showPasswordDialog()
            } else {
                tvStatus.text = "解压失败：${e.javaClass.simpleName}"
                Toast.makeText(this, "解压失败：${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            }
            e.printStackTrace()
        } finally {
            tempFile?.delete()
        }
    }

    private fun copyToDownloads(context: Context, sourceFile: File) {
        try {
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, sourceFile.name)
                put(MediaStore.MediaColumns.MIME_TYPE, "*/*")
                put(MediaStore.MediaColumns.SIZE, sourceFile.length())
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.put(MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                uri?.let {
                    resolver.openOutputStream(it)?.use { outputStream ->
                        FileInputStream(sourceFile).use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                }
            } else {
                val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
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
        builder.setNegativeButton("取消") { dialog: DialogInterface, _: Int ->
            dialog.cancel()
            val tvStatus = findViewById<android.widget.TextView>(R.id.tvStatus)
            tvStatus.text = "已取消解压"
        }
        builder.show()
    }
}
