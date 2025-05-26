package com.ivip.brainstormia

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Define o nome do DataStore para as configurações do app
private val Context.appSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

class AppSettingsPreferences(private val context: Context) {

    // Chave para a preferência de backup automático
    companion object {
        val AUTO_BACKUP_ENABLED = booleanPreferencesKey("auto_backup_enabled")
    }

    // Fluxo para observar se o backup automático está habilitado
    val isAutoBackupEnabled: Flow<Boolean> = context.appSettingsDataStore.data
        .map { preferences ->
            preferences[AUTO_BACKUP_ENABLED] ?: false // Padrão para desabilitado
        }

    // Função para definir o estado do backup automático
    suspend fun setAutoBackupEnabled(enabled: Boolean) {
        context.appSettingsDataStore.edit { settings ->
            settings[AUTO_BACKUP_ENABLED] = enabled
        }
    }
}