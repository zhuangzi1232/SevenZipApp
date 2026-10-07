package com.sevenzipapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private val PICK_FILE_REQUEST = 100
    private val REQUEST_MANAGE_STORAGE = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        checkAndRequestPermissions()

        val selectBtn = findViewById<Button>(R.id.btnSelect)
        selectBtn.setOnClickListener {
            if (checkStoragePermission()) {
                pick7zFile()
            } else {
                checkAndRequestPermissions()
            }
        }
    }

    private fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                AlertDialog.Builder(this)
                    .setTitle("需要权限")
                    .setMessage("应用需要「所有文件访问权限」才能解压文件，请点击确认前往授权。")
                    .setPositiveButton("确认") { _, _ ->
                        try {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                            intent.data = Uri.parse("package:$packageName")
                            startActivityForResult(intent, REQUEST_MANAGE_STORAGE)
                        } catch (e: Exception) {
                            val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                            startActivityForResult(intent, REQUEST_MANAGE_STORAGE)
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) 
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, 
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 100)
            }
        }
    }

    private fun checkStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) 
                == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MANAGE_STORAGE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    Toast.makeText(this, "授权成功", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "未获得权限，可能无法解压", Toast.LENGTH_SHORT).show()
                }
            }
        }
        if (requestCode == PICK_FILE_REQUEST && data != null) {
            val uri: Uri? = data.data
            if (uri != null) {
                extract7zFile(uri)
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

    private fun extract7zFile(uri: Uri) {
        Thread {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                val sevenZFile = SevenZFile(inputStream)
                val outDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SevenZipOutput")
                if (!outDir.exists()) outDir.mkdirs()

                var entry: SevenZArchiveEntry?
                val extractedFiles = mutableListOf<String>()

                while (sevenZFile.nextEntry.also { entry = it } != null) {
                    val outFile = File(outDir, entry!!.name)
                    if (entry!!.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        val outputStream = FileOutputStream(outFile)
                        val buffer = ByteArray(8192)
                        var len: Int
                        while (sevenZFile.read(buffer).also { len = it } != -1) {
                            outputStream.write(buffer, 0, len)
                        }
                        outputStream.close()
                        extractedFiles.add(outFile.name)
                    }
                }
                sevenZFile.close()
                inputStream?.close()

                runOnUiThread {
                    showCompletionDialog(extractedFiles, outDir)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "解压失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun showCompletionDialog(files: List<String>, outDir: File) {
        val message = if (files.isEmpty()) "无文件" else files.joinToString("\n")
        AlertDialog.Builder(this)
            .setTitle("解压完成")
            .setMessage("已解压文件:\n$message")
            .setPositiveButton("打开下载目录") { _, _ ->
                openDownloadDirectory(outDir)
            }
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
