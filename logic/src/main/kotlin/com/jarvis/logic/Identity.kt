package com.jarvis.logic

enum class IdentityOrigin { PHONE, APP, MAPS, WEB }

data class IdentityHit(
    val origin: IdentityOrigin,
    val name: String,
    val sourceLabel: String,
    val ratingText: String? = null,
    val reviewText: String? = null,
    val address: String? = null,
    val photoUrl: String? = null,
    val knownContact: Boolean = origin == IdentityOrigin.PHONE
)

fun chooseIdentity(hits: List<IdentityHit>): IdentityHit? {
    val order = listOf(IdentityOrigin.PHONE, IdentityOrigin.APP, IdentityOrigin.MAPS, IdentityOrigin.WEB)
    return order.firstNotNullOfOrNull { origin ->
        hits.firstOrNull { it.origin == origin && it.name.isNotBlank() }
    }
}

/** A public photo may fill an empty contact photo. It never replaces one the user already has. */
fun maySaveContactPhoto(contactExists: Boolean, alreadyHasPhoto: Boolean, hasCandidate: Boolean): Boolean {
    return contactExists && !alreadyHasPhoto && hasCandidate
}
