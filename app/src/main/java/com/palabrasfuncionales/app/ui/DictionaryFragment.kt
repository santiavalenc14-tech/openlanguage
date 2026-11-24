package com.palabrasfuncionales.app.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.palabrasfuncionales.app.R
import com.palabrasfuncionales.app.data.DictionaryRepository
import com.palabrasfuncionales.app.data.LanguagePack
import com.palabrasfuncionales.app.data.Languages
import com.palabrasfuncionales.app.databinding.FragmentDictionaryBinding
import kotlinx.coroutines.launch

class DictionaryFragment : Fragment() {
    private var _b: FragmentDictionaryBinding? = null
    private val b get() = _b!!
    private var pack: LanguagePack? = null
    private var section: String? = null
    private val adapter = EntryAdapter { entry ->
        val p = pack ?: return@EntryAdapter
        EntrySheet.newInstance(p.lang.code, entry.id).show(childFragmentManager, "entry")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentDictionaryBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.list.adapter = adapter
        b.list.setHasFixedSize(true)

        val langs = Languages.dictionary
        b.language.setAdapter(DropdownAdapter(requireContext(), langs.map { it.label }))
        b.language.setOnItemClickListener { _, _, pos, _ -> selectLanguage(langs[pos].code) }

        b.query.doAfterTextChanged { refresh() }

        val saved = prefs().getString(KEY_LANG, langs.first().code) ?: langs.first().code
        selectLanguage(if (Languages.byCode(saved) != null && langs.any { it.code == saved }) saved else langs.first().code)
    }

    private fun selectLanguage(code: String) {
        prefs().edit().putString(KEY_LANG, code).apply()
        val lang = Languages.byCode(code) ?: return
        b.language.setText(lang.label, false)
        b.progress.isVisible = true
        viewLifecycleOwner.lifecycleScope.launch {
            val p = DictionaryRepository.load(requireContext(), code)
            if (_b == null) return@launch
            pack = p
            section = null
            setupSections(p)
            b.progress.isVisible = false
            refresh()
            b.list.scrollToPosition(0)
        }
    }

    private fun setupSections(p: LanguagePack) {
        val all = getString(R.string.all_sections)
        val items = listOf(all) + p.sections
        b.section.setAdapter(DropdownAdapter(requireContext(), items))
        b.section.setText(all, false)
        b.section.setOnItemClickListener { _, _, pos, _ ->
            section = if (pos == 0) null else items[pos]
            refresh()
        }
    }

    private fun refresh() {
        val p = pack ?: return
        val results = DictionaryRepository.search(p, b.query.text?.toString().orEmpty(), section)
        adapter.showSection = section == null
        adapter.submitList(results)
        b.count.text = resources.getQuantityString(R.plurals.word_count, results.size, results.size)
        b.empty.isVisible = results.isEmpty()
    }

    private fun prefs() = requireContext().getSharedPreferences("ui", Context.MODE_PRIVATE)

    override fun onDestroyView() {
        b.list.adapter = null
        _b = null
        super.onDestroyView()
    }

    private companion object {
        const val KEY_LANG = "dictionary_language"
    }
}
