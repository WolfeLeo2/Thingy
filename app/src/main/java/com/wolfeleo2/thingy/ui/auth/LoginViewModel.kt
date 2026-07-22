package com.wolfeleo2.thingy.ui.auth

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wolfeleo2.thingy.data.AuthRepository
import kotlinx.coroutines.launch

class LoginViewModel(private val auth: AuthRepository) : ViewModel() {
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun signInWithGoogle(context: Context, serverClientId: String) {
        error = null
        busy = true
        viewModelScope.launch {
            try {
                auth.signInWithGoogle(context, serverClientId)
            } catch (e: Exception) {
                // We'll handle generic errors here. 
                // Specific "cancel" errors can be ignored or handled specially in the repo.
                error = e.message ?: "Sign-in failed"
            } finally {
                busy = false
            }
        }
    }
    
    fun clearError() {
        error = null
    }
}
