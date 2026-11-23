package dev.libretv.android

import org.junit.Assert.assertEquals
import org.junit.Test

class ImportSelectionTest {
    private val first = CmsSource("甲", "https://example.org/a")
    private val second = CmsSource("乙", "https://example.org/b")
    private val third = CmsSource("丙", "https://example.org/c")

    @Test fun reimportRemembersEnabledAndDisabledEndpointsEvenAfterRename() {
        val renamed = first.copy(name = "新名称")
        assertEquals(setOf(renamed), selectionAfterImport(listOf(first, second),
            setOf(first), listOf(second, renamed)))
    }

    @Test fun onlyNewEndpointsAreEnabledWhenEverythingWasDisabled() {
        assertEquals(setOf(third), selectionAfterImport(listOf(first, second),
            emptySet(), listOf(second, third, first)))
    }
}
