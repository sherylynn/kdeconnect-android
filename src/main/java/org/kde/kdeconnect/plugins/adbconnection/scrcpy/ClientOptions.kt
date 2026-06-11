package org.kde.kdeconnect.plugins.adbconnection.scrcpy

import org.kde.kdeconnect.plugins.adbconnection.scrcpy.Shared.*

data class ClientOptions(
    var crop: String = "",
    var recordFilename: String = "",
    var videoCodecOptions: String = "",
    var audioCodecOptions: String = "",
    var videoEncoder: String = "",
    var audioEncoder: String = "",
    var cameraId: String = "",
    var cameraSize: String = "",
    var cameraAr: String = "",
    var cameraZoom: String = "",
    var cameraFps: UShort = 0u,
    var logLevel: LogLevel = LogLevel.INFO,
    var videoCodec: Codec = Codec.H264,
    var audioCodec: Codec = Codec.OPUS,
    var videoSource: VideoSource = VideoSource.DISPLAY,
    var audioSource: AudioSource = AudioSource.AUTO,
    var recordFormat: RecordFormat = RecordFormat.AUTO,
    var cameraFacing: CameraFacing = CameraFacing.ANY,
    var minSizeAlignment: UByte = 1u,
    var maxSize: UShort = 0u,
    var videoBitRate: Int = 0,
    var audioBitRate: Int = 0,
    var maxFps: String = "",
    var angle: String = "",
    var captureOrientation: Orientation = Orientation.ORIENT_0,
    var captureOrientationLock: OrientationLock = OrientationLock.UNLOCKED,
    var displayOrientation: Orientation = Orientation.ORIENT_0,
    var recordOrientation: Orientation = Orientation.ORIENT_0,
    var displayImePolicy: DisplayImePolicy = DisplayImePolicy.UNDEFINED,
    var displayId: Int = -1,
    var screenOffTimeout: Tick = Tick(-1),
    var showTouches: Boolean = false,
    var fullscreen: Boolean = false,
    var control: Boolean = true,
    var videoPlayback: Boolean = true,
    var audioPlayback: Boolean = true,
    var turnScreenOff: Boolean = false,
    var keyInjectMode: KeyInjectMode = KeyInjectMode.MIXED,
    var stayAwake: Boolean = false,
    var disableScreensaver: Boolean = false,
    var forwardKeyRepeat: Boolean = true,
    var legacyPaste: Boolean = false,
    var powerOffOnClose: Boolean = false,
    var clipboardAutosync: Boolean = true,
    var downsizeOnError: Boolean = true,
    var cleanup: Boolean = true,
    var powerOn: Boolean = true,
    var video: Boolean = true,
    var audio: Boolean = true,
    var requireAudio: Boolean = false,
    var killAdbOnClose: Boolean = false,
    var cameraHighSpeed: Boolean = false,
    var list: ListOptions = ListOptions.NULL,
    var mouseHover: Boolean = true,
    var audioDup: Boolean = false,
    var newDisplay: String = "",
    var startApp: String = "",
    var vdDestroyContent: Boolean = true,
    var vdSystemDecorations: Boolean = true,
    var cameraTorch: Boolean = false,
    var keepActive: Boolean = false,
    var flexDisplay: Boolean = false,
) {
    enum class KeyInjectMode(val string: String) {
        MIXED("mixed"),
        PREFER_TEXT("prefer_text"),
        RAW("raw");

        companion object {
            fun fromString(value: String) =
                entries.find { it.string.equals(value, ignoreCase = true) } ?: MIXED
        }
    }

    enum class RecordFormat(val string: String) {
        AUTO("auto"),
        MP4("mp4"),
        MKV("mkv"),
        M4A("m4a"),
        MKA("mka"),
        OPUS("opus"),
        AAC("aac"),
        FLAC("flac"),
        WAV("wav");

        fun isAudioOnly(): Boolean = when (this) {
            M4A, MKA, OPUS, AAC, FLAC, WAV -> true
            else -> false
        }

        companion object {
            fun fromString(value: String) =
                entries.find { it.string.equals(value, ignoreCase = true) } ?: AUTO

            fun guessFromFilename(filename: String): RecordFormat {
                val extension = filename.substringAfterLast('.', "").trim()
                return fromString(extension)
            }
        }
    }

    fun validate(): ClientOptions {
        if (!video) { videoPlayback = false; powerOn = false }
        if (!audio) { audioPlayback = false }
        if (video && !videoPlayback && recordFilename.isBlank()) video = false
        if (audio && !audioPlayback && recordFilename.isBlank()) audio = false
        if (!video && !audio && !control) throw IllegalArgumentException("nothing to do")
        if (!video) requireAudio = true
        if (audio && audioSource == AudioSource.AUTO) {
            audioSource = if (videoSource == VideoSource.DISPLAY) {
                if (audioDup) AudioSource.PLAYBACK else AudioSource.OUTPUT
            } else AudioSource.MIC
        }
        if (recordFormat != RecordFormat.AUTO && recordFilename.isBlank()) {
            throw IllegalArgumentException("Record format specified without recording")
        }
        if (recordFilename.isNotBlank()) {
            if (!video && !audio) throw IllegalArgumentException("Video and audio disabled, nothing to record")
            if (recordFormat == RecordFormat.AUTO) {
                recordFormat = RecordFormat.guessFromFilename(recordFilename)
                if (recordFormat == RecordFormat.AUTO) throw IllegalArgumentException("No format specified")
            }
            if (recordOrientation != Orientation.ORIENT_0 && recordOrientation.isMirror()) {
                throw IllegalArgumentException("Record orientation only supports rotation")
            }
            if (video && recordFormat.isAudioOnly()) throw IllegalArgumentException("Audio container does not support video stream")
        }
        if (!control) {
            if (turnScreenOff) throw IllegalArgumentException("Cannot request turn screen off if control disabled")
            if (stayAwake) throw IllegalArgumentException("Cannot request stay awake if control disabled")
            if (showTouches) throw IllegalArgumentException("Cannot request show touches if control disabled")
            if (powerOffOnClose) throw IllegalArgumentException("Cannot request power off on close if control disabled")
            if (startApp.isNotBlank()) throw IllegalArgumentException("Cannot start app if control disabled")
            if (keepActive) throw IllegalArgumentException("Cannot request keep active if control disabled")
        }
        return this
    }

    fun toServerParams(scid: UInt): ServerParams {
        return ServerParams(
            scid = scid,
            logLevel = logLevel,
            videoCodec = videoCodec,
            audioCodec = audioCodec,
            videoSource = videoSource,
            audioSource = audioSource,
            cameraFacing = cameraFacing,
            crop = crop,
            maxSize = maxSize,
            minSizeAlignment = minSizeAlignment,
            videoBitRate = videoBitRate,
            audioBitRate = audioBitRate,
            maxFps = maxFps,
            angle = angle,
            screenOffTimeout = screenOffTimeout,
            captureOrientation = captureOrientation,
            captureOrientationLock = captureOrientationLock,
            control = control,
            displayId = displayId,
            newDisplay = newDisplay,
            displayImePolicy = displayImePolicy,
            video = video,
            audio = audio,
            audioDup = audioDup,
            showTouches = showTouches,
            stayAwake = stayAwake,
            videoCodecOptions = videoCodecOptions,
            audioCodecOptions = audioCodecOptions,
            videoEncoder = videoEncoder,
            audioEncoder = audioEncoder,
            cameraId = cameraId,
            cameraSize = cameraSize,
            cameraAr = cameraAr,
            cameraZoom = cameraZoom,
            cameraFps = cameraFps,
            powerOffOnClose = powerOffOnClose,
            legacyPaste = legacyPaste,
            clipboardAutosync = clipboardAutosync,
            downsizeOnError = downsizeOnError,
            cleanUp = cleanup,
            powerOn = powerOn,
            cameraHighSpeed = cameraHighSpeed,
            cameraTorch = cameraTorch,
            vdDestroyContent = vdDestroyContent,
            vdSystemDecorations = vdSystemDecorations,
            keepActive = keepActive,
            flexDisplay = flexDisplay,
            list = list,
        )
    }
}
