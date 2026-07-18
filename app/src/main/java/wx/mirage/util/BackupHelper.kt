package wx.mirage.util

import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/**
 * 备份管理 UI 辅助类
 */
object BackupHelper {

    fun createBackup(context: Context) {
        val path = BackupManager.createBackup(context)
        if (path != null) {
            Toast.makeText(context, "备份成功: $path", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "备份失败", Toast.LENGTH_SHORT).show()
        }
    }

    fun showRestoreBackupDialog(context: Context, onRestoreCompleted: () -> Unit = {}) {
        val backups = BackupManager.listBackups(context)
        if (backups.isEmpty()) {
            Toast.makeText(context, "没有可用的备份文件", Toast.LENGTH_SHORT).show()
            return
        }

        val backupNames = backups.map { it.name }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle("选择要恢复的备份")
            .setItems(backupNames) { _, which ->
                val backup = backups[which]
                AlertDialog.Builder(context)
                    .setTitle("确认恢复")
                    .setMessage("确定要恢复备份 \"${backup.name}\" 吗？\n当前配置将被替换。")
                    .setPositiveButton("恢复") { _, _ ->
                        val success = BackupManager.restoreBackup(context, backup.path)
                        if (success) {
                            onRestoreCompleted()
                            Toast.makeText(context, "配置恢复成功", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "配置恢复失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    fun showListBackupsDialog(context: Context) {
        val backups = BackupManager.listBackups(context)
        if (backups.isEmpty()) {
            Toast.makeText(context, "没有可用的备份文件", Toast.LENGTH_SHORT).show()
            return
        }

        val sb = StringBuilder()
        for ((index, backup) in backups.withIndex()) {
            sb.append("${index + 1}. ${backup.name}\n")
            sb.append("   大小: ${BackupManager.formatFileSize(backup.size)}\n")
            sb.append("   时间: ${BackupManager.formatTimestamp(backup.lastModified)}\n\n")
        }

        AlertDialog.Builder(context)
            .setTitle("备份文件列表")
            .setMessage(sb.toString().trim())
            .setPositiveButton("关闭", null)
            .show()
    }

    fun showDeleteOldBackupsDialog(context: Context, onDeleted: () -> Unit = {}) {
        val backups = BackupManager.listBackups(context)
        if (backups.isEmpty()) {
            Toast.makeText(context, "没有可用的备份文件", Toast.LENGTH_SHORT).show()
            return
        }

        val backupNames = backups.map { it.name }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle("选择要删除的备份")
            .setItems(backupNames) { _, which ->
                val backup = backups[which]
                AlertDialog.Builder(context)
                    .setTitle("确认删除")
                    .setMessage("确定要删除备份 \"${backup.name}\" 吗？")
                    .setPositiveButton("删除") { _, _ ->
                        val success = BackupManager.deleteBackup(context, backup.path)
                        if (success) {
                            onDeleted()
                            Toast.makeText(context, "备份已删除", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "删除失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}