package dev.rinstel.inkfeed

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import dev.rinstel.inkfeed.core.util.BeijingTime
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class InkFeedApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            CrashReporter.write(this, "uncaught:${thread.name}", error)
            previousHandler?.uncaughtException(thread, error)
        }
    }
}

internal object CrashReporter {
    fun write(context: Context, stage: String, error: Throwable): String {
        val report = buildString {
            appendLine("InkFeed startup/crash report")
            appendLine("time=${BeijingTime.dateTime(System.currentTimeMillis())}")
            appendLine("stage=$stage")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
            appendLine("supportedAbis=${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("process64Bit=${Process.is64Bit()}")
            appendLine("error=${error.javaClass.name}: ${error.message.orEmpty()}")
            appendLine()
            append(stackTrace(error))
        }
        runCatching {
            File(context.filesDir, FILE_NAME).writeText(report)
        }
        val external = context.getExternalFilesDir(null)?.let { File(it, FILE_NAME) }
        runCatching {
            external?.writeText(report)
        }
        return external?.absolutePath ?: File(context.filesDir, FILE_NAME).absolutePath
    }

    fun stackTrace(error: Throwable): String {
        val output = StringWriter()
        error.printStackTrace(PrintWriter(output))
        return output.toString()
    }

    private const val FILE_NAME = "crash-latest.txt"
}
