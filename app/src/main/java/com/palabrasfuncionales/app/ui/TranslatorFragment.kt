package com.palabrasfuncionales.app.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.palabrasfuncionales.app.R
import com.palabrasfuncionales.app.data.Lang
import com.palabrasfuncionales.app.data.Languages
import com.palabrasfuncionales.app.databinding.FragmentTranslatorBinding
import com.palabrasfuncionales.app.net.GoogleTranslator
import com.palabrasfuncionales.app.net.TranslateException
import com.palabrasfuncionales.app.net.TranslationService
import com.palabrasfuncionales.app.util.Speaker
import com.palabrasfuncionales.app.util.copyToClipboard
import com.palabrasfuncionales.app.util.openUri
import com.palabrasfuncionales.app.util.toast
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/** A small Google-Translate-style screen: type or speak, translation appears as you type. */
class TranslatorFragment : Fragment() {
    private var _b: FragmentTranslatorBinding? = null
    private val b get() = _b!!
    private var speaker: Speaker? = null
    private var liveJob: Job? = null
    private var settingTextProgrammatically = false

    /** Source choices: null = detect automatically. */
    private val sources: List<Lang?> = listOf(null) + Languages.dictionary + Languages.spanish + Languages.english
    private val targets: List<Lang> = Languages.all
    private var source: Lang? = null
    private var target: Lang = Languages.english

    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val heard = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!heard.isNullOrBlank()) setInput(heard)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentTranslatorBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        speaker = Speaker(requireContext())
        val prefs = prefs()
        source = Languages.byCode(prefs.getString(KEY_SRC, null))
        target = Languages.byCode(prefs.getString(KEY_DST, "en")) ?: Languages.english

        val detect = getString(R.string.detect_language)
        b.source.setAdapter(DropdownAdapter(requireContext(), sources.map { it?.label ?: detect }))
        b.target.setAdapter(DropdownAdapter(requireContext(), targets.map { it.label }))
        b.source.setOnItemClickListener { _, _, pos, _ -> source = sources[pos]; saveLangs(); scheduleLive(0) }
        b.target.setOnItemClickListener { _, _, pos, _ -> target = targets[pos]; saveLangs(); scheduleLive(0) }
        showLangs()

        b.swap.setOnClickListener { swap() }
        b.inputLayout.counterMaxLength = GoogleTranslator.MAX_INPUT_CHARS
        b.input.doAfterTextChanged {
            if (settingTextProgrammatically) return@doAfterTextChanged
            b.inputLayout.error = null
            if (it.isNullOrBlank()) clearOutput() else scheduleLive(LIVE_DELAY_MS)
        }
        b.translate.setOnClickListener { translate(allowDownload = true) }
        b.mic.setOnClickListener { listen() }
        b.listenInput.setOnClickListener {
            val text = b.input.text?.toString().orEmpty()
            val lang = source ?: Languages.byCode(lastDetected)
            if (text.isNotBlank() && lang != null) speak(text, lang)
        }
        b.openWeb.setOnClickListener {
            val text = b.input.text?.toString().orEmpty()
            if (text.isNotBlank()) requireContext().openUri(GoogleTranslator.webUri(text, source?.code, target.code))
        }
        b.copyOutput.setOnClickListener {
            b.output.text.toString().takeIf { it.isNotBlank() }?.let { requireContext().copyToClipboard(it) }
        }
        b.listenOutput.setOnClickListener {
            val text = b.output.text.toString()
            if (text.isNotBlank()) speak(text, target)
        }
        pendingInput.getAndSet(null)?.let { setInput(it) }
    }

    private var lastDetected: String? = null

    fun setInput(text: String) {
        val binding = _b
        if (binding == null) {
            pendingInput.set(text)
            return
        }
        binding.input.setText(text.take(GoogleTranslator.MAX_INPUT_CHARS))
        binding.input.setSelection(binding.input.length())
        translate(allowDownload = true)
    }

    private fun swap() {
        val s = source ?: Languages.byCode(lastDetected)
        if (s == null) {
            requireContext().toast(R.string.swap_needs_source)
            return
        }
        val oldTarget = target
        target = s
        source = oldTarget
        val out = b.output.text.toString()
        settingTextProgrammatically = true
        if (out.isNotBlank()) b.input.setText(out)
        settingTextProgrammatically = false
        showLangs()
        saveLangs()
        translate(allowDownload = true)
    }

    private fun listen() {
        val lang = source ?: Languages.byCode(lastDetected)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.speak_now))
        lang?.let { intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, it.ttsTag) }
        try {
            speech.launch(intent)
        } catch (e: ActivityNotFoundException) {
            requireContext().toast(R.string.no_speech_input)
        }
    }

    private fun speak(text: String, lang: Lang) {
        speaker?.speak(text, lang.ttsTag) { ok ->
            if (!ok) context?.let { it.toast(it.getString(R.string.no_voice, lang.english)) }
        }
    }

    /** Translate-as-you-type: only uses language packs already on the phone. */
    private fun scheduleLive(delayMs: Long) {
        liveJob?.cancel()
        if (b.input.text.isNullOrBlank()) return
        liveJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(delayMs)
            translate(allowDownload = false)
        }
    }

    private fun showLangs() {
        b.source.setText(source?.label ?: getString(R.string.detect_language), false)
        b.target.setText(target.label, false)
    }

    private fun saveLangs() {
        prefs().edit().putString(KEY_SRC, source?.code).putString(KEY_DST, target.code).apply()
    }

    private fun clearOutput() {
        liveJob?.cancel()
        b.outputCard.isVisible = false
        b.output.text = ""
        b.status.isVisible = false
        b.inputTranslit.isVisible = false
    }

    private fun translate(allowDownload: Boolean) {
        val ctx = requireContext()
        val text = b.input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            if (allowDownload) b.inputLayout.error = getString(R.string.type_something)
            return
        }
        liveJob?.cancel()
        b.progress.isVisible = true
        liveJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = TranslationService.translate(ctx, text, source?.code, target.code, allowDownload) { codes ->
                    _b?.status?.apply {
                        this.text = TranslateErrors.downloading(ctx, codes)
                        isVisible = true
                    }
                }
                if (_b == null) return@launch
                b.status.isVisible = false
                b.output.text = result.text
                val outTr = result.translit?.takeIf { it.isNotBlank() && it != result.text }
                b.outputTranslit.text = outTr?.let { getString(if (result.translitApprox) R.string.pronunciation_approx else R.string.pronunciation, it) }
                b.outputTranslit.isVisible = outTr != null
                val inTr = result.sourceTranslit?.takeIf { it.isNotBlank() && it != text }
                b.inputTranslit.text = inTr?.let { getString(R.string.pronunciation, it) }
                b.inputTranslit.isVisible = inTr != null
                b.outputCard.isVisible = true
                lastDetected = result.detectedSource ?: lastDetected
                val detected = result.detectedSource?.let { Languages.byCode(it)?.english ?: it }
                b.detected.text = detected?.let { getString(R.string.detected_as, it) }
                b.detected.isVisible = source == null && detected != null
            } catch (e: TranslateException) {
                if (_b == null) return@launch
                if (e.kind == TranslateException.Kind.NEEDS_DOWNLOAD) {
                    // Live mode never downloads; tell the user one tap is needed.
                    b.status.text = getString(R.string.tap_translate_to_download)
                    b.status.isVisible = true
                } else if (e.kind == TranslateException.Kind.UNSUPPORTED) {
                    b.status.text = TranslateErrors.message(ctx, e)
                    b.status.isVisible = true
                } else if (allowDownload) {
                    b.status.isVisible = false
                    ctx.toast(TranslateErrors.message(ctx, e))
                }
            } finally {
                _b?.progress?.isVisible = false
            }
        }
    }

    private fun prefs() = requireContext().getSharedPreferences("ui", Context.MODE_PRIVATE)

    override fun onDestroyView() {
        liveJob?.cancel()
        speaker?.shutdown()
        speaker = null
        _b = null
        super.onDestroyView()
    }

    companion object {
        private const val KEY_SRC = "translator_source"
        private const val KEY_DST = "translator_target"
        private const val LIVE_DELAY_MS = 600L
        /** Text handed over from another app before this screen had a view. */
        val pendingInput = AtomicReference<String?>(null)
    }
}
