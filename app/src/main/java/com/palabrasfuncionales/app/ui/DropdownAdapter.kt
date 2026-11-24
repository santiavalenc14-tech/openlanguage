package com.palabrasfuncionales.app.ui

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.Filter
import com.palabrasfuncionales.app.R

/**
 * Adapter for the exposed dropdown menus (language, section, category…).
 *
 * A plain [ArrayAdapter] filters its items by the text in the field. When Android
 * restores the field's text (rotation, theme/locale change, returning to the app),
 * that filter runs with the selected label, e.g. "German (Deutsch)", and the menu
 * then shows only that one item. These menus are pick-lists, not search boxes, so
 * the filter always returns every item.
 */
class DropdownAdapter(context: Context, private val items: List<String>) :
    ArrayAdapter<String>(context, R.layout.item_dropdown, items) {

    private val noFilter = object : Filter() {
        override fun performFiltering(constraint: CharSequence?) = FilterResults().apply {
            values = items
            count = items.size
        }

        override fun publishResults(constraint: CharSequence?, results: FilterResults?) =
            notifyDataSetChanged()

        override fun convertResultToString(resultValue: Any?) = resultValue?.toString().orEmpty()
    }

    override fun getFilter(): Filter = noFilter
}
