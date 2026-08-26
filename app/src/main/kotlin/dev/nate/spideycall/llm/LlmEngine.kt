package dev.nate.spideycall.llm

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Process-scoped LiteRT-LM [Engine] holder (docs/PLAN.md §10, Phase A/C/E).
 *
 * `LlmBenchTest` measured engine construction at ~27-31s on the S26 Ultra —
 * confirmed to be the native init itself and not a one-time disk read (a
 * warm re-run in the same process was barely faster) — so it must be built
 * at most once per process and never torn down on Activity recreation. A
 * plain Kotlin `object` is process-scoped for free, matching that lifetime;
 * [scope] is deliberately its own (not a caller's `lifecycleScope`) so a
 * warm-up in progress survives the Activity that started it being destroyed
 * and recreated (rotation, dark/light toggle, DeX/split-screen on this
 * device — none of which should waste the ~30s or leak the in-progress
 * native engine).
 *
 * [MODEL_PATH] is a dev-only stand-in: the model bundle isn't downloadable
 * yet (docs/PLAN.md §10 Phase B, not built) — for now it must be pushed
 * manually: `adb push gemma-4-E2B-it.litertlm /data/local/tmp/llm/`.
 *
 * `Engine.initialize()` alone is not enough to call this "ready": a staged
 * timing test (LlmBenchTest) found a real Conversation's *first-ever* turn
 * on a freshly-initialized engine costs an extra ~1-5s (likely one-time
 * delegate/graph compilation, backend-dependent — see below) on top of
 * `initialize()`'s own cost — but a second, brand-new
 * [com.google.ai.edge.litertlm.Conversation] created on that *same
 * already-used* engine has a fast first turn (matching steady-state decode
 * speed), so that cost is paid once per [Engine], not once per
 * [com.google.ai.edge.litertlm.Conversation]/call. [ensureReady] pre-pays it
 * with one throwaway conversation+message so every real call's first turn
 * is fast, not just its second.
 *
 * **GPU backend (2026-08-21 finding):** a plain `Backend.GPU()` attempt
 * originally failed outright — but root-caused via logcat, not assumed: the
 * `LITERT_CL` delegate actually claims the *entire* model (1330/1330 nodes)
 * and only fails one step later, writing a weight-cache file next to
 * [MODEL_PATH]. `EngineConfig.cacheDir` defaults to the model's own
 * directory when unset, and `/data/local/tmp` — despite looking
 * world-writable via plain Unix permissions — blocks app-process *writes*
 * under Android's SELinux app-sandbox policy (reads, which is all the CPU
 * path ever needed, are fine). Pointing `cacheDir` at the app's own
 * [Context.getCacheDir] fixes it, and GPU is then dramatically faster on
 * this device (SM8850/Adreno) — the cache-dir fix and the GPU-first,
 * CPU-fallback design below are unchanged by which model [MODEL_PATH]
 * points at; only the specific throughput numbers were Gemma3-1B-IT's
 * (measured 2026-08-21, now stale after the 2026-08-22 swap to Gemma 4
 * E2B — see docs/STATE.md for that model's freshly-measured numbers on
 * this device instead of repeating them here to rot again). [ensureReady]
 * tries GPU first, falling back to CPU (logged, not fatal) if the GPU
 * build-and-warm-up throws for any reason — e.g. a different device without
 * this one's GPU/driver support, or (found running on the AVD emulator,
 * 2026-08-22) a GPU backend whose `initialize()` succeeds but whose first
 * real inference throws because there's no OpenCL library at all — the
 * fallback covers the whole attempt, not just `initialize()`, precisely
 * because that failure mode surfaces one step later.
 */
object LlmEngine {
    private const val MODEL_PATH = "/data/local/tmp/llm/gemma-4-E2B-it.litertlm"
    private const val WARMUP_PROMPT = "Hi"
    private const val TAG = "LlmEngine"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    @Volatile private var engine: Engine? = null

    /**
     * Fire-and-forget: call as soon as the app opens to start warming before
     * a call is ever placed. Idempotent — safe to call repeatedly (e.g. once
     * per Activity recreation); a no-op once already warm or already
     * warming. [context] should be an application context (callers pass
     * `applicationContext`, not an Activity) since it's kept only as a
     * resolved cache-dir path, never held as a live `Context` reference.
     */
    fun warmUpAsync(context: Context) {
        val cacheDirPath = context.applicationContext.cacheDir.absolutePath
        scope.launch {
            try {
                ensureReady(cacheDirPath)
            } catch (e: Exception) {
                Log.w(TAG, "warm-up failed (non-fatal, not yet used by any caller)", e)
            }
        }
    }

    /**
     * Suspends until the engine is ready. Builds it on the first call (or
     * the first call to race here, if [warmUpAsync] is already in flight);
     * every other caller just suspends on the same in-progress build, then
     * returns immediately once it's done. [context]'s cache dir is only
     * used if this call is the one that actually builds the engine.
     */
    suspend fun ensureReady(context: Context): Engine =
        ensureReady(context.applicationContext.cacheDir.absolutePath)

    private suspend fun ensureReady(cacheDirPath: String): Engine = engine ?: mutex.withLock {
        engine ?: withContext(Dispatchers.Default) {
            Log.i(TAG, "cold-starting LiteRT-LM engine…")
            val startedAt = System.currentTimeMillis()

            // GPU can pass initialize() but only fail once real inference is
            // attempted (found on-device: a GPU-less emulator's Engine.initialize()
            // succeeds, then the warm-up's sendMessage() throws
            // "Can not find OpenCL library" — the same late-failure shape the
            // class doc already documents for the SELinux cacheDir case, just
            // a different root cause) — so the whole build-and-warm-up
            // attempt, not just initialize(), is what decides GPU vs CPU.
            val built = try {
                buildAndWarmUp(cacheDirPath, Backend.GPU()).also { Log.i(TAG, "GPU backend ready") }
            } catch (e: Exception) {
                Log.w(TAG, "GPU backend failed (init or first inference), falling back to CPU", e)
                buildAndWarmUp(cacheDirPath, Backend.CPU())
            }

            Log.i(TAG, "engine fully ready in ${System.currentTimeMillis() - startedAt}ms total")
            built
        }.also { engine = it }
    }

    // Pre-pays the one-time first-turn compile cost (see class doc) with a
    // throwaway conversation so every real call's first message is fast, not
    // just its second — and, per the doc above, doubles as the real test of
    // whether this backend actually works, not just whether initialize() did.
    private fun buildAndWarmUp(cacheDirPath: String, backend: Backend): Engine {
        val built = Engine(EngineConfig(modelPath = MODEL_PATH, backend = backend, cacheDir = cacheDirPath))
        try {
            built.initialize()
            built.createConversation().use { it.sendMessage(WARMUP_PROMPT) }
        } catch (e: Exception) {
            built.close()
            throw e
        }
        return built
    }

    fun isReady(): Boolean = engine != null
}
