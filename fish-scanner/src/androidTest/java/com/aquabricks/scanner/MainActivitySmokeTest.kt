package com.aquabricks.scanner

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {

    @Test
    fun scannerScreenInflatesAndShowsItsTitle() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val decor = activity.window.decorView
                assertTrue("decor view is not shown", decor.isShown)
                val title = findText(decor, "AQUABRICKS")
                assertNotNull("AQUABRICKS title not found", title)
                assertTrue("title is not visible", title!!.isShown)
                val subtitle = findText(decor, "BRICK FISH SCANNER")
                assertNotNull("subtitle not found", subtitle)
                assertTrue("subtitle is not visible", subtitle!!.isShown)
                val manage = findText(decor, "MANAGE FISH")
                assertNotNull("direct manage-fish entry point not found", manage)
                assertTrue("manage-fish entry point is not visible", manage!!.isShown)
                assertTrue("manage-fish entry point is not clickable", manage.isClickable)
            }
        }
    }

    private fun findText(view: View, value: String): TextView? {
        if (view is TextView && view.text.toString() == value) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                val found = findText(view.getChildAt(index), value)
                if (found != null) return found
            }
        }
        return null
    }
}
