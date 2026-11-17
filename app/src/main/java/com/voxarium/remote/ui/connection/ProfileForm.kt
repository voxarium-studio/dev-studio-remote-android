package com.voxarium.remote.ui.connection

import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.voxarium.remote.R
import com.voxarium.remote.data.transport.RemoteEndpoint

enum class ProfileFormError { LABEL, SERVER, KEY }

fun validateProfileForm(label: String, server: String): ProfileFormError? {
    if (label.trim().isEmpty() || label.length > 256 || label.any { it.code < 32 }) return ProfileFormError.LABEL
    return try { RemoteEndpoint.create(server); null } catch (_: IllegalArgumentException) {
        ProfileFormError.SERVER
    }
}

/** Only a native view handle, not key text, is remembered. */
private class PasswordInput {
    var view: EditText? = null
    fun valid(): Boolean = view?.text?.let { value ->
        value.isNotEmpty() && value.length <= 8192 && value.all { it.code in 33..126 }
    } == true
    fun consume(): String {
        val value = view?.text?.toString().orEmpty()
        view?.text?.clear()
        return value
    }
    fun clear() { view?.text?.clear() }
}

@Composable
fun ProfileForm(
    busy: Boolean, onCancel: () -> Unit, onSave: (String, String, String) -> Unit,
    error: String? = null, initialName: String = "", initialServer: String = "", keyOnly: Boolean = false,
) {
    var label by rememberSaveable(initialName) { mutableStateOf(initialName) }
    var server by rememberSaveable(initialServer) { mutableStateOf(initialServer) }
    var invalid by remember { mutableStateOf<ProfileFormError?>(null) }
    val password = remember { PasswordInput() }
    val keyboard = LocalSoftwareKeyboardController.current
    val keyLabel = stringResource(R.string.profile_key)
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    DisposableEffect(password) { onDispose { password.clear(); password.view = null } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(if (keyOnly) R.string.server_replace_key else R.string.profile_add), style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() })
        OutlinedTextField(label, { label = it }, Modifier.fillMaxWidth(), enabled = !busy && !keyOnly,
            label = { Text(stringResource(R.string.profile_label)) }, singleLine = true,
            isError = invalid == ProfileFormError.LABEL)
        OutlinedTextField(server, { server = it }, Modifier.fillMaxWidth(), enabled = !busy && !keyOnly,
            label = { Text(stringResource(R.string.profile_server)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Uri),
            isError = invalid == ProfileFormError.SERVER)
        Text(keyLabel, style = MaterialTheme.typography.labelLarge)
        AndroidView(factory = { context ->
            EditText(context).apply {
                id = View.NO_ID
                isSaveEnabled = false
                isSaveFromParentEnabled = false
                if (android.os.Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setSingleLine(true)
                // setSingleLine may install SingleLineTransformationMethod; restore masking AFTER it.
                transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
                imeOptions = EditorInfo.IME_ACTION_DONE
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                }
                hint = keyLabel
                contentDescription = keyLabel
                setTextColor(textColor)
                setHintTextColor(hintColor)
                password.view = this
            }
        }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), update = {
            it.isEnabled = !busy
            it.setTextColor(textColor)
            it.setHintTextColor(hintColor)
        }, onRelease = { it.text.clear(); password.view = null })
        Text(stringResource(R.string.key_privacy), style = MaterialTheme.typography.bodySmall)
        invalid?.let {
            Text(stringResource(when (it) {
                ProfileFormError.LABEL -> R.string.validation_label
                ProfileFormError.SERVER -> R.string.validation_endpoint
                ProfileFormError.KEY -> R.string.validation_key
            }), color = MaterialTheme.colorScheme.error)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = {
            invalid = validateProfileForm(label, server) ?: if (!password.valid()) ProfileFormError.KEY else null
            if (invalid == null) {
                keyboard?.hide()
                onSave(label, server, password.consume())
            }
        }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(if (keyOnly) R.string.server_save_key else R.string.profile_save)) }
        OutlinedButton(onClick = { password.clear(); onCancel() }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.cancel))
        }
    }
}
