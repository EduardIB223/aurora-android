package io.nekohasekai.sfa.compose.screen.configuration

import io.nekohasekai.sfa.utils.RemoteProfileLoader
import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.bg.UpdateProfileWork
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.TypedProfile
import io.nekohasekai.sfa.utils.HTTPClient
import io.nekohasekai.sfa.utils.ProxyLinkParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.Date

data class NewProfileUiState(
    val name: String = "",
    // A pasted share link or subscription URL. When set it decides everything
    // and the type/source choices below are not used.
    val linkText: String = "",
    val linkError: String? = null,
    val profileType: ProfileType = ProfileType.Local,
    val profileSource: ProfileSource = ProfileSource.CreateNew,
    // Remote profile fields
    val remoteUrl: String = "",
    val autoUpdate: Boolean = true,
    val autoUpdateInterval: Int = 60,
    // File import
    val importUri: Uri? = null,
    val importFileName: String? = null,
    // QRS import
    val qrsData: ByteArray? = null,
    // State
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val isSuccess: Boolean = false,
    val createdProfile: Profile? = null,
    // Field errors
    val nameError: String? = null,
    val remoteUrlError: String? = null,
    val importError: String? = null,
)

enum class ProfileType {
    Local,
    Remote,
}

enum class ProfileSource {
    CreateNew,
    Import,
}

class NewProfileViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(NewProfileUiState())
    val uiState: StateFlow<NewProfileUiState> = _uiState.asStateFlow()

    fun initializeFromQRImport(name: String?, url: String?) {
        if (name != null && url != null) {
            _uiState.update {
                it.copy(
                    name = name,
                    profileType = ProfileType.Remote,
                    remoteUrl = url,
                    // A profile served by a PC on the LAN is gone after a few
                    // minutes; auto-updating it would only produce errors.
                    autoUpdate = !RemoteProfileLoader.isLanUrl(url),
                )
            }
        }
    }

    fun initializeFromQRSImport(name: String?, qrsData: ByteArray) {
        _uiState.update {
            it.copy(
                name = name ?: "",
                profileType = ProfileType.Local,
                profileSource = ProfileSource.Import,
                qrsData = qrsData,
            )
        }
    }

    fun updateName(name: String) {
        _uiState.update {
            it.copy(
                name = name,
                nameError = if (name.isNotBlank()) null else it.nameError,
            )
        }
    }

    fun updateProfileType(type: ProfileType) {
        _uiState.update { it.copy(profileType = type) }
    }

    fun updateProfileSource(source: ProfileSource) {
        _uiState.update {
            it.copy(
                profileSource = source,
                importError = null, // Clear import error when changing source
            )
        }
    }

    fun updateRemoteUrl(url: String) {
        // A pasted share link carries its own label after "#" — use it so the
        // name field doesn't have to be filled in by hand.
        val suggestedName =
            if (ProxyLinkParser.isProxyLink(url)) ProxyLinkParser.parse(url)?.name else null

        _uiState.update {
            it.copy(
                remoteUrl = url,
                remoteUrlError = if (url.isNotBlank()) null else it.remoteUrlError,
                name = if (it.name.isBlank() && suggestedName != null) suggestedName else it.name,
                nameError = if (suggestedName != null) null else it.nameError,
            )
        }
    }

    fun updateLinkText(text: String) {
        val suggestedName = suggestNameForLink(text)
        _uiState.update {
            it.copy(
                linkText = text,
                linkError = null,
                name = if (it.name.isBlank() && suggestedName != null) suggestedName else it.name,
                nameError = if (suggestedName != null) null else it.nameError,
            )
        }
    }

    /** A name for the profile taken from the link itself, if it carries one. */
    private fun suggestNameForLink(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("sing-box://import-remote-profile", true)) {
            return runCatching { Libbox.parseRemoteProfileImportLink(trimmed).name }.getOrNull()
                ?.takeIf { it.isNotBlank() }
        }
        if (ProxyLinkParser.isProxyLink(trimmed)) return ProxyLinkParser.parse(trimmed)?.name
        return ProxyLinkParser.subscriptionUrl(trimmed)
            ?.substringAfter("://")?.substringBefore('/')?.takeIf { it.isNotBlank() }
    }

    fun updateAutoUpdate(enabled: Boolean) {
        _uiState.update { it.copy(autoUpdate = enabled) }
    }

    fun updateAutoUpdateInterval(interval: String) {
        val intValue = interval.toIntOrNull() ?: 60
        _uiState.update { it.copy(autoUpdateInterval = intValue.coerceAtLeast(15)) }
    }

    fun setImportUri(uri: Uri, fileName: String?) {
        _uiState.update {
            it.copy(
                importUri = uri,
                importFileName = fileName,
                importError = null, // Clear error when file is selected
                name =
                if (it.name.isEmpty()) {
                    fileName?.substringBeforeLast(".") ?: "Imported Profile"
                } else {
                    it.name
                },
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun validateAndCreateProfile(): Boolean {
        val state = _uiState.value
        val context = getApplication<Application>()

        // Clear previous errors
        _uiState.update {
            it.copy(
                nameError = null,
                remoteUrlError = null,
                importError = null,
            )
        }

        var hasError = false

        // A pasted link carries everything needed; the name falls back to the
        // one from the link.
        if (state.linkText.isNotBlank()) {
            val text = state.linkText.trim()
            val recognised =
                text.startsWith("sing-box://import-remote-profile", true) ||
                    ProxyLinkParser.isProxyLink(text) ||
                    ProxyLinkParser.subscriptionUrl(text) != null
            if (!recognised) {
                _uiState.update { it.copy(linkError = context.getString(R.string.error_unsupported_share_link)) }
                return false
            }
            if (state.name.isBlank()) {
                _uiState.update { it.copy(name = suggestNameForLink(text) ?: "Aurora") }
            }
            createProfile()
            return true
        }

        // Validate name
        if (state.name.isBlank()) {
            _uiState.update { it.copy(nameError = context.getString(R.string.profile_input_required)) }
            hasError = true
        }

        // Validate based on profile type
        when (state.profileType) {
            ProfileType.Local -> {
                if (state.profileSource == ProfileSource.Import && state.importUri == null && state.qrsData == null) {
                    _uiState.update { it.copy(importError = context.getString(R.string.profile_input_required)) }
                    hasError = true
                }
            }
            ProfileType.Remote -> {
                if (state.remoteUrl.isBlank()) {
                    _uiState.update { it.copy(remoteUrlError = context.getString(R.string.profile_input_required)) }
                    hasError = true
                }
            }
        }

        if (hasError) {
            return false
        }

        // If validation passes, create the profile
        createProfile()
        return true
    }

    private fun createProfile() {
        viewModelScope.launch {
            val state = _uiState.value
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }

            try {
                val profile =
                    withContext(Dispatchers.IO) {
                        val current = _uiState.value
                        when {
                            current.linkText.isNotBlank() -> createProfileFromLink(current)
                            current.profileType == ProfileType.Local -> createLocalProfile(current)
                            else -> createRemoteProfile(current)
                        }
                    }

                _uiState.update {
                    it.copy(
                        isSaving = false,
                        isSuccess = true,
                        createdProfile = profile,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = e.message ?: "Unknown error",
                    )
                }
            }
        }
    }

    /**
     * Creates a profile from a pasted link: a single share link (or several)
     * becomes a local profile with every server; a subscription becomes a
     * remote profile that keeps updating.
     */
    private suspend fun createProfileFromLink(state: NewProfileUiState): Profile {
        val context = getApplication<Application>()
        val text = state.linkText.trim()

        if (text.startsWith("sing-box://import-remote-profile", true)) {
            val info = Libbox.parseRemoteProfileImportLink(text)
            return createRemoteProfile(
                state.copy(remoteUrl = info.url, autoUpdate = !RemoteProfileLoader.isLanUrl(info.url)),
            )
        }
        if (ProxyLinkParser.isProxyLink(text)) {
            val parsed =
                ProxyLinkParser.parse(text)
                    ?: throw IllegalArgumentException(context.getString(R.string.error_unsupported_share_link))
            return createProfileFromConfig(state.name.ifBlank { parsed.name }, parsed.config)
        }
        val url =
            ProxyLinkParser.subscriptionUrl(text)
                ?: throw IllegalArgumentException(context.getString(R.string.error_unsupported_share_link))
        return createRemoteProfile(state.copy(remoteUrl = url))
    }

    private suspend fun createLocalProfile(state: NewProfileUiState): Profile {
        val context = getApplication<Application>()
        val typedProfile =
            TypedProfile().apply {
                type = TypedProfile.Type.Local
            }

        val profile =
            Profile(name = state.name, typed = typedProfile).apply {
                userOrder = ProfileManager.nextOrder()
            }

        val fileID = ProfileManager.nextFileID()
        val configDirectory = File(context.filesDir, "configs").also { it.mkdirs() }
        val configFile = File(configDirectory, "$fileID.json")
        typedProfile.path = configFile.path

        // Get config content
        val configContent =
            when (state.profileSource) {
                ProfileSource.CreateNew -> "{}"
                ProfileSource.Import -> {
                    if (state.qrsData != null) {
                        val content = Libbox.decodeProfileContent(state.qrsData)
                        content.config
                    } else {
                        state.importUri?.let { uri ->
                            val sourceURL = uri.toString()
                            when {
                                sourceURL.startsWith("content://") -> {
                                    val inputStream = context.contentResolver.openInputStream(uri) as InputStream
                                    inputStream.use { it.bufferedReader().readText() }
                                }
                                sourceURL.startsWith("file://") -> {
                                    File(Uri.parse(sourceURL).path!!).readText()
                                }
                                sourceURL.startsWith("http://") || sourceURL.startsWith("https://") -> {
                                    RemoteProfileLoader.fetch(sourceURL).content
                                }
                                else -> throw Exception("Unsupported source: $sourceURL")
                            }
                        } ?: "{}"
                    }
                }
            }

        // Validate config
        Libbox.checkConfig(configContent)
        configFile.writeText(configContent)

        // Create profile in database and select it
        ProfileManager.create(profile, andSelect = true)

        return profile
    }

    private suspend fun createRemoteProfile(state: NewProfileUiState): Profile {
        val context = getApplication<Application>()

        // A share link pasted into the URL field can't be fetched over HTTP —
        // expand it into a full configuration and store it as a local profile.
        if (ProxyLinkParser.isProxyLink(state.remoteUrl)) {
            val parsed =
                ProxyLinkParser.parse(state.remoteUrl)
                    ?: throw IllegalArgumentException(
                        context.getString(R.string.error_unsupported_share_link),
                    )
            return createProfileFromConfig(state.name.ifBlank { parsed.name }, parsed.config)
        }

        val typedProfile =
            TypedProfile().apply {
                type = TypedProfile.Type.Remote
                remoteURL = state.remoteUrl
                autoUpdate = state.autoUpdate
                autoUpdateInterval = state.autoUpdateInterval
                lastUpdated = Date()
            }

        val profile =
            Profile(name = state.name, typed = typedProfile).apply {
                userOrder = ProfileManager.nextOrder()
            }

        val fileID = ProfileManager.nextFileID()
        val configDirectory = File(context.filesDir, "configs").also { it.mkdirs() }
        val configFile = File(configDirectory, "$fileID.json")
        typedProfile.path = configFile.path

        // Fetch initial config - this MUST succeed for remote profiles
        val content = RemoteProfileLoader.fetch(state.remoteUrl).content
        Libbox.checkConfig(content)
        val configContent = content

        configFile.writeText(configContent)

        // Create profile in database and select it
        ProfileManager.create(profile, andSelect = true)

        // Reconfigure updater if auto-update is enabled
        if (state.autoUpdate) {
            UpdateProfileWork.reconfigureUpdater()
        }

        return profile
    }

    private suspend fun createProfileFromConfig(
        name: String,
        config: String,
    ): Profile {
        val context = getApplication<Application>()
        Libbox.checkConfig(config)

        val typedProfile = TypedProfile().apply { type = TypedProfile.Type.Local }
        val profile =
            Profile(name = name, typed = typedProfile).apply {
                userOrder = ProfileManager.nextOrder()
            }

        val fileID = ProfileManager.nextFileID()
        val configDirectory = File(context.filesDir, "configs").also { it.mkdirs() }
        val configFile = File(configDirectory, "$fileID.json")
        configFile.writeText(config)
        typedProfile.path = configFile.path

        ProfileManager.create(profile, andSelect = true)
        return profile
    }
}
