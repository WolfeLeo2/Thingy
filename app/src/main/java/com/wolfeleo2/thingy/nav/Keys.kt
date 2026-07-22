package com.wolfeleo2.thingy.nav

import androidx.navigation3.runtime.NavKey

// Gate destinations + pushed screens. Home is the tabbed shell (Home/Spaces tabs live inside it).
// ponytail: not @Serializable — gate re-derives from state; back stack lost on process death is
// acceptable. Per-tab back stacks deferred until Tidy/Search need retained depth.
data object Login : NavKey
data object Onboarding : NavKey
data object Home : NavKey
data object Settings : NavKey
data object Map : NavKey

data class ItemDetail(val itemIds: List<String>, val startIndex: Int, val spaceId: String? = null, val disableSharedTransition: Boolean = false) : NavKey
data class SpaceDetail(val spaceId: String) : NavKey
data class NewSpace(val spaceId: String? = null) : NavKey   // null = create, else edit
data class Camera(val spaceId: String? = null) : NavKey
