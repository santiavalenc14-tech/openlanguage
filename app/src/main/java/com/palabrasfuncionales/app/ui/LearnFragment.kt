package com.palabrasfuncionales.app.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.palabrasfuncionales.app.R
import com.palabrasfuncionales.app.data.DictionaryRepository
import com.palabrasfuncionales.app.data.LanguagePack
import com.palabrasfuncionales.app.data.Languages
import com.palabrasfuncionales.app.databinding.FragmentLearnBinding
import com.palabrasfuncionales.app.learn.Lesson
import com.palabrasfuncionales.app.learn.Lessons
import com.palabrasfuncionales.app.learn.Srs
import com.palabrasfuncionales.app.learn.SrsStore
import com.palabrasfuncionales.app.learn.Streak
import com.palabrasfuncionales.app.learn.CardState
import com.palabrasfuncionales.app.data.Entry
import com.palabrasfuncionales.app.util.Speaker
import com.palabrasfuncionales.app.util.toast
import kotlin.random.Random
import kotlinx.coroutines.launch

/** Home of the learning mode: method, tip of the day, review and the lesson path. */
class LearnFragment : Fragment() {
    private var _b: FragmentLearnBinding? = null
    private val b get() = _b!!
    private var pack: LanguagePack? = null
    private var lessons: List<Lesson> = emptyList()
    private var nextLesson: Lesson? = null
    private var tipIndex = 0
    private var daily: Entry? = null
    private var speaker: Speaker? = null
    private val adapter = LessonAdapter { lesson -> open(lesson.key) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentLearnBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.lessons.adapter = adapter
        val langs = Languages.dictionary
        b.language.setAdapter(DropdownAdapter(requireContext(), langs.map { it.label }))
        b.language.setOnItemClickListener { _, _, pos, _ -> selectLanguage(langs[pos].code) }

        val tips = resources.getStringArray(R.array.learning_tips)
        tipIndex = (Srs.today() % tips.size).toInt()
        showTip()
        b.nextTip.setOnClickListener {
            tipIndex = (tipIndex + 1) % tips.size
            showTip()
        }
        b.continueLesson.setOnClickListener { nextLesson?.let { open(it.key) } }
        b.review.setOnClickListener { open(null) }
        speaker = Speaker(requireContext())
        b.dailyRandom.setOnClickListener { pickDaily(random = true) }
        b.dailyListen.setOnClickListener {
            val p = pack ?: return@setOnClickListener
            daily?.let { e ->
                speaker?.speak(e.example ?: e.word, p.lang.ttsTag) { ok ->
                    if (!ok) context?.let { it.toast(it.getString(R.string.no_voice, p.lang.english)) }
                }
            }
        }
        b.dailyAdd.setOnClickListener { addDailyToReview() }
        b.timedPlay.setOnClickListener {
            val p = pack ?: return@setOnClickListener
            startActivity(StudyActivity.intent(requireContext(), p.lang.code, null, timed = true))
        }

        val ui = prefs()
        val saved = ui.getString(KEY_LANG, null) ?: ui.getString("dictionary_language", null)
        selectLanguage(saved?.takeIf { c -> langs.any { it.code == c } } ?: langs.first().code)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) refresh()
    }

    private fun showTip() {
        val tips = resources.getStringArray(R.array.learning_tips)
        b.tip.text = tips[tipIndex % tips.size]
    }

    private fun selectLanguage(code: String) {
        prefs().edit().putString(KEY_LANG, code).apply()
        val lang = Languages.byCode(code) ?: return
        b.language.setText(lang.label, false)
        viewLifecycleOwner.lifecycleScope.launch {
            val p = DictionaryRepository.load(requireContext(), code)
            if (_b == null) return@launch
            pack = p
            lessons = Lessons.build(p)
            pickDaily(random = false)
            refresh()
        }
    }

    private fun refresh() {
        val binding = _b ?: return
        val p = pack ?: return
        val ctx = requireContext()
        val states = SrsStore(ctx, p.lang.code).all()
        val today = Srs.today()
        val due = p.entries.count { Srs.isDue(states[it.cardKey], today) }
        val seen = p.entries.count { states.containsKey(it.cardKey) }
        val mastered = p.entries.count { (states[it.cardKey]?.box ?: 0) >= Srs.MASTERED_BOX }

        nextLesson = lessons.firstOrNull { l -> l.entries.any { !states.containsKey(it.cardKey) } }
        binding.title.text = getString(R.string.learn_title, p.lang.english)
        binding.streak.text = getString(R.string.streak_days, Streak.current(ctx))
        binding.learned.text = getString(R.string.learned_count, seen, p.entries.size, mastered)
        binding.dueCount.text = resources.getQuantityString(R.plurals.due_today, due, due)
        binding.review.isEnabled = due > 0
        binding.review.text = getString(R.string.review_now, due)
        binding.continueLesson.isVisible = nextLesson != null
        nextLesson?.let {
            binding.continueLesson.text = getString(R.string.continue_lesson, it.section, it.part, it.parts)
        }
        binding.allDone.isVisible = nextLesson == null

        val best = requireContext().getSharedPreferences("stats", Context.MODE_PRIVATE)
            .getInt(StudyActivity.bestKey(StudyActivity.SOURCE_FUNC, p.lang.code), 0)
        binding.timedBest.text = getString(R.string.timed_body, best)
        binding.dailyAdd.isEnabled = daily?.let { !states.containsKey(it.cardKey) } ?: false

        adapter.submitList(lessons.map { l ->
            LessonRow(
                lesson = l,
                seen = l.entries.count { states.containsKey(it.cardKey) },
                mastered = l.entries.count { (states[it.cardKey]?.box ?: 0) >= Srs.MASTERED_BOX },
                isNext = l.key == nextLesson?.key,
            )
        })
    }

    /** Same word all day (seeded by date), or a random one on demand. Prefers entries with an example sentence. */
    private fun pickDaily(random: Boolean) {
        val p = pack ?: return
        val pool = p.entries.filter { !it.example.isNullOrBlank() || !it.exampleTranslit.isNullOrBlank() }
            .ifEmpty { p.entries }
        if (pool.isEmpty()) return
        val r = if (random) Random.Default else Random(Srs.today() * 31 + p.lang.code.hashCode())
        val e = pool[r.nextInt(pool.size)]
        daily = e
        b.dailyLabel.setText(if (random) R.string.random_title else R.string.daily_title)
        b.dailyWord.text = e.word
        b.dailyReading.text = e.readingAid
        b.dailyReading.isVisible = !e.readingAid.isNullOrBlank()
        b.dailyMeaning.text = e.spanish
        val ex = listOfNotNull(e.example, e.exampleTranslit?.let { if (e.example != null) "($it)" else it }).joinToString("  ")
        b.dailyExample.text = ex
        b.dailyExample.isVisible = ex.isNotBlank()
        b.dailyExampleEs.text = e.exampleSpanish
        b.dailyExampleEs.isVisible = !e.exampleSpanish.isNullOrBlank()
        val states = SrsStore(requireContext(), p.lang.code).all()
        b.dailyAdd.isEnabled = !states.containsKey(e.cardKey)
    }

    private fun addDailyToReview() {
        val p = pack ?: return
        val e = daily ?: return
        SrsStore(requireContext(), p.lang.code).putAll(mapOf(e.cardKey to CardState(1, Srs.today())))
        requireContext().toast(R.string.added_to_review)
        refresh()
    }

    private fun open(lessonKey: String?) {
        val p = pack ?: return
        startActivity(StudyActivity.intent(requireContext(), p.lang.code, lessonKey))
    }

    private fun prefs() = requireContext().getSharedPreferences("ui", Context.MODE_PRIVATE)

    override fun onDestroyView() {
        speaker?.shutdown()
        speaker = null
        b.lessons.adapter = null
        _b = null
        super.onDestroyView()
    }

    private companion object {
        const val KEY_LANG = "learn_language"
    }
}
