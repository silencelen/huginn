package com.silencelen.huginn

import android.content.pm.ServiceInfo
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element

/**
 * ⚠⚠ THE WATCH SERVICE MAY ONLY PASS A TYPE ITS MANIFEST ENTRY DECLARES.
 *
 * Android 10-13 check that the type handed to `startForeground` is a subset of
 * the service's `android:foregroundServiceType`, and throw when it is not —
 * inside `onStartCommand`, which kills the process. The manifest declares
 * specialUse alone and the pre-14 branch passed dataSync, so "Watch
 * continuously" crashed the app on those versions at every launch, boot and
 * update after it was turned on. The owner's phone takes the 14+ branch, which
 * is why nothing showed.
 *
 * There is no device in this build, so this makes the platform's own check
 * against the two files: every type the service's source names is NONE or
 * declared, and every declared type carries the permission Android 14 demands.
 *
 * NOTE this module is on org.junit, whose argument order is (message, expected,
 * actual) — the REVERSE of the kotlin.test order `:core` and `:ui` use.
 */
class WatchServiceTypeTest {

    private val androidNs = "http://schemas.android.com/apk/res/android"

    /** Manifest spelling to platform bit, for every type a manifest can name. */
    private val types = mapOf(
        "dataSync" to ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        "mediaPlayback" to ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        "phoneCall" to ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL,
        "location" to ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        "connectedDevice" to ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        "mediaProjection" to ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        "camera" to ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
        "microphone" to ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        "health" to ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH,
        "remoteMessaging" to ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING,
        "systemExempted" to ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
        "shortService" to ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE,
        "mediaProcessing" to ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
        "specialUse" to ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
    )

    /** `dataSync` -> `DATA_SYNC`: how the constant and the permission spell a type. */
    private fun upperSnake(name: String) = name.replace(Regex("([A-Z])"), "_$1").uppercase()

    private fun root(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")

    private fun file(rel: String): File {
        val f = File(rel).takeIf { it.isAbsolute } ?: File(root(), rel)
        assertTrue("$rel not found at ${f.absolutePath}", f.isFile)
        return f
    }

    private fun manifest(): org.w3c.dom.Document =
        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(file("app/src/main/AndroidManifest.xml"))

    private fun watchServiceEntry(): Element {
        val services = manifest().getElementsByTagName("service")
        val found = (0 until services.length).map { services.item(it) as Element }
            .filter { it.getAttributeNS(androidNs, "name").endsWith(".notify.WatchService") }
        assertTrue("the manifest has ${found.size} WatchService entries, not one", found.size == 1)
        return found.single()
    }

    /** The manifest's declared types, by name. */
    private fun declaredNames(): List<String> {
        val raw = watchServiceEntry().getAttributeNS(androidNs, "foregroundServiceType")
        assertTrue("WatchService declares no foregroundServiceType at all", raw.isNotBlank())
        return raw.split('|').map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun declaredMask(): Int = declaredNames().fold(0) { mask, name ->
        mask or (types[name] ?: error("manifest type \"$name\" is not in this test's table; add it"))
    }

    /** Every FOREGROUND_SERVICE_TYPE_* the service's CODE names; comments do not count. */
    private fun typesNamedInSource(): List<String> {
        val text = file("app/src/main/kotlin/com/silencelen/huginn/notify/WatchService.kt").readText()
        // A grep that matches nothing exits 0. Assert the floor before the finding.
        assertTrue("WatchService.kt read as ${text.length} chars — wrong file", text.length > 2_000)
        val code = text
            .replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .replace(Regex("""//[^\n]*"""), "")
        return Regex("""FOREGROUND_SERVICE_TYPE_[A-Z_]+""").findAll(code).map { it.value }.distinct().toList()
    }

    private fun bitFor(constant: String): Int {
        if (constant == "FOREGROUND_SERVICE_TYPE_NONE") return ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
        if (constant == "FOREGROUND_SERVICE_TYPE_MANIFEST") return ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
        val name = types.keys.firstOrNull { "FOREGROUND_SERVICE_TYPE_" + upperSnake(it) == constant }
            ?: error("$constant is not in this test's table; add it")
        return types.getValue(name)
    }

    @Test
    fun `every type the watch service can pass is one its manifest entry declares`() {
        val declared = declaredMask()
        val named = typesNamedInSource()
        assertTrue("found no foreground type in WatchService.kt — the check proves nothing", named.isNotEmpty())
        for (constant in named) {
            val bit = bitFor(constant)
            // NONE is a subset of anything; MANIFEST means "whatever is declared".
            if (bit == ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE) continue
            if (bit == ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST) continue
            if (bit and declared != bit) {
                fail(
                    "WatchService passes $constant (0x${bit.toString(16)}), which its manifest entry does " +
                        "not declare (0x${declared.toString(16)}): Android 10-13 throw from startForeground " +
                        "for that, inside onStartCommand, and the app dies",
                )
            }
        }
    }

    @Test
    fun `Android 14 and later still get the declared special-use type`() {
        // The 14+ branch must keep naming the type, not fall back to NONE: from
        // 14 on a foreground service has to say what it is for, and specialUse
        // is what this one declared (with its subtype property) in the manifest.
        assertTrue(
            "the specialUse branch is gone from WatchService",
            "FOREGROUND_SERVICE_TYPE_SPECIAL_USE" in typesNamedInSource(),
        )
        assertTrue("the manifest no longer declares specialUse", "specialUse" in declaredNames())
    }

    @Test
    fun `every declared type carries the permission Android 14 asks for`() {
        val doc = manifest()
        val perms = doc.getElementsByTagName("uses-permission").let { list ->
            (0 until list.length).map { (list.item(it) as Element).getAttributeNS(androidNs, "name") }.toSet()
        }
        for (name in declaredNames()) {
            // shortService is the one type that needs no permission of its own.
            if (name == "shortService") continue
            val perm = "android.permission.FOREGROUND_SERVICE_" + upperSnake(name)
            assertTrue("the manifest declares $name but not $perm", perm in perms)
        }
    }
}
