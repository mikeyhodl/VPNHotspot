package be.mygod.vpnhotspot.net

import android.content.Context
import android.os.Build
import android.os.IBinder
import android.system.OsConstants
import androidx.annotation.RequiresApi
import be.mygod.librootkotlinx.ParcelableBoolean
import be.mygod.librootkotlinx.RootCommand
import be.mygod.librootkotlinx.systemContext
import be.mygod.vpnhotspot.BuildConfig
import be.mygod.vpnhotspot.util.Services
import dalvik.system.PathClassLoader
import kotlinx.parcelize.Parcelize
import timber.log.Timber
import java.io.File

/**
 * https://android.googlesource.com/platform/system/netd/+/android-10.0.0_r1/server/NetdNativeService.cpp#1138
 */
@Parcelize
@RequiresApi(29)
data class RemoveUidInterfaceRuleCommand(private val uid: Int) : RootCommand<ParcelableBoolean> {
    @Suppress("JAVA_CLASS_ON_COMPANION")
    companion object {
        private fun findConnectivityClass(baseName: String, loader: ClassLoader? = javaClass.classLoader): Class<*> {
            // only relevant for Android 11+ where com.android.tethering APEX exists
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    // https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/tags/android-14.0.0_r1/service/Android.bp#333
                    return Class.forName("android.net.connectivity.$baseName", true, loader)
                } catch (_: ClassNotFoundException) { }
                try {
                    // https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/tags/android-12.0.0_r1/service/jarjar-rules.txt#29
                    return Class.forName("com.android.connectivity.$baseName", true, loader)
                } catch (_: ClassNotFoundException) { }
            }
            return Class.forName(baseName, true, loader)
        }

        private val servicesClassLoader by lazy {
            if (Build.VERSION.SDK_INT >= 30) {
                PathClassLoader("/apex/com.android.tethering/javalib/service-connectivity.jar${File.pathSeparator}/system/framework/services.jar",
                    "/apex/com.android.tethering/lib64${File.pathSeparator}/apex/com.android.tethering/lib",
                    javaClass.classLoader)
            } else PathClassLoader("/system/framework/services.jar", javaClass.classLoader)
        }
    }

    /**
     * Deprecated in Android 13: https://android.googlesource.com/platform/system/netd/+/android-13.0.0_r1/server/NetdNativeService.cpp#1142
     */
    private object Impl29 {
        private val stub by lazy { findConnectivityClass("android.net.INetd\$Stub", servicesClassLoader) }
        val netd by lazy {
            stub.getDeclaredMethod("asInterface", IBinder::class.java)(null, Services.netd)
        }
        private val firewallRemoveUidInterfaceRules by lazy {
            stub.getMethod("firewallRemoveUidInterfaceRules", IntArray::class.java)
        }
        operator fun invoke(uid: Int) = firewallRemoveUidInterfaceRules(netd, intArrayOf(uid))
    }

    /**
     * https://android.googlesource.com/platform/packages/modules/Connectivity/+/android-14.0.0_r1/service/src/com/android/server/BpfNetMaps.java#416
     */
    @RequiresApi(33)
    private object JniBpfMap {
        private val matches by lazy {
            try {
                val constants = findConnectivityClass("android.net.BpfNetMapsConstants")
                constants.getDeclaredField("IIF_MATCH").getLong(null) or
                        constants.getDeclaredField("LOCKDOWN_VPN_MATCH").getLong(null)
            } catch (e: ReflectiveOperationException) {
                Timber.w(e)
                // https://android.googlesource.com/platform/packages/modules/Connectivity/+/android-13.0.0_r1/bpf_progs/bpf_shared.h#160
                3 shl 7
            }
        }

        operator fun invoke(uid: Int): Boolean {
            val clazz = systemContext.createPackageContext(BuildConfig.APPLICATION_ID,
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
                .classLoader.loadClass("be.mygod.vpnhotspot.root.Jni")
            val jni = clazz.getDeclaredConstructor().newInstance()
            return clazz.getDeclaredMethod("removeUidInterfaceRules", Int::class.java, Long::class.java)(
                jni, uid, matches) as Boolean
        }
    }

    @RequiresApi(33)
    private object NativeBpfMap {
        private val BpfNetMaps by lazy { findConnectivityClass("com.android.server.BpfNetMaps", servicesClassLoader) }
        private val bpfNetMaps by lazy {
            val constructor = try {
                // https://android.googlesource.com/platform/packages/modules/Connectivity/+/android-14.0.0_r1/service/src/com/android/server/BpfNetMaps.java#335
                BpfNetMaps.getDeclaredConstructor(Context::class.java)
            } catch (_: NoSuchMethodException) {
                // https://android.googlesource.com/platform/packages/modules/Connectivity/+/android-13.0.0_r1/service/src/com/android/server/BpfNetMaps.java#57
                return@lazy BpfNetMaps.getDeclaredConstructor().newInstance()
            }
            try {
                // try to bypass init to avoid side effects
                BpfNetMaps.getDeclaredField("sInitialized").apply { isAccessible = true }.setBoolean(null, true)
            } catch (e: ReflectiveOperationException) {
                Timber.w(e)
            }
            constructor.newInstance(null).also {
                try {
                    BpfNetMaps.getDeclaredMethod("native_init", Boolean::class.java)
                        .apply { isAccessible = true }(it, false)
                } catch (_: NoSuchMethodException) {
                    BpfNetMaps.getDeclaredMethod("native_init").apply { isAccessible = true }(it)
                }
            }
        }

        /**
         * https://android.googlesource.com/platform/packages/modules/Connectivity/+/android-13.0.0_r1/service/src/com/android/server/BpfNetMaps.java#273
         */
        private val setUidRule by lazy {
            BpfNetMaps.getDeclaredMethod("native_setUidRule", Int::class.java, Int::class.java, Int::class.java)
                .apply { isAccessible = true }
        }
        private val removeUidInterfaceRules by lazy {
            BpfNetMaps.getDeclaredMethod("native_removeUidInterfaceRules", IntArray::class.java)
                .apply { isAccessible = true }
        }
        private val updateUidLockdownRule by lazy {
            BpfNetMaps.getDeclaredMethod("native_updateUidLockdownRule", Int::class.java, Boolean::class.java)
                .apply { isAccessible = true }
        }
        private fun checkRet(ret: Any?, method: String) = when (ret as Int) {
            0, OsConstants.ENOENT -> { }
            else -> error("$method returns $ret")
        }
        operator fun invoke(uid: Int) {
            checkRet(removeUidInterfaceRules(bpfNetMaps, intArrayOf(uid)), "native_removeUidInterfaceRules")
            try {
                checkRet(updateUidLockdownRule(bpfNetMaps, uid, false), "native_updateUidLockdownRule")
            } catch (e: ReflectiveOperationException) {
                if (Build.VERSION.SDK_INT >= 34) Timber.w(e) else Timber.d(e)
                // FIREWALL_CHAIN_LOCKDOWN_VPN FIREWALL_RULE_ALLOW
                checkRet(setUidRule(bpfNetMaps, 6, uid, 1), "native_setUidRule")
            }
        }
    }

    override suspend fun execute() = ParcelableBoolean(if (Build.VERSION.SDK_INT < 33) {
        Impl29(uid)
        true
    } else try {
        JniBpfMap(uid)
    } catch (e: Exception) {
        if (Build.VERSION.SDK_INT >= 34) Timber.w(e)
        try {
            NativeBpfMap(uid)
            true
        } catch (e2: Exception) {
            e2.addSuppressed(e)
            throw e2
        }
    })
}
