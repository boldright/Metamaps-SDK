package jp.metamaps.positioning.android

import kotlin.test.Test
import kotlin.test.assertEquals

class PermissionResultsTests {
    @Test
    fun partialCallbackKeepsAlreadyGrantedPermissions() {
        val requested = listOf("coarse", "fine", "scan", "connect")
        val callback = mapOf("scan" to true, "connect" to true)
        val alreadyGranted = setOf("coarse", "fine")

        val result = completePermissionResults(requested, callback, alreadyGranted::contains)

        assertEquals(requested.associateWith { true }, result)
    }
}
