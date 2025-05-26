package com.ivip.brainstormia

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.ivip.brainstormia.services.BackupWorker
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val appSettingsPreferences = AppSettingsPreferences(application.applicationContext)
    private val workManager = WorkManager.getInstance(application.applicationContext)
    private val TAG = "SettingsViewModel"

    val isAutoBackupEnabled: StateFlow<Boolean> = appSettingsPreferences.isAutoBackupEnabled
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    init {
        viewModelScope.launch {
            val autoBackupCurrentlyEnabled = isAutoBackupEnabled.first()
            if (autoBackupCurrentlyEnabled) {
                Log.d(TAG, "Backup automático periódico está habilitado. Verificando agendamento existente.")
                schedulePeriodicAutoBackup()
            } else {
                Log.d(TAG, "Backup automático periódico está desabilitado. Cancelando qualquer agendamento.")
                cancelPeriodicAutoBackup()
            }
        }
    }

    fun setAutoBackupEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appSettingsPreferences.setAutoBackupEnabled(enabled)
            if (enabled) {
                Log.i(TAG, "Usuário habilitou o backup automático periódico. Agendando...")
                schedulePeriodicAutoBackup()
                // CHAMADA PARA TESTE IMEDIATO:
                scheduleOneTimeBackupForTest()
                Log.i(TAG, "Também agendando um backup de TESTE ÚNICO para execução imediata (se as restrições permitirem).")
            } else {
                Log.i(TAG, "Usuário desabilitou o backup automático periódico. Cancelando...")
                cancelPeriodicAutoBackup()
                // Opcionalmente, você pode querer cancelar também qualquer teste único pendente:
                // workManager.cancelAllWorkByTag(AUTO_BACKUP_ONETIME_TEST_TAG)
            }
        }
    }

    /**
     * Agenda um backup automático periódico (a cada 15 minutos, como configurado).
     */
    private fun schedulePeriodicAutoBackup() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val backupRequest = PeriodicWorkRequestBuilder<BackupWorker>(
            repeatInterval = 15,
            repeatIntervalTimeUnit = TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .addTag(AUTO_BACKUP_PERIODIC_WORK_TAG)
            .build()

        workManager.enqueueUniquePeriodicWork(
            AUTO_BACKUP_PERIODIC_WORK_TAG,
            ExistingPeriodicWorkPolicy.REPLACE,
            backupRequest
        )
        Log.i(TAG, "Backup automático periódico agendado para rodar a cada 15 minutos.")
    }

    /**
     * Cancela o backup automático periódico.
     */
    private fun cancelPeriodicAutoBackup() {
        workManager.cancelUniqueWork(AUTO_BACKUP_PERIODIC_WORK_TAG)
        Log.i(TAG, "Agendamento de backup automático periódico cancelado.")
    }

    /**
     * Agenda um backup único para fins de teste.
     * Este trabalho tentará executar assim que as restrições forem atendidas.
     */
    fun scheduleOneTimeBackupForTest() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val oneTimeBackupRequest = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(constraints)
            .addTag(AUTO_BACKUP_ONETIME_TEST_TAG)
            .build()

        // Para garantir que apenas um teste único seja enfileirado por vez,
        // podemos usar enqueueUniqueWork. Se um teste anterior ainda estiver na fila,
        // podemos decidir se o mantemos, substituímos ou ignoramos o novo.
        // ExistingWorkPolicy.REPLACE substituirá qualquer trabalho de teste único pendente.
        workManager.enqueueUniqueWork(
            AUTO_BACKUP_ONETIME_TEST_TAG, // Nome único para o trabalho de teste
            androidx.work.ExistingWorkPolicy.REPLACE, // Política para lidar com trabalho existente
            oneTimeBackupRequest
        )
        Log.i(TAG, "Backup automático de TESTE ÚNICO agendado (ou substituído se já existia um).")
    }

    companion object {
        const val AUTO_BACKUP_PERIODIC_WORK_TAG = "AutoBackupBrainstormiaPeriodic"
        const val AUTO_BACKUP_ONETIME_TEST_TAG = "AutoBackupBrainstormiaOneTimeTest"
    }
}
