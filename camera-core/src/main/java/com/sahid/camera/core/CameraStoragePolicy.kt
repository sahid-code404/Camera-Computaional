package com.sahid.camera.core

/**
 * Central shared-storage policy for Camera.
 *
 * The user-facing rendition uses Android's conventional camera album so Gallery/Photos apps discover
 * it naturally. Aurora's canonical custom RAW master is a generic document, not an image MIME type,
 * so it lives in Documents while the app presents both as one capture in its own gallery.
 *
 * A later storage-settings milestone will make [VISIBLE_ALBUM_RELATIVE_PATH] user-selectable through
 * MediaStore/SAF without changing capture or render engines.
 */
object CameraStoragePolicy {
    const val VISIBLE_ALBUM_RELATIVE_PATH = "DCIM/Camera"
    const val CANONICAL_RAW_RELATIVE_PATH = "Documents/Camera/RAW"
}
