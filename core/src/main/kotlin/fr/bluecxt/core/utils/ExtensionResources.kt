// Copyright bluecxt
// SPDX-License-Identifier: Apache-2.0
package fr.bluecxt.core.utils

import android.app.Application
import android.content.res.AssetManager
import android.content.res.Resources
import android.util.Log
import dalvik.system.BaseDexClassLoader
import fr.bluecxt.core.EXTENSION_RESOURCES_LOG
import java.util.concurrent.ConcurrentHashMap

object ExtensionResources {
    private val cache = ConcurrentHashMap<String, Resources>()

    fun getResources(app: Application, clazz: Class<*>): Resources? {
        val apkPath = getApkPath(app, clazz)
        if (apkPath == null) {
            Log.w(EXTENSION_RESOURCES_LOG, "Could not resolve APK path for class ${clazz.name}, falling back to app resources")
            return null
        }
        return cache.getOrPut(apkPath) {
            try {
                val assetManager = AssetManager::class.java.getDeclaredConstructor().newInstance()
                val addAssetPathMethod = AssetManager::class.java.getMethod("addAssetPath", String::class.java)
                addAssetPathMethod.invoke(assetManager, apkPath)

                val hostResources = app.resources
                val res = Resources(assetManager, hostResources.displayMetrics, hostResources.configuration)
                Log.d(EXTENSION_RESOURCES_LOG, "Successfully initialized Resources from APK: $apkPath")
                res
            } catch (e: Exception) {
                Log.e(EXTENSION_RESOURCES_LOG, "Failed to create Resources from APK $apkPath: ${e.message}", e)
                app.resources
            }
        }
    }

    fun getApkPath(app: Application, clazz: Class<*>): String? {
        // 1. Try via class CodeSource location
        try {
            clazz.protectionDomain?.codeSource?.location?.path?.let { path ->
                val clean = path.substringBefore("!/").removePrefix("file:")
                if (clean.endsWith(".apk")) {
                    Log.d(EXTENSION_RESOURCES_LOG, "[Solution 1 - CodeSource] Found APK: $clean for ${clazz.name}")
                    return clean
                }
            }
        } catch (e: Exception) {
            Log.w(EXTENSION_RESOURCES_LOG, "CodeSource lookup failed for ${clazz.name}: ${e.message}")
        }

        // 2. Try via ClassLoader (DexClassLoader / PathClassLoader)
        val classLoader = clazz.classLoader
        if (classLoader is BaseDexClassLoader) {
            try {
                val pathListField = BaseDexClassLoader::class.java.getDeclaredField("pathList").apply { isAccessible = true }
                val pathList = pathListField.get(classLoader)
                val dexElementsField = pathList.javaClass.getDeclaredField("dexElements").apply { isAccessible = true }
                val dexElements = dexElementsField.get(pathList) as? Array<*>
                if (dexElements != null) {
                    for (element in dexElements) {
                        val fileField = element?.javaClass?.declaredFields?.firstOrNull { it.name == "path" || it.name == "file" }
                        fileField?.isAccessible = true
                        val file = fileField?.get(element)
                        val path = file?.toString()
                        if (path != null && path.endsWith(".apk")) {
                            Log.d(EXTENSION_RESOURCES_LOG, "[Solution 2 - ClassLoader] Found APK: $path for ${clazz.name}")
                            return path
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(EXTENSION_RESOURCES_LOG, "ClassLoader reflection lookup failed for ${clazz.name}: ${e.message}")
            }
        }

        // 3. Fallback to packageManager if installed globally
        val pkgName = clazz.`package`?.name ?: ""
        return try {
            val path = app.packageManager.getPackageInfo(pkgName, 0).applicationInfo?.publicSourceDir
            if (path != null) {
                Log.d(EXTENSION_RESOURCES_LOG, "[Solution 3 - PackageManager] Found APK: $path for $pkgName")
            }
            path
        } catch (e: Exception) {
            Log.w(EXTENSION_RESOURCES_LOG, "PackageManager lookup failed for $pkgName: ${e.message}")
            null
        }
    }

    fun getVersionName(app: Application, clazz: Class<*>): String? {
        val apkPath = getApkPath(app, clazz) ?: return null
        return try {
            app.packageManager.getPackageArchiveInfo(apkPath, 0)?.versionName
        } catch (_: Exception) {
            null
        }
    }
}
