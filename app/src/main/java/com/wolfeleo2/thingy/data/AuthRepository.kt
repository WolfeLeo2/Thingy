package com.wolfeleo2.thingy.data

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class AuthRepository(private val auth: FirebaseAuth = FirebaseAuth.getInstance()) {
    val currentUser: FirebaseUser? get() = auth.currentUser

    val authState: Flow<FirebaseUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    /** Google sign-in via Credential Manager, exchanged for a Firebase credential. */
    suspend fun signInWithGoogle(context: Context, serverClientId: String) {
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(serverClientId)          // Web client id, not the Android one
            .setFilterByAuthorizedAccounts(false)       // allow first-time sign-in
            .setAutoSelectEnabled(true)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        
        try {
            val response = CredentialManager.create(context).getCredential(context, request)
            val cred = response.credential
            check(cred is CustomCredential && cred.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                "Unexpected credential type: ${cred.type}"
            }
            val idToken = GoogleIdTokenCredential.createFrom(cred.data).idToken
            auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
        } catch (e: GetCredentialCancellationException) {
            // User canceled the dialog, don't throw an error to the UI
            return
        } catch (e: GetCredentialException) {
            throw Exception(e.message ?: "Credential request failed")
        }
    }


    fun signOut() = auth.signOut()
}
