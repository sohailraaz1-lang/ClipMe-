// Minimal JNI bridge to llama.cpp (pinned in CMakeLists.txt). One prompt in, one answer out.
#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <algorithm>
#include "llama.h"

#define TAG "ClipMeLLM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

struct Handle {
    llama_model *model;
    int nctx;
    int threads;
};

static bool g_init = false;

static jbyteArray toBytes(JNIEnv *env, const std::string &s) {
    jbyteArray a = env->NewByteArray((jsize) s.size());
    env->SetByteArrayRegion(a, 0, (jsize) s.size(), (const jbyte *) s.data());
    return a;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_clipme_app_LlmEngine_nativeLoad(JNIEnv *env, jobject, jstring jpath, jint nctx, jint threads) {
    if (!g_init) { llama_backend_init(); g_init = true; }
    const char *p = env->GetStringUTFChars(jpath, nullptr);
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    llama_model *model = llama_model_load_from_file(p, mp);
    env->ReleaseStringUTFChars(jpath, p);
    if (!model) return 0;
    Handle *h = new Handle{model, (int) nctx, (int) threads};
    return (jlong) h;
}

extern "C" JNIEXPORT void JNICALL
Java_com_clipme_app_LlmEngine_nativeFree(JNIEnv *, jobject, jlong hp) {
    Handle *h = (Handle *) hp;
    if (!h) return;
    llama_model_free(h->model);
    delete h;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_clipme_app_LlmEngine_nativeGenerate(JNIEnv *env, jobject, jlong hp, jstring jprompt, jint maxTok) {
    Handle *h = (Handle *) hp;
    if (!h) return toBytes(env, "ERR:model not loaded");

    const char *pc = env->GetStringUTFChars(jprompt, nullptr);
    std::string prompt(pc);
    env->ReleaseStringUTFChars(jprompt, pc);

    const llama_vocab *vocab = llama_model_get_vocab(h->model);

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = h->nctx;
    cp.n_batch = 512;
    cp.n_threads = h->threads;
    cp.n_threads_batch = h->threads;
    llama_context *ctx = llama_init_from_model(h->model, cp);
    if (!ctx) return toBytes(env, "ERR:could not create context (low memory)");

    int n = -llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(), nullptr, 0, true, true);
    if (n <= 0) { llama_free(ctx); return toBytes(env, "ERR:could not read the prompt"); }
    std::vector<llama_token> toks(n);
    if (llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(), toks.data(), (int32_t) toks.size(), true, true) < 0) {
        llama_free(ctx);
        return toBytes(env, "ERR:tokenizer failed");
    }
    if (n + maxTok + 4 > h->nctx) {
        llama_free(ctx);
        return toBytes(env, "ERR:video text is too long for the model, try fewer clips");
    }

    for (int i = 0; i < n; i += 512) {
        int c = std::min(512, n - i);
        llama_batch b = llama_batch_get_one(toks.data() + i, c);
        if (llama_decode(ctx, b) != 0) { llama_free(ctx); return toBytes(env, "ERR:model failed while reading the prompt"); }
    }

    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    static const char *STOPS[] = {"<end_of_turn>", "<|im_end|>", "<|end|>", "<|eot_id|>", "</s>"};
    std::string out;
    for (int i = 0; i < maxTok; i++) {
        llama_token t = llama_sampler_sample(smpl, ctx, -1);
        if (llama_vocab_is_eog(vocab, t)) break;
        char buf[256];
        int len = llama_token_to_piece(vocab, t, buf, (int32_t) sizeof(buf), 0, true);
        if (len > 0) out.append(buf, (size_t) len);
        bool stop = false;
        for (const char *s : STOPS) {
            size_t sl = std::char_traits<char>::length(s);
            if (out.size() >= sl && out.compare(out.size() - sl, sl, s) == 0) {
                out.erase(out.size() - sl);
                stop = true;
                break;
            }
        }
        if (stop) break;
        llama_batch nb = llama_batch_get_one(&t, 1);
        if (llama_decode(ctx, nb) != 0) break;
    }

    llama_sampler_free(smpl);
    llama_free(ctx);
    LOGI("generated %zu bytes", out.size());
    return toBytes(env, out);
}
