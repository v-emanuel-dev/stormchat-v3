package com.ivip.brainstormia.viewmodels

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ivip.brainstormia.api.ApiService
import com.ivip.brainstormia.api.AllModelsUsage
import com.ivip.brainstormia.api.ModelUsageInfo
import com.ivip.brainstormia.auth.TokenManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class UsageState {
    object Loading : UsageState()
    data class Success(val data: AllModelsUsage) : UsageState()
    data class Error(val message: String) : UsageState()
}

class UsageLimitsViewModel(application: Application) : AndroidViewModel(application) {

    private val apiService = ApiService(TokenManager(application.applicationContext))

    private val _usageState = MutableStateFlow<UsageState>(UsageState.Loading)
    val usageState: StateFlow<UsageState> = _usageState.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val TAG = "UsageLimitsViewModel"

    init {
        loadUsageData()
    }

    fun loadUsageData() {
        _usageState.value = UsageState.Loading
        _errorMessage.value = null

        viewModelScope.launch {
            try {
                Log.d(TAG, "Carregando dados de uso...")

                val result = apiService.getAllModelsUsage()

                result.fold(
                    onSuccess = { usageData ->
                        Log.d(TAG, "Dados de uso carregados com sucesso: ${usageData.usage.size} modelos")
                        _usageState.value = UsageState.Success(usageData)
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Erro ao carregar dados de uso: ${error.message}")
                        _usageState.value = UsageState.Error(error.message ?: "Erro desconhecido")
                        _errorMessage.value = error.message
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Exceção ao carregar dados de uso", e)
                _usageState.value = UsageState.Error("Erro ao carregar dados: ${e.message}")
                _errorMessage.value = e.message
            }
        }
    }

    fun refreshUsageData() {
        Log.d(TAG, "Atualizando dados de uso...")
        loadUsageData()
    }

    fun clearError() {
        _errorMessage.value = null
    }
}