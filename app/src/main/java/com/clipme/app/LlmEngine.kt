package com.clipme.app

/**
 * On-device LLM (llama.cpp, GGUF, CPU). The native library is built by CMake from
 * app/src/main/cpp. If it is missing, `loaded` is false and the app falls back to
 * audio-based highlights instead of crashing.
 */
object LlmEngine {
    @JvmField
    val loaded: Boolean = try { System.loadLibrary("clipme_llm"); true } catch (t: Throwable) { false }

    external fun nativeLoad(path: String, nCtx: Int, threads: Int): Long
    external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int): ByteArray
    external fun nativeFree(handle: Long)

    fun format(template: String, sys: String, user: String): String = when (template) {
        "gemma" -> "<start_of_turn>user\n" + sys + "\n\n" + user + "<end_of_turn>\n<start_of_turn>model\n"
        "llama3" -> "<|start_header_id|>system<|end_header_id|>\n\n" + sys + "<|eot_id|>" +
                "<|start_header_id|>user<|end_header_id|>\n\n" + user + "<|eot_id|>" +
                "<|start_header_id|>assistant<|end_header_id|>\n\n"
        "phi3" -> "<|system|>\n" + sys + "<|end|>\n<|user|>\n" + user + "<|end|>\n<|assistant|>\n"
        else -> "<|im_start|>system\n" + sys + "<|im_end|>\n<|im_start|>user\n" + user + "<|im_end|>\n<|im_start|>assistant\n"
    }

    /** Loads the model, answers one prompt, frees the model (so RAM is free for rendering). */
    @Synchronized
    fun run(path: String, prompt: String, maxTokens: Int): String {
        if (!loaded) throw IllegalStateException("This build has no on-device LLM engine")
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val h = nativeLoad(path, 2048, threads)
        if (h == 0L) throw IllegalStateException("Could not load the model (low memory or damaged file). Try a smaller model.")
        try {
            val s = String(nativeGenerate(h, prompt, maxTokens), Charsets.UTF_8)
            if (s.startsWith("ERR:")) throw IllegalStateException(s.substring(4))
            return s.trim()
        } finally {
            nativeFree(h)
        }
    }
}
