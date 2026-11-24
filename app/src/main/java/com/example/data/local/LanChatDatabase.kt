package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class LanChatTypeConverters {
    @TypeConverter fun fromTrustStatus(value: TrustStatus): String = value.name
    @TypeConverter fun toTrustStatus(value: String): TrustStatus = TrustStatus.valueOf(value)

    @TypeConverter fun fromConversationType(value: ConversationType): String = value.name
    @TypeConverter fun toConversationType(value: String): ConversationType = ConversationType.valueOf(value)

    @TypeConverter fun fromMessageType(value: MessageType): String = value.name
    @TypeConverter fun toMessageType(value: String): MessageType = MessageType.valueOf(value)

    @TypeConverter fun fromDeliveryStatus(value: DeliveryStatus): String = value.name
    @TypeConverter fun toDeliveryStatus(value: String): DeliveryStatus = DeliveryStatus.valueOf(value)

    @TypeConverter fun fromTransferDirection(value: TransferDirection): String = value.name
    @TypeConverter fun toTransferDirection(value: String): TransferDirection = TransferDirection.valueOf(value)

    @TypeConverter fun fromTransferStatus(value: TransferStatus): String = value.name
    @TypeConverter fun toTransferStatus(value: String): TransferStatus = TransferStatus.valueOf(value)
}

@Database(
    entities = [
        PeerEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        MediaTransferEntity::class,
        DtnRelayPacketEntity::class,
        SeenPacketEntity::class
    ],
    version = 2,
    exportSchema = false
)
@TypeConverters(LanChatTypeConverters::class)
abstract class LanChatDatabase : RoomDatabase() {
    abstract fun peerDao(): PeerDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun mediaTransferDao(): MediaTransferDao
    abstract fun dtnRelayDao(): DtnRelayDao
    abstract fun seenPacketDao(): SeenPacketDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE peers ADD COLUMN latitude REAL")
                db.execSQL("ALTER TABLE peers ADD COLUMN longitude REAL")
                db.execSQL("ALTER TABLE peers ADD COLUMN locationUpdatedAt INTEGER")
                db.execSQL("ALTER TABLE messages ADD COLUMN localOrderTs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE messages SET localOrderTs = timestamp")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_messages_conversationId_localOrderTs " +
                        "ON messages(conversationId, localOrderTs)"
                )
            }
        }

        fun build(context: Context): LanChatDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                LanChatDatabase::class.java,
                "lanchat_mesh.db"
            )
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
