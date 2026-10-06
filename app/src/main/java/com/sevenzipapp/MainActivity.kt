package com.sevenzipapp

import android.content.ContentValues
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.sevenzipapp.databinding.ActivityMainBinding
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import org.apache.commons.io.IOUtils

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var pendingUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnSelectFile.setOnClickListener {
            selectFileLauncher.launch(arrayOf("*/*"))
        }
    }

    private val selectFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            pendingUri = it
            copyAndExtract(it, null)
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
            binding.tvStatus.text = "正在处理..."
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
                                IOUtils.copy(ins, fos)
                            }
                        }
                    }
                    entry = szf.nextEntry
                }
            }

            binding.tvStatus.text = "解压完成！正在导出到下载目录..."
            
            outputDir.listFiles()?.forEach { file ->
                if (file.isFile) {
                    copyToDownloads(this, file)
                }
            }
            Toast.makeText(this, "解压完成！", Toast.LENGTH_LONG).show()

        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            if (msg.contains("password", ignoreCase = true) || msg.contains("encrypted", ignoreCase = true)) {
                binding.tvStatus.text = "该文件已加密，需要密码"
                showPasswordDialog()
            } else {
                binding.tvStatus.text = "解压失败：${e.javaClass.simpleName}"
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
            binding.tvStatus.text = "已取消解压"
        }
        builder.show()
    }
}
