package dev.nate.spideycall

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import audio.soniqo.speech.ModelDownloadWorker
import audio.soniqo.speech.ModelManager
import audio.soniqo.speech.ModelPrecision
import audio.soniqo.speech.SttBackend
import audio.soniqo.speech.SttModel
import audio.soniqo.speech.TtsModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach

/**
 * Downloads the STT/TTS model bundle via [ModelDownloadWorker] rather than
 * calling [ModelManager.ensureModels] directly. A plain download running in
 * an Activity-scoped coroutine measurably stalled and eventually died the
 * moment the app left the foreground (Doze/background-network throttling,
 * then the OS killing the backgrounded process outright) — observed
 * repeatedly on a real device mid-download. The worker runs as a foreground
 * service with a progress notification, which is exempt from both.
 *
 * Shared by [VoiceSession] (real calls) and the voice-test screen
 * (auditioning candidate voices) — both need the same bundle downloaded
 * exactly once; [ModelDownloadWorker]'s `ExistingWorkPolicy.KEEP` makes a
 * second concurrent call here a no-op that just observes the first.
 */
suspend fun downloadSpeechModels(context: Context, onStatus: (String) -> Unit): String {
    ModelDownloadWorker.enqueue(
        context,
        precision = ModelPrecision.INT8,
        sttModel = SttModel.PARAKEET_EOU,
        sttBackend = SttBackend.ONNX,
        ttsModel = TtsModel.KOKORO_SHORT_TURN,
    )
    val uniqueName = ModelDownloadWorker.uniqueName(
        precision = ModelPrecision.INT8,
        sttModel = SttModel.PARAKEET_EOU,
        sttBackend = SttBackend.ONNX,
        ttsModel = TtsModel.KOKORO_SHORT_TURN,
    )

    // ModelDownloadWorker only reports progress (KEY_TOTAL_BYTES etc.) for
    // files it actually has to fetch — downloadMissingModels skips cached,
    // valid files before ever calling onProgress for them. So when
    // everything's already cached, WorkInfo.progress never gets touched and
    // totalBytes stays 0 the whole run: that's exactly "nothing to
    // download", the signal for whether to bother showing status text at
    // all instead of flashing it on every call regardless of real work.
    val finished = WorkManager.getInstance(context)
        .getWorkInfosForUniqueWorkFlow(uniqueName)
        .mapNotNull { it.firstOrNull() }
        .onEach { info ->
            val totalBytes = info.progress.getLong(ModelDownloadWorker.KEY_TOTAL_BYTES, 0L)
            if (totalBytes > 0) {
                val pct = info.progress.getInt(ModelDownloadWorker.KEY_PERCENT, 0)
                onStatus("Downloading Spidey's voice… $pct%")
            }
        }
        .first { it.state.isFinished }

    return when (finished.state) {
        WorkInfo.State.SUCCEEDED ->
            finished.outputData.getString(ModelDownloadWorker.KEY_MODEL_DIR)
                ?: error("Model download succeeded without a model dir")
        else -> error(
            "Model download ${finished.state}: " +
                (finished.outputData.getString(ModelDownloadWorker.KEY_ERROR) ?: "no error detail"),
        )
    }
}
