package com.lilayam.dellservertools.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScriptUploadTest {

    @Test
    fun `saves through cat and makes the file runnable by its owner`() {
        assertEquals("cat > '/root/setup.sh' && chmod 700 '/root/setup.sh'", ScriptUpload.saveCommand("/root/setup.sh"))
    }

    @Test
    fun `quotes paths with spaces and single quotes`() {
        assertEquals("cat > '/root/my '\\''x'\\'' file.sh' && chmod 700 '/root/my '\\''x'\\'' file.sh'",
            ScriptUpload.saveCommand("/root/my 'x' file.sh"))
        assertEquals("ls -l '/tmp/a b.sh'", ScriptUpload.showLine("/tmp/a b.sh"))
    }

    @Test
    fun `runs the script with bash and the arguments as typed`() {
        assertEquals("bash '/root/setup.sh' 300 vmbr4", ScriptUpload.runLine("/root/setup.sh", " 300 vmbr4 "))
        assertEquals("bash '/root/setup.sh'", ScriptUpload.runLine("/root/setup.sh", "  "))
    }

    @Test
    fun `accepts only an absolute path to one file`() {
        assertTrue(ScriptUpload.isValidPath("/root/setup.sh"))
        assertTrue(ScriptUpload.isValidPath(ScriptUpload.DEFAULT_PATH))
        assertFalse(ScriptUpload.isValidPath("setup.sh"))
        assertFalse(ScriptUpload.isValidPath(""))
        assertFalse(ScriptUpload.isValidPath("/root/"))
        assertFalse(ScriptUpload.isValidPath("/root/a\nrm -rf /"))
    }

    @Test
    fun `turns pasted CRLF line endings into LF and ends with a newline`() {
        assertEquals("#!/bin/bash\necho hi\n", ScriptUpload.normalize("#!/bin/bash\r\necho hi"))
        assertEquals("a\nb\n", ScriptUpload.normalize("a\rb\n"))
        assertEquals("\techo tab kept\n", ScriptUpload.normalize("\techo tab kept\n"))
    }
}
