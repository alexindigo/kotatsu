package com.gezimos.katapult

import androidx.test.platform.app.InstrumentationRegistry

/** The outer UI scope owns foreign Settings navigation, not only its in-process Activities. */
internal class AndroidSettingsTaskFixture : AutoCloseable {
    private var closed = false

    init { removeSettingsTasks() }

    override fun close() {
        if (closed) return
        removeSettingsTasks()
        closed = true
    }

    companion object {
        private const val SETTINGS = "com.android.settings"
        internal data class Stack(val id: Int, val packages: Set<String>)

        /** API31 dumpsys uses RootTask headers and Hist entries for actual live task members. */
        internal fun settingsStacks(dump: String): List<Stack> {
            val stacks = linkedMapOf<Int, MutableSet<String>>()
            var current: Int? = null
            val root = Regex("^\\s*RootTask #(\\d+):")
            val activity = Regex("Hist #\\d+:.*ActivityRecord\\{[^}]* u\\d+ ([^ /]+)/")
            dump.lineSequence().forEach { line ->
                root.find(line)?.let {
                    current = it.groupValues[1].toInt()
                    stacks.getOrPut(current!!) { linkedSetOf() }
                }
                activity.find(line)?.let { match -> current?.let { stacks.getValue(it).add(match.groupValues[1]) } }
            }
            return stacks.map { (id, packages) -> Stack(id, packages) }.filter { SETTINGS in it.packages }
        }

        private fun shell(command: String) = InstrumentationRegistry.getInstrumentation().platformAutomation.boundedShell(command)
        private fun current() = settingsStacks(shell("dumpsys activity activities"))

        fun assertEmpty() {
            check(current().isEmpty()) { "Stale Android Settings tasks remain: ${current()}" }
        }

        private fun removeSettingsTasks() {
            withFixtureCleanup({
                fixtureAwait("Android Settings tasks survived their UI scope") { current().isEmpty() }
                assertEmpty()
            }) {
                fixtureCleanup(*current().map { stack -> {
                    check(stack.packages == setOf(SETTINGS)) {
                        "Cannot remove Settings stack containing unowned Activities: $stack"
                    }
                    // This documented API31 command removes the identified task stack, not the process.
                    shell("am stack remove ${stack.id}")
                    Unit
                } }.toTypedArray())
            }
        }
    }
}
