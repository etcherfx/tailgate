package io.github.etcherfx.tailgate.entry

import io.github.etcherfx.tailgate.core.TailgateLog
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/** [TailgateLog] on the game's log4j2, which every supported Minecraft ships. */
class Log4jLog private constructor(private val logger: Logger) : TailgateLog {
    override fun info(message: String) = logger.info(message)

    override fun warn(message: String, error: Throwable?) =
        if (error == null) logger.warn(message) else logger.warn(message, error)

    override fun error(message: String, error: Throwable?) =
        if (error == null) logger.error(message) else logger.error(message, error)

    companion object {
        fun create(): TailgateLog = try {
            Log4jLog(LogManager.getLogger("Tailgate"))
        } catch (e: LinkageError) {
            TailgateLog.Stderr
        }
    }
}
