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
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private var pendingUri: Uri? = null
    private val PICK_FILE_REQUEST = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
        }

        val selectBtn = Button(this).apply {
            text = "选择7Z文件并解压"
        }

        tvStatus = TextView(this).apply {
            text = "等待操作..."
        }

        layout.addView(selectBtn)
        layout.addView(tvStatus)

        setContentView(layout)

        selectBtn.setOnClickListener {
            Toast.makeText(this, "按钮被点击了", Toast.LENGTH_SHORT).show()
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            startActivityForResult(intent, PICK_FILE_REQUEST)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_FILE_REQUEST && resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri != null) {
                pendingUri = uri
                copyAndExtract(uri, null)
            }
        }
    }

    private fun copyAndExtract(uri: Uri, password: String?) {
        tvStatus.text = "开始处理..."
        Thread {
            var tempFile: File? = null
            try {
                tempFile = File(cacheDir, "temp.7z")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }

                val outputDir = File(cacheDir, "unzip_output").apply { mkdirs() }

                val sevenZFile = if (!password.isNullOrEmpty()) {
                    SevenZFile(tempFile, password.toCharArray())
                } else {
                    SevenZFile(tempFile)
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

                runOnUiThread {
                    tvStatus.text = "解压完成！正在导出到下载目录..."
                    outputDir.listFiles()?.forEach { file ->
                        if (file.isFile) {
                            copyToDownloads(this, file)
                        }
                    }
                    Toast.makeText(this, "解压完成！", Toast.LENGTH_LONG).show()
                }

            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                runOnUiThread {
                    if (msg.contains("password", ignoreCase = true) || msg.contains("encrypted", ignoreCase = true)) {
                        tvStatus.text = "该文件已加密，需要密码"
                        showPasswordDialog()
                    } else {
                        tvStatus.text = "解压失败：${e.javaClass.simpleName}"
                        Toast.makeText(this, "解压失败：${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                    }
                }
                e.printStackTrace()
            } finally {
                tempFile?.delete()
            }
        }.start()
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
            runOnUiThread {
                Toast.makeText(this, "文件已导出到：下载目录/${sourceFile.name}", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            runOnUiThread {
                Toast.makeText(this, "导出到下载目录失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showPasswordDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("输入解压密码")

        val input = EditText(this)
        input.hint = "请输入压缩包密码"
        input.inputType = android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD

        builder.setView(input)

        builder.setPositiveButton("确定") { _: DialogInterface, _: Int ->
            val pwd = input.text.toString().trim()
            pendingUri?.let { copyAndExtract(it, pwd) }
        }
        builder.setNegativeButton("取消") { dialog: DialogInterface, _: Int ->
            dialog.cancel()
            tvStatus.text = "已取消解压"
        }
        builder.show()
    }
}
