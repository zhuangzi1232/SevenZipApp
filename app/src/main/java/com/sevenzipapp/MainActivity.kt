package com.sevenzipapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.utils.IOUtils
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var btnSelectFile: Button

    private val selectFileLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                extractSevenZFile(uri)
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

    private fun extractSevenZFile(uri: Uri) {
        try {
            tvStatus.text = "正在解压..."

            // 获取文件名
            var fileName = "unknown.7z"
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) fileName = cursor.getString(nameIndex)
                }
            }

            // 创建输出目录（在应用内部存储）
            val outputDir = File(filesDir, "extracted_" + System.currentTimeMillis())
            outputDir.mkdirs()

            // 使用 commons-compress 解压 7z
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val sevenZFile = SevenZFile(inputStream.readBytes().inputStream().buffered())
                var entry: SevenZArchiveEntry? = sevenZFile.nextEntry
                while (entry != null) {
                    val outputFile = File(outputDir, entry.name)
                    if (entry.isDirectory) {
                        outputFile.mkdirs()
                    } else {
                        outputFile.parentFile?.mkdirs()
                        FileOutputStream(outputFile).use { fos ->
                            IOUtils.copy(sevenZFile.getContentAsStream(entry), fos)
                        }
                    }
                    entry = sevenZFile.nextEntry
                }
                sevenZFile.close()
            }

            tvStatus.text = "解压完成！文件保存在：${outputDir.absolutePath}"
            Toast.makeText(this, "解压成功！", Toast.LENGTH_LONG).show()

        } catch (e: Exception) {
            tvStatus.text = "解压失败：${e.message}"
            e.printStackTrace()
            Toast.makeText(this, "解压失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
