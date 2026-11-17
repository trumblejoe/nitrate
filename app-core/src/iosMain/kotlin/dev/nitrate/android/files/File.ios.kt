package dev.nitrate.android.files

import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSNumber
import platform.Foundation.dateWithTimeIntervalSince1970

actual fun setFileModified(path: String, ms: Long): Boolean =
    NSFileManager.defaultManager.setAttributes(mapOf<Any?, Any?>(NSFileModificationDate to NSDate.dateWithTimeIntervalSince1970(ms / 1000.0)), ofItemAtPath = path, error = null)

actual fun freeSpace(path: String): Long =
    (NSFileManager.defaultManager.attributesOfFileSystemForPath(path, error = null)?.get(NSFileSystemFreeSize) as? NSNumber)?.longLongValue ?: 0L
