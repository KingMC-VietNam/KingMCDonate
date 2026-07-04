package net.kingmc.plugin.kingmcdonate.gui

/**
 * Pure paging arithmetic for [Pagination]: tracks the current page over a known item
 * count and clamps navigation, with no dependency on Bukkit or a [Gui]. An empty data
 * set is always a single page. A [pageSize] of zero is treated as one so paging never
 * divides by zero.
 */
class PageState(private val pageSize: Int) {

    var itemCount: Int = 0
        private set
    var pageIndex: Int = 0
        private set

    private val effectiveSize: Int get() = pageSize.coerceAtLeast(1)

    val pageCount: Int get() = if (itemCount == 0) 1 else (itemCount + effectiveSize - 1) / effectiveSize

    /** Point at a new item count and jump back to the first page. */
    fun reset(count: Int) {
        itemCount = count
        pageIndex = 0
    }

    /** Advance one page if one exists; returns whether the page moved. */
    fun next(): Boolean {
        if (pageIndex + 1 < pageCount) {
            pageIndex++
            return true
        }
        return false
    }

    /** Go back one page if not already first; returns whether the page moved. */
    fun previous(): Boolean {
        if (pageIndex > 0) {
            pageIndex--
            return true
        }
        return false
    }

    /** Index of the first item shown on the current page. */
    fun startIndex(): Int = pageIndex * effectiveSize
}
