package wx.mirage.util

import android.content.Context
import android.os.Environment
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import wx.mirage.config.ConfigManager

/**
 * Mirage 配置备份管理器
 *
 * 提供配置的自动备份、手动备份、恢复和备份文件管理功能。
 *
 * ## 功能列表
 * - [createBackup]: 创建带时间戳的备份文件，保存到外部存储 Mirage/backup 目录
 * - [restoreBackup]: 从指定备份文件恢复配置
 * - [listBackups]: 列出所有可用备份文件，按时间降序排列
 * - [deleteOldBackups]: 只保留最近 N 个备份，自动删除旧备份
 * - [autoBackupIfNeeded]: 自动备份检查，距离上次备份超过 24 小时则自动创建备份
 * - [lastBackupTime]: 获取上次自动备份的时间戳
 *
 * ## 备份文件格式
 * JSON 文件，包含以下字段：
 * - version: 备份格式版本号
 * - timestamp: 备份创建时间戳
 * - enabled: 模块启用状态
 * - hiddenIds: 隐藏好友 wxId 列表
 * - labels: 标签/备注映射
 *
 * ## 安全防护
 * - [restoreBackup] 在读取备份文件前，使用 [SecurityUtils.isValidPath] 验证文件路径
 *   是否在预期的备份目录内，防止路径遍历攻击
 * - [createBackup] 使用固定前缀和扩展名生成文件名，不直接使用用户输入作为文件名
 * - [listBackups] 仅列出符合预期命名模式的文件，过滤掉不相关的文件
 *
 * ## 线程安全
 * 本类为单例 object，所有文件操作均通过 File API 进行。
 * 备份目录的创建（mkdirs）和文件写入操作不是原子的，但并发创建备份文件
 * 不会导致数据损坏，因为每个备份文件使用不同的时间戳文件名。
 * 在极端并发场景下，建议在外部对备份操作进行序列化。
 *
 * ## 使用示例
 * ```kotlin
 * // 创建备份
 * val path = BackupManager.createBackup(context)
 *
 * // 列出所有备份
 * val backups = BackupManager.listBackups()
 *
 * // 恢复备份
 * val success = BackupManager.restoreBackup(context, "/path/to/backup.json")
 *
 * // 只保留最近 5 个备份
 * val deleted = BackupManager.deleteOldBackups(5)
 * ```
 */
object BackupManager {

    private const val MODULE_TAG = "BackupManager"
    private const val BACKUP_DIR_NAME = "Mirage/backup"
    private const val BACKUP_FILE_PREFIX = "mirage_backup_"
    private const val BACKUP_FILE_EXT = ".json"
    private const val AUTO_BACKUP_INTERVAL_MS = 24 * 60 * 60 * 1000L // 24 小时

    /** 备份格式版本号 */
    private const val BACKUP_VERSION = 1

    /** 允许的最大备份文件大小（字节），防止恶意超大文件 */
    private const val MAX_BACKUP_FILE_SIZE = 10 * 1024 * 1024L // 10MB

