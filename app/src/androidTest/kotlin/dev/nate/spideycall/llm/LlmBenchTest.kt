package dev.nate.spideycall.llm

import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.benchmark
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * docs/PLAN.md §10 Phase A: standalone go/no-go numbers for the on-device LLM
 * via LiteRT-LM (the actively-developed successor to the deprecated MediaPipe
 * `tasks-genai` LlmInference API), on the real S26 Ultra target device —
 * no app wiring yet. Model is pushed ahead of time via `adb push` (too large
 * to bundle in the APK, same as the STT/TTS bundle) to
 * `/data/local/tmp/llm/gemma-4-E2B-it.litertlm` (originally Gemma3-1B-IT;
 * swapped 2026-08-22 — same LiteRT-LM API, see docs/STATE.md for why).
 *
 * Uses the library's own [benchmark] helper for init time / time-to-first-token
 * / prefill+decode tokens-per-second (it already drives a real
 * Engine+Conversation+sendMessage internally), and wraps it with
 * [Debug.getMemoryInfo] PSS deltas since [benchmark] doesn't report memory
 * itself.
 */
@RunWith(AndroidJUnit4::class)
class LlmBenchTest {

    @OptIn(ExperimentalApi::class)
    @Test
    fun cpu_loadTimeTokensPerSecondAndMemory() {
        val modelPath = "/data/local/tmp/llm/gemma-4-E2B-it.litertlm"
        require(File(modelPath).isFile) { "Push the model first: adb push gemma-4-E2B-it.litertlm $modelPath" }

        val pssBeforeKb = processPssKb()
        Log.i(TAG, "PSS before load: ${pssBeforeKb}kB")

        val info = runOnce(modelPath, "cpu-cold", Backend.CPU())
        val pssAfterKb = processPssKb()
        Log.i(TAG, "PSS after load+generate: ${pssAfterKb}kB (delta ${pssAfterKb - pssBeforeKb}kB)")

        // Second in-process init, model file now OS-page-cache warm: isolates
        // "one-time disk read" from "native engine init is just slow" before
        // deciding whether the engine must stay resident across the whole
        // app session rather than being recreated per call.
        val infoWarm = runOnce(modelPath, "cpu-warm", Backend.CPU())

        // GPU backend comparison: could have a very different init/decode
        // profile than CPU. Falls back gracefully (logged, not failed) if the
        // device/driver doesn't support it, since Backend.GPU() is best-effort.
        val infoGpu = try {
            runOnce(modelPath, "gpu", Backend.GPU())
        } catch (e: Throwable) {
            Log.w(TAG, "GPU backend failed, skipping", e)
            null
        }

        assertTrue("expected at least one decoded token", info.lastDecodeTokenCount > 0)
        assertTrue("expected a positive decode rate", info.lastDecodeTokensPerSecond > 0.0)
        assertTrue("expected at least one decoded token (warm)", infoWarm.lastDecodeTokenCount > 0)
        if (infoGpu != null) {
            assertTrue("expected at least one decoded token (gpu)", infoGpu.lastDecodeTokenCount > 0)
        }
    }

    /**
     * Root-caused the plain GPU attempt's failure above: `LITERT_CL` actually
     * delegates the *entire* model (1330/1330 nodes, per logcat) — the model
     * is fully GPU-compatible. It then fails writing a weight-cache file
     * next to the model path, because `EngineConfig.cacheDir` defaults to
     * the model's own directory, and `/data/local/tmp` — despite looking
     * world-writable via plain Unix permissions — is blocked for app-process
     * *writes* by Android's SELinux app-sandbox policy (reads are fine,
     * which is why CPU-backend loading has worked all along). Pointing
     * `cacheDir` at the app's own writable storage instead should fix it —
     * this is also what Phase B's real model bootstrap will naturally do
     * anyway (downloading into app-private storage, never `/data/local/tmp`),
     * so this dev-only path's limitation isn't a real production concern.
     */
    @OptIn(ExperimentalApi::class)
    @Test
    fun gpuBackendWorksWithAWritableCacheDir() {
        val modelPath = "/data/local/tmp/llm/gemma-4-E2B-it.litertlm"
        require(File(modelPath).isFile) { "Push the model first: adb push gemma-4-E2B-it.litertlm $modelPath" }

        val writableCacheDir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.absolutePath
        val info = benchmark(
            modelPath = modelPath,
            backend = Backend.GPU(),
            prefillTokens = 64,
            decodeTokens = 32,
            cacheDir = writableCacheDir,
            prompt = "Hi, I'm Spidey! What's your name?",
        )
        Log.i(
            TAG,
            "[gpu-writable-cache] initTimeInSecond=${info.initTimeInSecond} " +
                "timeToFirstTokenInSecond=${info.timeToFirstTokenInSecond} " +
                "lastPrefillTokensPerSecond=${info.lastPrefillTokensPerSecond} " +
                "lastDecodeTokensPerSecond=${info.lastDecodeTokensPerSecond}",
        )
        assertTrue("expected at least one decoded token (gpu)", info.lastDecodeTokenCount > 0)
    }

