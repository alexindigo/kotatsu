package com.gezimos.katapult.lockscreen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Parcel
import com.gezimos.katapult.R
import org.xmlpull.v1.XmlPullParser

/** Shared Android boundary for scoped event delivery. Copies every unrelated service property. */
internal class AndroidEventDelivery(private val service: AccessibilityService) : ReplayServiceInfoPort<AccessibilityServiceInfo> {
    companion object {
        private val connections = java.util.IdentityHashMap<AccessibilityService, ReplayDelivery<AccessibilityServiceInfo>>()
        fun connected(service: AccessibilityService) {
            disconnected(service)
            connections[service] = ReplayDelivery(AndroidEventDelivery(service)) {
                android.util.Log.e("AndroidEventDelivery", "Could not restore event delivery until reconnect", it)
            }
        }
        fun scoped(service: AccessibilityService): ReplayDelivery<AccessibilityServiceInfo> =
            checkNotNull(connections[service]) { "Event delivery has no connected owner" }
        fun disconnected(service: AccessibilityService) { connections.remove(service)?.cancel() }
    }
    override fun currentInfo(): AccessibilityServiceInfo = copy(service.serviceInfo)
    override fun staticInfo(): AccessibilityServiceInfo = currentInfo().apply {
        service.resources.getXml(R.xml.lockscreen_widget_service).use { xml ->
            while (xml.eventType != XmlPullParser.START_TAG) xml.next()
            val namespace = "http://schemas.android.com/apk/res/android"
            packageNames = xml.getAttributeValue(namespace, "packageNames")?.split(',')?.toTypedArray()
            notificationTimeout = xml.getAttributeIntValue(namespace, "notificationTimeout", 0).toLong()
        }
    }
    override fun widened(info: AccessibilityServiceInfo): AccessibilityServiceInfo = copy(info).apply {
        packageNames = null
        notificationTimeout = 0L
    }
    override fun apply(info: AccessibilityServiceInfo) {
        service.serviceInfo = copy(info).apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
    }
    private fun copy(info: AccessibilityServiceInfo): AccessibilityServiceInfo {
        val parcel = Parcel.obtain()
        return try {
            info.writeToParcel(parcel, 0); parcel.setDataPosition(0)
            AccessibilityServiceInfo.CREATOR.createFromParcel(parcel)
        } finally { parcel.recycle() }
    }
}
