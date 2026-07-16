package com.agentpad.app.media

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PhotoDateQueryParserTest {
    @Test
    fun parsesChineseYearMonth() {
        val range = PhotoDateQueryParser.parseYearMonth("帮我找2024年5月的照片")
        assertNotNull(range)
        val (start, end) = range!!
        val cal = Calendar.getInstance().apply { timeInMillis = start }
        assertEquals(2024, cal.get(Calendar.YEAR))
        assertEquals(Calendar.MAY, cal.get(Calendar.MONTH))
        assertEquals(1, cal.get(Calendar.DAY_OF_MONTH))
        val calEnd = Calendar.getInstance().apply { timeInMillis = end }
        assertEquals(Calendar.JUNE, calEnd.get(Calendar.MONTH))
    }

    @Test
    fun parsesIsoLikeMonth() {
        val range = PhotoDateQueryParser.parseYearMonth("photos from 2023-12")
        assertNotNull(range)
        val cal = Calendar.getInstance().apply { timeInMillis = range!!.first }
        assertEquals(2023, cal.get(Calendar.YEAR))
        assertEquals(Calendar.DECEMBER, cal.get(Calendar.MONTH))
    }

    @Test
    fun returnsNullWhenNoDate() {
        assertNull(PhotoDateQueryParser.parseYearMonth("找海边的照片"))
    }
}