    @OptIn(ExperimentalApi::class)
    private fun runOnce(modelPath: String, label: String, backend: Backend) = benchmark(
        modelPath = modelPath,
        backend = backend,
        prefillTokens = 64,
        decodeTokens = 32,
        prompt = "Hi, I'm Spidey! What's your name?",
    ).also { info ->
        Log.i(
            TAG,
            "[$label] initTimeInSecond=${info.initTimeInSecond} " +
                "timeToFirstTokenInSecond=${info.timeToFirstTokenInSecond} " +
                "lastPrefillTokenCount=${info.lastPrefillTokenCount} " +
                "lastDecodeTokenCount=${info.lastDecodeTokenCount} " +
                "lastPrefillTokensPerSecond=${info.lastPrefillTokensPerSecond} " +
                "lastDecodeTokensPerSecond=${info.lastDecodeTokensPerSecond}",
        )
    }

    /**
     * `benchmark()` bundles Engine init + Conversation creation + a real
     * prefill/decode round trip, and creates a brand-new Engine every call —
     * it can't tell us whether the ~20s gap above bare `Engine.initialize()`
     * (~6.9s, see LlmEngine's own log) is a true one-time-per-Engine cost
     * (e.g. XNNPACK delegate/graph compilation, safe to pre-warm and reuse)
     * or a per-Conversation/per-turn cost (would recur every call even with
     * a warm Engine — a much worse finding). Isolates that directly: one
     * Engine, two Conversations, two messages each.
     */
    @Test
    fun engineInit_vs_firstConversationTurn_vs_secondConversationTurn() {
        val modelPath = "/data/local/tmp/llm/gemma-4-E2B-it.litertlm"
        require(File(modelPath).isFile) { "Push the model first: adb push gemma-4-E2B-it.litertlm $modelPath" }

        val engine = Engine(EngineConfig(modelPath = modelPath, backend = Backend.CPU()))
        try {
            timed("engine.initialize()") { engine.initialize() }

            val conversationA = timed("createConversation() #1") { engine.createConversation() }
            timed("conversationA.sendMessage() #1 (first ever turn)") {
                conversationA.sendMessage("Hi, I'm Spidey! What's your name?")
            }
            timed("conversationA.sendMessage() #2 (same conversation)") {
                conversationA.sendMessage("How are you today?")
            }
            conversationA.close()

            val conversationB = timed("createConversation() #2 (same engine)") { engine.createConversation() }
            timed("conversationB.sendMessage() #1 (new conversation, warm engine)") {
                conversationB.sendMessage("Hi, I'm Spidey! What's your name?")
            }
            conversationB.close()
        } finally {
            engine.close()
        }
    }

    /**
     * Same isolation as [engineInit_vs_firstConversationTurn_vs_secondConversationTurn]
     * but for GPU + a writable cacheDir — need to know if GPU's per-Engine
     * first-turn compile cost story matches CPU's (§10.11/§10.12: paid once
     * per Engine, not per Conversation) before redesigning [LlmEngine]'s
     * warm-up around it.
     */
    @Test
    fun gpuEngineInit_vs_firstConversationTurn_vs_secondConversationTurn() {
        val modelPath = "/data/local/tmp/llm/gemma-4-E2B-it.litertlm"
        require(File(modelPath).isFile) { "Push the model first: adb push gemma-4-E2B-it.litertlm $modelPath" }
        val writableCacheDir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.absolutePath

        val engine = Engine(
            EngineConfig(modelPath = modelPath, backend = Backend.GPU(), cacheDir = writableCacheDir),
        )
        try {
            timed("gpu engine.initialize()") { engine.initialize() }

            val conversationA = timed("gpu createConversation() #1") { engine.createConversation() }
            timed("gpu conversationA.sendMessage() #1 (first ever turn)") {
                conversationA.sendMessage("Hi, I'm Spidey! What's your name?")
            }
            timed("gpu conversationA.sendMessage() #2 (same conversation)") {
                conversationA.sendMessage("How are you today?")
            }
            conversationA.close()

            val conversationB = timed("gpu createConversation() #2 (same engine)") { engine.createConversation() }
            timed("gpu conversationB.sendMessage() #1 (new conversation, warm engine)") {
                conversationB.sendMessage("Hi, I'm Spidey! What's your name?")
            }
            conversationB.close()
        } finally {
            engine.close()
        }
    }

    private inline fun <T> timed(label: String, block: () -> T): T {
        val startedAt = System.currentTimeMillis()
        val result = block()
        Log.i(TAG, "$label took ${System.currentTimeMillis() - startedAt}ms")
        return result
    }

    private fun processPssKb(): Int {
        val memInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memInfo)
        return memInfo.totalPss
    }

    private companion object {
        const val TAG = "LlmBenchTest"
    }
}
