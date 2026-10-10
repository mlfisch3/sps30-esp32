package com.sensirion.sps30.service

import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The foreground service type, held from the outside.
 *
 * Two halves have to agree and they live in different files: the manifest's
 * `android:foregroundServiceType` and the constant [Sps30Service.FOREGROUND_TYPE]
 * that `startForeground` is called with. When they disagree the platform throws
 * `InvalidForegroundServiceTypeException` - but only on a device, only at the
 * moment the user switches monitoring on, and by then the app is in the field.
 * This reads the **merged manifest** the way the system does and compares it
 * with the constant, so changing either one alone is a red build.
 *
 * ## Why `connectedDevice`, and why this is worth a test at all
 *
 * It was `dataSync` until 2026-08-06 and that was wrong on two counts. Play's
 * approved `dataSync` uses are backup, restore, upload, download, import and
 * export - transfers a user starts and that then end - and a sensor stream that
 * runs for as long as monitoring is left on is none of them, so the declaration
 * would have failed foreground-service review. And from Android 15 `dataSync`
 * carries a six-hour daily budget after which the system calls
 * `Service.onTimeout` and the stream stops; `connectedDevice` has no budget.
 * Reverting the type therefore silently reintroduces both a policy failure and a
 * six-hour ceiling on overnight monitoring, neither of which any other test in
 * this pack can see.
 *
 * ## Why this runs at 35 and not at the 36 the module targets
 *
 * Robolectric refuses to build a sandbox for **API 36 unless the test JVM is
 * Java 21**, and this project's toolchain is JDK 17, so 35 is the pack's
 * ceiling - `@Config(sdk = [36])` fails with `Android SDK 36 requires Java 21`
 * rather than running. That ceiling applies to the whole pack, not just this
 * test: nothing here exercises API 36 today. What is asserted below is a
 * manifest declaration and a set of requested permissions, neither of which the
 * sandbox level changes, so 35 costs this test nothing - but it does mean the
 * *behaviour* of a connectedDevice service at 36 is a device check. See the
 * README's test section.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Sps30ServiceTypeTest {

    private val app get() = RuntimeEnvironment.getApplication()

    @Test
    fun theManifestAndTheCodeNameTheSameType() {
        val declared = app.packageManager
            .getServiceInfo(ComponentName(app, Sps30Service::class.java), 0)
            .foregroundServiceType
        assertEquals(
            "manifest android:foregroundServiceType and Sps30Service.FOREGROUND_TYPE " +
                "must name the same type",
            Sps30Service.FOREGROUND_TYPE,
            declared,
        )
    }

    @Test
    fun theTypeIsConnectedDeviceAndOnlyThat() {
        val declared = app.packageManager
            .getServiceInfo(ComponentName(app, Sps30Service::class.java), 0)
            .foregroundServiceType
        assertEquals(
            "a continuous sensor stream is `connectedDevice`, not one of the finite " +
                "transfers `dataSync` covers",
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            declared,
        )
        assertEquals(
            "dataSync must not be left in the declaration - its six-hour daily budget " +
                "applies to a service that carries the bit at all",
            0,
            declared and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    @Test
    fun thePermissionsMatchTheTypeAndItsPrerequisite() {
        val requested = app.packageManager
            .getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSet()

        assertTrue(
            "a connectedDevice FGS needs FOREGROUND_SERVICE_CONNECTED_DEVICE",
            "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" in requested,
        )
        assertFalse(
            "FOREGROUND_SERVICE_DATA_SYNC is no longer used and an unused permission is " +
                "one more thing Play asks about",
            "android.permission.FOREGROUND_SERVICE_DATA_SYNC" in requested,
        )
        // The system also requires a *prerequisite* before it will let a
        // connectedDevice service start. CHANGE_NETWORK_STATE is the one that
        // matters here because it is a normal permission - held from install,
        // never revocable - so the promotion cannot fail because the user said
        // no to Bluetooth. The BLUETOOTH_* requests satisfy it as well.
        assertTrue(
            "connectedDevice needs one of the network/Bluetooth prerequisites declared",
            "android.permission.CHANGE_NETWORK_STATE" in requested,
        )
        assertTrue(
            "the Bluetooth transport's own prerequisite is still declared",
            "android.permission.BLUETOOTH_CONNECT" in requested,
        )
    }
}
