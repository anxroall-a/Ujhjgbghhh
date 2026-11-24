package com.blackstrings

import android.content.Context
import java.io.File
import java.io.FileNotFoundException

object TorService {

    fun prepareTorBinary(context: Context): File {
        val nativeLibTor = File(context.applicationInfo.nativeLibraryDir, "libtor.so")
        if (nativeLibTor.exists()) {
            if (!nativeLibTor.canExecute()) {
                nativeLibTor.setExecutable(true, true)
            }
            return nativeLibTor
        }

        val targetFile = File(context.filesDir, "tor")
        if (targetFile.exists() && targetFile.canExecute()) {
            return targetFile
        }

        val assetCandidates = listOf("nativeLibs/libtor.so", "nativeLibs/tor", "libtor.so", "tor")
        for (assetPath in assetCandidates) {
            try {
                context.assets.open(assetPath).use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                targetFile.setExecutable(true, true)
                return targetFile
            } catch (_: Exception) {
                // Try next candidate
            }
        }

        throw FileNotFoundException("Tor binary (libtor.so) not found in nativeLibraryDir or assets")
    }
}
