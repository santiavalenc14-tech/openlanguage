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
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.palabrasfuncionales.app.R
import com.palabrasfuncionales.app.data.Lang
import com.palabrasfuncionales.app.data.Languages
import com.palabrasfuncionales.app.databinding.FragmentVocabularyBinding
import com.palabrasfuncionales.app.databinding.ItemVocabBinding
import com.palabrasfuncionales.app.learn.Lessons
import com.palabrasfuncionales.app.learn.SrsStore
import com.palabrasfuncionales.app.net.TranslateException
import com.palabrasfuncionales.app.util.Speaker
import com.palabrasfuncionales.app.util.TextNorm
import com.palabrasfuncionales.app.util.copyToClipboard
import com.palabrasfuncionales.app.util.toast
import com.palabrasfuncionales.app.vocab.VocabItem
import com.palabrasfuncionales.app.vocab.VocabRepository
import com.palabrasfuncionales.app.vocab.VocabWord
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Everyday words by theme (family, food, verbs…), with pronunciation and practice. */
class VocabularyFragment : Fragment() {
    private var _b: FragmentVocabularyBinding? = null
    private val b get() = _b!!
    private var speaker: Speaker? = null
    private var lang: Lang = Languages.dictionary.first()
    private var items: List<VocabItem> = emptyList()
    private var categories: List<String> = emptyList()
    private var category: String? = null
    private var saved: Map<Int, VocabWord> = emptyMap()
    private var job: Job? = null
    private val adapter = VocabAdapter(
        onClick = { row -> row.word?.let { w -> speak(w.text) } },
        onLongClick = { row ->
            val text = listOfNotNull(row.word?.text, row.word?.translit, row.item.es).joinToString(" — ")
            requireContext().copyToClipboard(text)
        },
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentVocabularyBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        speaker = Speaker(requireContext())
        b.list.adapter = adapter
        val langs = Languages.dictionary
        b.language.setAdapter(DropdownAdapter(requireContext(), langs.map { it.label }))
        b.language.setOnItemClickListener { _, _, pos, _ -> selectLanguage(langs[pos]) }
        b.query.doAfterTextChanged { refresh() }
        b.practice.setOnClickListener { practice() }
        b.downloadAll.setOnClickListener { translate(items, all = true) }

        viewLifecycleOwner.lifecycleScope.launch {
            items = VocabRepository.items(requireContext())
            categories = VocabRepository.categories(items)
            if (_b == null) return@launch
            b.category.setAdapter(DropdownAdapter(requireContext(), categories))
            b.category.setOnItemClickListener { _, _, pos, _ -> selectCategory(categories[pos]) }
            val savedCode = prefs().getString(KEY_LANG, null)
            selectLanguage(Languages.byCode(savedCode)?.takeIf { it in langs } ?: langs.first())
        }
    }

    private fun selectLanguage(l: Lang) {
        lang = l
        prefs().edit().putString(KEY_LANG, l.code).apply()
        b.language.setText(l.label, false)
        viewLifecycleOwner.lifecycleScope.launch {
            saved = VocabRepository.cached(requireContext(), l.code)
            if (_b == null) return@launch
            selectCategory(category ?: categories.first())
        }
    }

    private fun selectCategory(c: String) {
        category = c
        b.category.setText(c, false)
        refresh()
        val list = items.filter { it.category == c }
        if (list.any { it.id !in saved }) translate(list, all = false)
    }

    private fun translate(list: List<VocabItem>, all: Boolean) {
        job?.cancel()
        val ctx = requireContext()
        val code = lang.code
        b.progress.isVisible = true
        b.status.isVisible = true
        b.status.text = getString(if (all) R.string.vocab_downloading_all else R.string.vocab_translating, lang.english)
        job = viewLifecycleOwner.lifecycleScope.launch {
            try {
                saved = VocabRepository.translate(ctx, code, list) { codes ->
                    _b?.status?.text = TranslateErrors.downloading(ctx, codes)
                }
                if (_b == null || code != lang.code) return@launch
                b.status.text = if (all) getString(R.string.vocab_all_ready, lang.english) else ""
                b.status.isVisible = all
            } catch (e: TranslateException) {
                if (_b == null) return@launch
                b.status.text = getString(R.string.vocab_error, TranslateErrors.message(ctx, e))
                b.status.isVisible = true
            } finally {
                _b?.progress?.isVisible = false
                if (_b != null) refresh()
            }
        }
    }

    private fun refresh() {
        val binding = _b ?: return
        val q = TextNorm.fold(binding.query.text?.toString())
        val rows = if (q.isEmpty()) {
            items.filter { it.category == category }
        } else {
            items.filter {
                TextNorm.fold(it.es).contains(q) || TextNorm.fold(it.en).contains(q) ||
                    TextNorm.fold(saved[it.id]?.text).contains(q) || TextNorm.fold(saved[it.id]?.translit).contains(q)
            }
        }.map { VocabRow(it, saved[it.id]) }
        adapter.submitList(rows)
        binding.empty.isVisible = rows.isEmpty()
    }

    private fun practice() {
        val ctx = requireContext()
        val c = category ?: return
        val code = lang.code
        viewLifecycleOwner.lifecycleScope.launch {
            val pack = VocabRepository.pack(ctx, code)
            val lessons = Lessons.build(pack).filter { it.section == c }
            if (lessons.isEmpty()) {
                ctx.toast(R.string.vocab_not_ready)
                return@launch
            }
            val states = SrsStore(ctx, StudyActivity.vocabStoreCode(code)).all()
            val next = lessons.firstOrNull { l -> l.entries.any { it.cardKey !in states } } ?: lessons.first()
            startActivity(StudyActivity.intent(ctx, code, next.key, source = StudyActivity.SOURCE_VOCAB))
        }
    }

    private fun speak(text: String) {
        speaker?.speak(text, lang.ttsTag) { ok ->
            if (!ok) context?.let { it.toast(it.getString(R.string.no_voice, lang.english)) }
        }
    }

    private fun prefs() = requireContext().getSharedPreferences("ui", Context.MODE_PRIVATE)

    override fun onDestroyView() {
        job?.cancel()
        speaker?.shutdown()
        speaker = null
        b.list.adapter = null
        _b = null
        super.onDestroyView()
    }

    private companion object {
        const val KEY_LANG = "vocab_language"
    }
}

data class VocabRow(val item: VocabItem, val word: VocabWord?)

class VocabAdapter(
    private val onClick: (VocabRow) -> Unit,
    private val onLongClick: (VocabRow) -> Unit,
) : ListAdapter<VocabRow, VocabAdapter.Holder>(DIFF) {

    class Holder(val b: ItemVocabBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemVocabBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        with(holder.b) {
            word.text = row.word?.text ?: "…"
            translit.text = row.word?.translit
            translit.isVisible = !row.word?.translit.isNullOrBlank() && row.word?.translit != row.word?.text
            meaning.text = row.item.es
            english.text = row.item.enShown
            root.setOnClickListener { onClick(row) }
            root.setOnLongClickListener { onLongClick(row); true }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<VocabRow>() {
            override fun areItemsTheSame(a: VocabRow, b: VocabRow) = a.item.id == b.item.id
            override fun areContentsTheSame(a: VocabRow, b: VocabRow) = a == b
        }
    }
}
