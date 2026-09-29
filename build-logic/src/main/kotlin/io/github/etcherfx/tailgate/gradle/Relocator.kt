package io.github.etcherfx.tailgate.gradle

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper

/** Package prefixes, in internal (slash) form. */
object Packages {
    const val ROOT = "io/github/etcherfx/tailgate/"
    const val UI = ROOT + "ui/"
    const val MIXIN = ROOT + "mixin/"
    const val KOTLIN = "kotlin/"
    const val SHADOW_KOTLIN = ROOT + "shadow/kotlin/"
}

/**
 * Rewrites class references by internal-name prefix, and records every name it sees so the
 * merge step can work out which Kotlin stdlib classes are reachable.
 */
class Relocator(private val prefixes: Map<String, String>) {
    /** Relocates [bytes] and returns the new bytes plus every class name the class references. */
    fun relocate(bytes: ByteArray): Pair<ByteArray, Set<String>> {
        val seen = HashSet<String>()
        val remapper = object : Remapper(Opcodes.ASM9) {
            override fun map(internalName: String): String {
                seen += internalName
                return mapName(internalName)
            }
        }
        val reader = ClassReader(bytes)
        val writer = ClassWriter(0)
        reader.accept(StringRelocator(ClassRemapper(writer, remapper), seen), 0)
        return writer.toByteArray() to seen
    }

    fun mapName(internalName: String): String {
        for ((from, to) in prefixes) {
            if (internalName.startsWith(from)) return to + internalName.substring(from.length)
        }
        return internalName
    }

    /**
     * Relocates string constants that name Kotlin classes (e.g. `kotlin.internal.jdk8.…`), which
     * the stdlib loads reflectively, and records them as references.
     */
    private inner class StringRelocator(next: ClassVisitor, private val seen: MutableSet<String>) : ClassVisitor(Opcodes.ASM9, next) {
        override fun visitMethod(access: Int, name: String?, descriptor: String?, signature: String?, exceptions: Array<out String>?): MethodVisitor? {
            val mv = super.visitMethod(access, name, descriptor, signature, exceptions) ?: return null
            return object : MethodVisitor(Opcodes.ASM9, mv) {
                override fun visitLdcInsn(value: Any?) {
                    if (value is String && CLASS_NAME.matches(value)) {
                        val internal = value.replace('.', '/')
                        seen += internal
                        super.visitLdcInsn(mapName(internal).replace('/', '.'))
                    } else {
                        super.visitLdcInsn(value)
                    }
                }
            }
        }
    }

    companion object {
        private val CLASS_NAME = Regex("kotlin(\\.[a-z0-9_]+)*\\.[A-Z][A-Za-z0-9_$]*")
    }
}
