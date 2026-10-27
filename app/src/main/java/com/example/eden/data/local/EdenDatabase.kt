package com.example.eden.data.local


import com.example.eden.data.repository.DiaryRepository
import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.eden.core.storage.EdenStorage

/** Eden 本地数据库：聊天记录 + 长期记忆 + 日记 + 专注任务/记录。 */
@Database(
    entities = [
        ChatMessageEntity::class,
        ChatMemoryEntity::class,
        DiaryEntity::class,
        FocusTaskEntity::class,
        FocusSessionEntity::class,
        LongTermMemoryEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class EdenDatabase : RoomDatabase() {

    abstract fun chatMessageDao(): ChatMessageDao

    abstract fun chatMemoryDao(): ChatMemoryDao

    abstract fun diaryDao(): DiaryDao

    abstract fun focusDao(): FocusDao

    abstract fun longTermMemoryDao(): LongTermMemoryDao

    companion object {
        /**
         * v1 → v2：新增长期记忆表。
         * 必须显式迁移，不能用破坏性迁移，否则老用户升级后聊天记录会被清空。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `chat_memory` (
                        `characterId` TEXT NOT NULL,
                        `summary` TEXT NOT NULL,
                        `facts` TEXT NOT NULL,
                        `summarizedUntilId` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`characterId`)
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * v2 → v3：新增日记表。
         * 日记从 SharedPreferences 迁到 Room，支持按日期增量查询；旧数据在
         * [DiaryRepository] 首次访问时一次性导入，不在此处处理（迁移只负责建表）。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `diary` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `date` TEXT NOT NULL,
                        `mood` TEXT NOT NULL,
                        `moodEmoji` TEXT NOT NULL,
                        `tags` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `summary` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_diary_date` ON `diary` (`date`)")
            }
        }

        /**
         * v3 → v4：新增专注任务表与专注记录表（番茄钟 / 专注陪伴功能）。
         * 同样只负责建表，老用户既有数据不受影响。
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `focus_task` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL,
                        `targetPomodoros` INTEGER NOT NULL,
                        `donePomodoros` INTEGER NOT NULL,
                        `done` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `focus_session` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `taskId` INTEGER NOT NULL,
                        `taskTitle` TEXT NOT NULL,
                        `durationSeconds` INTEGER NOT NULL,
                        `date` TEXT NOT NULL,
                        `endedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_focus_session_date` ON `focus_session` (`date`)")
            }
        }

        /**
         * v4 → v5：新增结构化长期记忆表。
         * 与 chat_memory（滚动摘要）分表存放，只建表，不动既有数据。
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `long_term_memory` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `characterId` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `subject` TEXT NOT NULL,
                        `key` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `sourceMessageId` INTEGER NOT NULL,
                        `importance` INTEGER NOT NULL,
                        `mentionCount` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `lastMentionedAt` INTEGER NOT NULL,
                        `supersededBy` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_long_term_memory_characterId` ON `long_term_memory` (`characterId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_long_term_memory_characterId_key` ON `long_term_memory` (`characterId`, `key`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_long_term_memory_sourceMessageId` ON `long_term_memory` (`sourceMessageId`)")
            }
        }

        @Volatile
        private var INSTANCE: EdenDatabase? = null

        /** 当前 [INSTANCE] 对应的库文件名，与其一起维护，用于检测账号分区切换。 */
        @Volatile
        private var INSTANCE_NAME: String? = null

        /**
         * 全局单例。库文件名由 [EdenStorage.dbName] 按当前账号分区决定，因此切换账号后
         * 首次访问会拿到不同的名字：此时关闭旧库连接并重建，指向新账号的库。
         */
        fun getInstance(context: Context): EdenDatabase {
            val name = EdenStorage.dbName()
            INSTANCE?.let { if (INSTANCE_NAME == name) return it }
            return synchronized(this) {
                INSTANCE?.let { if (INSTANCE_NAME == name) return it }
                INSTANCE?.close()
                INSTANCE = null
                Room.databaseBuilder(context.applicationContext, EdenDatabase::class.java, name)
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build()
                    .also { INSTANCE = it; INSTANCE_NAME = name }
            }
        }
    }
}
