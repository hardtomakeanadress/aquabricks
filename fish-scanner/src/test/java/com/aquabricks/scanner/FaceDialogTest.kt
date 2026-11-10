package com.aquabricks.scanner

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.aquabricks.scanner.imaging.ArgbImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FaceDialogTest {

    @Test
    fun changingTheEyeModelKeepsThePositionAlreadyDragged() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val pixels = IntArray(80 * 40) { ArgbImage.argb(255, 200, 70, 30) }
        val base = ArgbImage(80, 40, pixels)
        var applied: FaceConfig? = null

        FaceDialog(activity, base, FaceConfig(), facingRight = true) { applied = it }.show()
        ShadowLooper.idleMainLooper()

        val dialog = ShadowDialog.getLatestDialog()
        assertNotNull("face dialog did not open", dialog)
        val root = dialog.window!!.decorView
        val editor = findEditor(root)
        assertNotNull("face editor missing", editor)

        // Simulate a drag: only the editor's copy is updated.
        editor!!.setConfig(editor.currentConfig().copy(eyeX = 0.21f, eyeY = 0.33f))
        val happy = findText(root, EyeModel.HAPPY.label)
        assertNotNull("HAPPY eye chip missing", happy)
        happy!!.performClick()
        val apply = findText(root, activity.getString(R.string.face_apply))
        assertNotNull("APPLY button missing", apply)
        apply!!.performClick()

        val result = applied
        assertNotNull("face was not applied", result)
        assertEquals(EyeModel.HAPPY, result!!.eye)
        assertEquals("eye X was reset by the model chip", 0.21f, result.eyeX, 0.0001f)
        assertEquals("eye Y was reset by the model chip", 0.33f, result.eyeY, 0.0001f)
        assertTrue(result.mouth == MouthModel.SMILE)
    }

    private fun findEditor(view: View): FaceEditorView? {
        if (view is FaceEditorView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findEditor(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun findText(view: View, value: String): TextView? {
        if (view is TextView && view.isClickable && view.text.toString() == value) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findText(view.getChildAt(index), value)?.let { return it }
            }
        }
        return null
    }
}
