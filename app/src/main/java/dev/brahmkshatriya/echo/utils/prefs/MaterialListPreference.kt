package dev.brahmkshatriya.echo.utils.prefs

import android.content.Context
import androidx.preference.ListPreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.brahmkshatriya.echo.R

class MaterialListPreference(context: Context) : ListPreference(context) {

    private var customSummary: CharSequence? = null

    override fun onSetInitialValue(defaultValue: Any?) {
        super.onSetInitialValue(defaultValue)
        customSummary = summary
        updateSummary()
    }

    override fun onClick() {
        MaterialAlertDialogBuilder(context)
            .setSingleChoiceItems(entries, entryValues.indexOf(value)) { dialog, index ->
                if (callChangeListener(entryValues[index].toString())) {
                    setValueIndex(index)
                    updateSummary()
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel) { dialog, _ -> dialog.dismiss() }.setTitle(title)
            .create()
            .show()
    }

    private fun updateSummary() {
        val value = context.getString(R.string.value)
        val entry = entry ?: context.getString(R.string.not_set)
        val sum = customSummary?.let { "\n\n$it" } ?: ""
        summary = "$value : $entry$sum"
    }
}