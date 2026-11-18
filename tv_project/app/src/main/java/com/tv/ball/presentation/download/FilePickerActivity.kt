package com.tv.ball.presentation.download

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.tv.ball.databinding.ActivityFilePickerBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class FilePickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFilePickerBinding
    private lateinit var adapter: FilePickerAdapter
    private var currentPath: File = Environment.getExternalStorageDirectory()

    companion object {
        const val EXTRA_FILE_PATH = "file_path"
        const val REQUEST_CODE = 102
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFilePickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 设置紫色状态栏
        window.statusBarColor = android.graphics.Color.parseColor("#673AB7")

        setupViews()
        loadDirectory(currentPath)
    }

    private fun setupViews() {
        // 返回按钮
        binding.btnBack.setOnClickListener {
            finish()
        }

        // RecyclerView
        adapter = FilePickerAdapter(
            onItemClick = { file ->
                if (file.isDirectory) {
                    loadDirectory(file)
                } else {
                    // 选择文件
                    selectFile(file)
                }
            }
        )

        binding.rvFiles.apply {
            layoutManager = LinearLayoutManager(this@FilePickerActivity)
            adapter = this@FilePickerActivity.adapter
        }
    }

    private fun loadDirectory(directory: File) {
        currentPath = directory
        binding.tvPath.text = "路径: ${directory.absolutePath}"

        try {
            val files = directory.listFiles()?.toList() ?: emptyList()
            
            // 分类：文件夹在前，文件在后
            val sortedFiles = files.sortedWith(compareBy(
                { !it.isDirectory }, // 文件夹优先
                { it.name.lowercase() } // 按名称排序
            ))

            // 添加上级目录选项（如果不是根目录）
            val items = mutableListOf<FileItem>()
            if (directory.parent != null) {
                items.add(FileItem(
                    file = directory.parentFile!!,
                    name = "..",
                    isDirectory = true,
                    size = 0,
                    lastModified = 0
                ))
            }

            // 添加当前目录的文件和文件夹
            items.addAll(sortedFiles.map { file ->
                FileItem(
                    file = file,
                    name = file.name,
                    isDirectory = file.isDirectory,
                    size = if (file.isFile) file.length() else 0,
                    lastModified = file.lastModified()
                )
            })

            adapter.submitList(items)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun selectFile(file: File) {
        val intent = Intent()
        intent.putExtra(EXTRA_FILE_PATH, file.absolutePath)
        setResult(Activity.RESULT_OK, intent)
        finish()
    }

    override fun onBackPressed() {
        if (currentPath.parent != null) {
            loadDirectory(currentPath.parentFile!!)
        } else {
            super.onBackPressed()
        }
    }
}

data class FileItem(
    val file: File,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long
)
