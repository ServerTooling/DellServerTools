package com.lilayam.dellservertools.core

/**
 * Builds the commands for "Paste script": the script is written to a file on the host over its own SSH exec
 * channel and then run in the interactive shell, so its output shows in the terminal.
 *
 * It isn't typed into the shell because the tty and readline mangle what they receive: tabs trigger
 * completion, very long lines are cut, and every line is echoed back.
 */
object ScriptUpload {
    const val DEFAULT_PATH = "/root/script.sh"

    /** Quotes [value] for a POSIX shell. */
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /** An absolute path on a single line; anything else would not name one file. */
    fun isValidPath(path: String): Boolean =
        path.startsWith("/") && !path.endsWith("/") && path.none { it == '\n' || it == '\r' || it == '\u0000' }

    /** Exec command that stores its stdin as [path], readable and runnable by its owner only. */
    fun saveCommand(path: String): String = "cat > ${quote(path)} && chmod 700 ${quote(path)}"

    /**
     * Shell line that runs the saved script. [args] are typed into the user's own shell, so they are passed
     * as written, like anything else they type there.
     */
    fun runLine(path: String, args: String): String =
        "bash ${quote(path)}" + args.trim().let { if (it.isEmpty()) "" else " $it" }

    /** Shell line that shows the saved file, for "Save only". */
    fun showLine(path: String): String = "ls -l ${quote(path)}"

    /** Unix line endings and a final newline: text pasted on a phone may carry CRLF, which bash rejects. */
    fun normalize(script: String): String {
        val text = script.replace("\r\n", "\n").replace('\r', '\n')
        return if (text.endsWith("\n")) text else text + "\n"
    }
}
