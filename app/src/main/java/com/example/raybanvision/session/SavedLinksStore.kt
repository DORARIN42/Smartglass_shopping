package com.example.raybanvision.session

import androidx.compose.runtime.mutableStateListOf
import com.example.raybanvision.data.SavedLink

/**
 * In-memory store for saved product links (same singleton pattern as CapturedPhotoStore).
 * Survives recomposition but not process death — a Room DB would be needed for persistence.
 */
object SavedLinksStore {
    val links = mutableStateListOf<SavedLink>()

    /** Prepends [link] to the top of the list. Skips duplicates by linkUrl. */
    fun save(link: SavedLink) {
        if (link.linkUrl.isEmpty() || links.none { it.linkUrl == link.linkUrl }) {
            links.add(0, link)
        }
    }

    /** Removes the saved link matching [linkUrl]. No-op if not found. */
    fun remove(linkUrl: String) {
        links.removeAll { it.linkUrl == linkUrl }
    }
}
