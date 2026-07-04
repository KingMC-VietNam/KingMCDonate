package net.kingmc.plugin.kingmcdonate.gui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PageStateTest {

    @Test
    fun `empty data is a single page at index zero`() {
        val s = PageState(pageSize = 5)
        s.reset(0)
        assertEquals(1, s.pageCount)
        assertEquals(0, s.pageIndex)
    }

    @Test
    fun `page count rounds up partial pages`() {
        val s = PageState(pageSize = 5)
        s.reset(5); assertEquals(1, s.pageCount)
        s.reset(6); assertEquals(2, s.pageCount)
        s.reset(11); assertEquals(3, s.pageCount)
    }

    @Test
    fun `next advances until the last page then refuses`() {
        val s = PageState(pageSize = 5)
        s.reset(11) // 3 pages: 0,1,2
        assertTrue(s.next()); assertEquals(1, s.pageIndex)
        assertTrue(s.next()); assertEquals(2, s.pageIndex)
        assertFalse(s.next()); assertEquals(2, s.pageIndex)
    }

    @Test
    fun `previous never goes below zero`() {
        val s = PageState(pageSize = 5)
        s.reset(11)
        assertFalse(s.previous()); assertEquals(0, s.pageIndex)
        s.next()
        assertTrue(s.previous()); assertEquals(0, s.pageIndex)
    }

    @Test
    fun `reset returns to the first page`() {
        val s = PageState(pageSize = 5)
        s.reset(11); s.next(); s.next()
        s.reset(3)
        assertEquals(0, s.pageIndex)
        assertEquals(1, s.pageCount)
    }

    @Test
    fun `start index is page times page size`() {
        val s = PageState(pageSize = 5)
        s.reset(11)
        assertEquals(0, s.startIndex())
        s.next(); assertEquals(5, s.startIndex())
        s.next(); assertEquals(10, s.startIndex())
    }

    @Test
    fun `page size of zero is treated as one`() {
        val s = PageState(pageSize = 0)
        s.reset(3)
        assertEquals(3, s.pageCount)
        assertEquals(0, s.startIndex())
    }
}
