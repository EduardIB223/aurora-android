package io.nekohasekai.sfa.update

import android.content.Intent
import android.os.Build
import androidx.compose.runtime.mutableStateOf
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.database.Settings
import java.io.File

object UpdateState {
    val hasUpdate = mutableStateOf(false)
    val updateInfo = mutableStateOf<UpdateInfo?>(null)
    val isChecking = mutableStateOf(false)

    val isDownloading = mutableStateOf(false)
    val downloadProgress = mutableStateOf<Float?>(null)
    val downloadError = mutableStateOf<String?>(null)

    val cachedApkFile = mutableStateOf<File?>(null)

    sealed class InstallStatus {
        data object Idle : InstallStatus()
        data object Installing : InstallStatus()
        data object Success : InstallStatus()
        data class Failed(val error: String) : InstallStatus()
    }

    val installStatus = mutableStateOf<InstallStatus>(InstallStatus.Idle)

    /** The system's "install this update?" screen, opened from the foreground activity. */
    val pendingConfirmIntent = mutableStateOf<Intent?>(null)

    fun setUpdate(info: UpdateInfo?) {
        updateInfo.value = info
        hasUpdate.value = info != null
        saveToCache(info)
        // A file downloaded for an older offer must not be installed for this one.
        cachedApkFile.value?.let { if (info == null || !isApkFor(it, info.versionCode)) dropApk() }
    }

    /** versionCode inside an APK file, or null if it isn't a readable APK of this app. */
    fun apkVersionCode(file: File): Long? {
        if (!file.exists() || file.length() == 0L) return null
        val pm = Application.application.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(file.absolutePath, android.content.pm.PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(file.absolutePath, 0)
        } ?: return null
        if (info.packageName != Application.application.packageName) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    fun isApkFor(file: File, versionCode: Int?): Boolean = versionCode != null && apkVersionCode(file) == versionCode.toLong()

    fun dropApk() {
        cachedApkFile.value?.delete()
        cachedApkFile.value = null
        Settings.cachedApkPath = ""
    }

    fun setInstallStatus(status: InstallStatus) {
        installStatus.value = status
    }

    fun clear() {
        hasUpdate.value = false
        updateInfo.value = null
        isDownloading.value = false
        downloadProgress.value = null
        downloadError.value = null
        installStatus.value = InstallStatus.Idle
        cachedApkFile.value = null
        clearCache()
    }

    fun resetDownload() {
        isDownloading.value = false
        downloadProgress.value = null
        downloadError.value = null
    }

    fun loadFromCache() {
        val json = Settings.cachedUpdateInfo
        if (json.isBlank()) return

        val info = UpdateInfo.fromJson(json) ?: return
        if (info.versionCode <= BuildConfig.VERSION_CODE) {
            clearCache()
            return
        }

        updateInfo.value = info
        hasUpdate.value = true

        val apkPath = Settings.cachedApkPath
        if (apkPath.isNotBlank()) {
            val apkFile = File(apkPath)
            if (isApkFor(apkFile, info.versionCode)) {
                cachedApkFile.value = apkFile
            } else {
                apkFile.delete()
                Settings.cachedApkPath = ""
            }
        }
    }

    private fun saveToCache(info: UpdateInfo?) {
        Settings.cachedUpdateInfo = info?.toJson() ?: ""
    }

    fun saveApkPath(file: File) {
        Settings.cachedApkPath = file.absolutePath
        cachedApkFile.value = file
    }

    private fun clearCache() {
        Settings.cachedUpdateInfo = ""
        Settings.cachedApkPath = ""
        Settings.lastShownUpdateVersion = 0
    }
}
