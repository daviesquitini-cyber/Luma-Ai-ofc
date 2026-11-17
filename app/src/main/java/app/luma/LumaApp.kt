package app.luma

import android.app.Application
import androidx.room.Room
import app.luma.data.AppDatabase
import app.luma.data.ChatRepository
import app.luma.data.SettingsStore

class LumaApp : Application() {
    val db by lazy { Room.databaseBuilder(this, AppDatabase::class.java, "luma.db").build() }
    val repo by lazy { ChatRepository(db) }
    val settings by lazy { SettingsStore(this) }
}