    /** Gson 实例 */
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    /** 日期格式化器，用于备份文件名 */
    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    /**
     * 获取备份目录。
     *
     * 目录不存在时自动创建。
     *
     * @return 备份目录 File 对象
     */
    private fun getBackupDir(): File {
        val dir = File(Environment.getExternalStorageDirectory(), BACKUP_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * 创建带时间戳的备份文件。
     *
     * 将当前 ConfigManager 的所有配置（enabled, hiddenIds, labels）导出为 JSON 文件，
     * 保存到外部存储的 Mirage/backup 目录下。
     *
     * 文件名格式: mirage_backup_yyyyMMdd_HHmmss.json
     *
     * @param context Android Context，用于读取配置
     * @return 备份文件的绝对路径，失败返回 null
     */
    fun createBackup(context: Context): String? {
        return try {
            val backupDir = getBackupDir()
            val timestamp = System.currentTimeMillis()
            val dateStr = dateFormat.format(Date(timestamp))
            // 文件名仅使用固定前缀 + 时间戳 + 固定扩展名，不接受用户输入
            val fileName = "$BACKUP_FILE_PREFIX$dateStr$BACKUP_FILE_EXT"
            val backupFile = File(backupDir, fileName)

            val allConfig = ConfigManager.getAllConfig(context)
            val data = mapOf(
                "version" to BACKUP_VERSION,
                "timestamp" to timestamp,
                "enabled" to allConfig["enabled"],
                "hiddenIds" to allConfig["hiddenIds"],
                "labels" to allConfig["labels"]
            )

            backupFile.writeText(gson.toJson(data))
            LogUtil.i(MODULE_TAG, "Backup created: ${backupFile.absolutePath}")
            backupFile.absolutePath
        } catch (e: Exception) {
            LogUtil.e(MODULE_TAG, "Failed to create backup: ${e.message}", e)
            null
        }
    }

    /**
     * 从指定备份文件恢复配置。
     *
     * 读取备份文件中的 JSON 数据，解析后调用 ConfigManager.setAllConfig() 进行批量恢复。
     * 恢复前会验证备份文件是否存在、格式正确，以及路径是否在预期的备份目录内。
     *
     * ## 安全验证
     * 1. 使用 [SecurityUtils.isValidPath] 验证文件路径在备份目录内，防止路径遍历攻击
     * 2. 检查文件是否存在且可读
     * 3. 检查文件大小不超过 [MAX_BACKUP_FILE_SIZE]，防止恶意超大文件
     * 4. 验证 JSON 基本结构（version 和 hiddenIds 字段）
     *
     * @param context Android Context，用于写入配置
     * @param filePath 备份文件的绝对路径
     * @return true 如果恢复成功，false 如果失败
     */
    fun restoreBackup(context: Context, filePath: String): Boolean {
        return try {
            // 安全验证：检查文件路径是否在备份目录内
            val backupDir = getBackupDir()
            if (!SecurityUtils.isValidPath(filePath, backupDir)) {
                LogUtil.w(MODULE_TAG, "Path traversal attempt blocked: $filePath is outside backup directory")
                return false
            }

            val backupFile = File(filePath)
            if (!backupFile.exists()) {
                LogUtil.w(MODULE_TAG, "Backup file not found: $filePath")
                return false
            }

            if (!backupFile.canRead()) {
                LogUtil.w(MODULE_TAG, "Backup file not readable: $filePath")
                return false
            }

            // 检查文件大小，防止恶意超大文件
            if (backupFile.length() > MAX_BACKUP_FILE_SIZE) {
                LogUtil.w(MODULE_TAG, "Backup file too large: ${backupFile.length()} bytes (max $MAX_BACKUP_FILE_SIZE)")
                return false
            }

            val jsonContent = backupFile.readText()
            val type = object : TypeToken<Map<String, Any>>() {}.type
            val data: Map<String, Any> = gson.fromJson(jsonContent, type)

            // 验证备份文件基本结构
            if (!data.containsKey("version") || !data.containsKey("hiddenIds")) {
                LogUtil.w(MODULE_TAG, "Invalid backup file format: $filePath")
                return false
            }

            val config = mutableMapOf<String, Any>()

            data["enabled"]?.let { value ->
                config["enabled"] = value
            }

            data["hiddenIds"]?.let { value ->
                @Suppress("UNCHECKED_CAST")
                val ids = (value as? List<*>)?.mapNotNull { it as? String }?.toSet()
                if (ids != null) {
                    config["hiddenIds"] = ids
                }
            }

            data["labels"]?.let { value ->
                @Suppress("UNCHECKED_CAST")
                val labels = value as? Map<String, String>
                if (labels != null) {
                    config["labels"] = labels
                }
            }

            val count = ConfigManager.setAllConfig(context, config)
            LogUtil.i(MODULE_TAG, "Backup restored from $filePath, applied $count config items")
            true
        } catch (e: Exception) {
            LogUtil.e(MODULE_TAG, "Failed to restore backup: ${e.message}", e)
            false
        }
    }

    /**
     * 列出所有可用备份文件。
     *
     * 扫描备份目录中的所有 .json 备份文件，按修改时间降序排列（最新的在前）。
     * 仅列出符合预期命名模式的文件（前缀 + 时间戳 + 扩展名），过滤掉不相关的文件。
     *
     * @return 备份文件信息列表，按时间降序排列。每个元素包含 name（文件名）、path（绝对路径）、
     *         size（文件大小字节）、lastModified（最后修改时间戳）
     */
    fun listBackups(): List<BackupInfo> {
        return listBackupsInternal()
    }

    /**
     * 列出所有可用备份文件（带 Context 的便捷方法）。
     */
    fun listBackups(context: Context?): List<BackupInfo> {
        return listBackupsInternal()
    }

    private fun listBackupsInternal(): List<BackupInfo> {
        return try {
            val backupDir = getBackupDir()
            if (!backupDir.exists() || !backupDir.isDirectory) {
                return emptyList()
            }

            // 仅列出符合预期命名格式的文件，过滤掉潜在的不相关文件
            val files = backupDir.listFiles { file ->
                file.isFile && file.name.startsWith(BACKUP_FILE_PREFIX) && file.name.endsWith(BACKUP_FILE_EXT)
            }

            if (files == null) return emptyList()

            files.sortedByDescending { it.lastModified() }
                .map { file ->
                    BackupInfo(
                        name = file.name,
                        path = file.absolutePath,
                        size = file.length(),
                        lastModified = file.lastModified()
                    )
                }
        } catch (e: Exception) {
            LogUtil.e(MODULE_TAG, "Failed to list backups: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * 删除旧备份，只保留最近 N 个。
     *
     * 列出所有备份文件并按时间降序排列，删除前 [maxKeep] 个之外的所有旧备份。
     * 如果 [maxKeep] 为 0 或负数，则删除所有备份。
     *
     * @param maxKeep 最多保留的备份数量
     * @return 被删除的备份文件数量
     */
    fun deleteOldBackups(maxKeep: Int): Int {
        if (maxKeep < 0) return 0

        return try {
            val backupDir = getBackupDir()
            if (!backupDir.exists() || !backupDir.isDirectory) {
                return 0
            }

            val files = backupDir.listFiles { file ->
                file.isFile && file.name.startsWith(BACKUP_FILE_PREFIX) && file.name.endsWith(BACKUP_FILE_EXT)
            }

            if (files == null || files.size <= maxKeep) {
                return 0
            }

            val sortedFiles = files.sortedByDescending { it.lastModified() }
            val toDelete = sortedFiles.drop(maxKeep)
            var deletedCount = 0

            for (file in toDelete) {
                try {
                    if (file.delete()) {
                        deletedCount++
                        LogUtil.d(MODULE_TAG, "Deleted old backup: ${file.name}")
                    }
                } catch (e: Exception) {
                    LogUtil.w(MODULE_TAG, "Failed to delete backup ${file.name}: ${e.message}")
                }
            }

            LogUtil.i(MODULE_TAG, "Deleted $deletedCount old backups, kept $maxKeep")
            deletedCount
        } catch (e: Exception) {
            LogUtil.e(MODULE_TAG, "Failed to delete old backups: ${e.message}", e)
            0
        }
    }

    /**
     * 删除指定的备份文件。
     */
    fun deleteBackup(context: Context?, path: String): Boolean {
        return try {
            val backupDir = getBackupDir()
            if (!SecurityUtils.isValidPath(path, backupDir)) {
                LogUtil.w(MODULE_TAG, "Path traversal attempt blocked: $path")
                return false
            }
            val file = File(path)
            if (file.exists()) {
                file.delete()
            } else {
                false
            }
        } catch (e: Exception) {
            LogUtil.e(MODULE_TAG, "Failed to delete backup: ${e.message}", e)
            false
        }
    }

    /**
     * 自动备份：如果距离上次备份超过 24 小时，则自动创建备份。
     *
     * 上次备份时间存储在 SharedPreferences 中，键名为 "last_auto_backup_time"。
     * 自动备份间隔为 24 小时（AUTO_BACKUP_INTERVAL_MS）。
     *
     * 线程安全：SharedPreferences 的 edit().apply() 是异步写入，
     * 但 last_auto_backup_time 的读取-更新存在窗口期。
     * 对于自动备份场景，精确的间隔控制不是关键需求，这种竞态条件是可接受的。
     *
     * @param context Android Context，用于读取配置和存储上次备份时间
     * @return 备份文件的绝对路径，如果不需要备份或创建失败则返回 null
     */
    fun autoBackupIfNeeded(context: Context): String? {
        return try {
            val sp = context.getSharedPreferences("wx_mirage_config", Context.MODE_PRIVATE)
            val lastBackupTime = sp.getLong("last_auto_backup_time", 0L)
            val now = System.currentTimeMillis()

            if (now - lastBackupTime < AUTO_BACKUP_INTERVAL_MS) {
                LogUtil.d(MODULE_TAG, "Auto-backup not needed, last backup was ${(now - lastBackupTime) / 3600000} hours ago")
                return null
            }

            val backupPath = createBackup(context)
            if (backupPath != null) {
                sp.edit().putLong("last_auto_backup_time", now).apply()
                LogUtil.i(MODULE_TAG, "Auto-backup created: $backupPath")
            }
            backupPath
        } catch (e: Exception) {
            LogUtil.e(MODULE_TAG, "Auto-backup failed: ${e.message}", e)
            null
        }
    }

    /**
     * 获取上次自动备份的时间戳。
     *
     * @param context Android Context
     * @return 上次自动备份的时间戳（毫秒），从未备份过返回 0
     */
    fun lastBackupTime(context: Context): Long {
        return context.getSharedPreferences("wx_mirage_config", Context.MODE_PRIVATE)
            .getLong("last_auto_backup_time", 0L)
    }

    /**
     * 格式化备份文件大小为人类可读的字符串。
     *
     * @param bytes 文件大小（字节）
     * @return 格式化后的大小字符串，如 "1.5 KB", "2.3 MB"
     */
    fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes.toDouble() / 1024)
            else -> "%.1f MB".format(bytes.toDouble() / (1024 * 1024))
        }
    }

    /**
     * 格式化时间戳为可读的日期时间字符串。
     *
     * @param timestamp 时间戳（毫秒）
     * @return 格式化后的日期时间字符串，如 "2024-01-15 14:30:00"
     */
    fun formatTimestamp(timestamp: Long): String {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            sdf.format(Date(timestamp))
        } catch (e: Exception) {
            timestamp.toString()
        }
    }

    /**
     * 备份文件信息数据类。
     *
     * @param name 文件名
     * @param path 绝对路径
     * @param size 文件大小（字节）
     * @param lastModified 最后修改时间戳（毫秒）
     */
    data class BackupInfo(
        val name: String,
        val path: String,
        val size: Long,
        val lastModified: Long
    )
}