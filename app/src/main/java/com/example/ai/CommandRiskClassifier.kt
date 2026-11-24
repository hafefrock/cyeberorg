package com.example.ai

import androidx.annotation.StringRes
import com.example.R

enum class CommandRiskLevel {
    LOW,
    MEDIUM,
    HIGH
}

enum class CommandRiskReason(@StringRes val messageRes: Int?) {
    SAFE(null),
    DESTRUCTIVE_RM_ROOT(R.string.risk_reason_rm_root),
    RAW_BLOCK_DEVICE_WRITE(R.string.risk_reason_dd_device),
    FILESYSTEM_FORMAT(R.string.risk_reason_mkfs),
    WORLD_WRITABLE_CHMOD(R.string.risk_reason_chmod_777),
    RECURSIVE_ROOT_PERMISSION(R.string.risk_reason_root_perm),
    PIPE_TO_SHELL(R.string.risk_reason_pipe_shell),
    FORK_BOMB(R.string.risk_reason_fork_bomb),
    DEVICE_REDIRECT(R.string.risk_reason_device_redirect),
    FIND_DELETE(R.string.risk_reason_find_delete),
    OVERSIZED_COMMAND(R.string.risk_reason_too_long),
    MULTILINE_OR_CONTROL_INSERT(R.string.risk_reason_multiline_or_control),
    PRIVILEGE_ESCALATION(R.string.risk_reason_privilege),
    PACKAGE_REMOVAL(R.string.risk_reason_pkg_remove),
    FILE_OVERWRITE_REDIRECT(R.string.risk_reason_overwrite)
}

data class CommandRiskAssessment(
    val level: CommandRiskLevel,
    val reason: CommandRiskReason = CommandRiskReason.SAFE
)

/**
 * Pure Kotlin shell command safety classifier using strictly linear O(N) normalization
 * and token/scanner passes (zero backtracking / zero ReDoS regexes).
 *
 * Commands longer than [MAX_CLASSIFY_LENGTH] characters are classified on a bounded window
 * and elevated to at least [CommandRiskLevel.MEDIUM].
 */
object CommandRiskClassifier {

    const val MAX_CLASSIFY_LENGTH = 4_000
    private const val MAX_SCAN_WINDOW = 65_536

    private val PARTITION_WRITE_TOOLS = setOf("fdisk", "gdisk", "parted", "sfdisk", "cfdisk")
    private val PARTED_WRITE_SUBCMDS = setOf(
        "mklabel", "mktable", "mkpart", "rm", "resizepart",
        "set", "toggle", "name", "disk_set", "disk_toggle"
    )
    private val FETCH_COMMANDS = setOf("curl", "wget", "fetch")
    private val SHELL_AND_ELEVATE_TARGETS = setOf("sh", "bash", "zsh", "dash", "ash", "ksh", "sudo", "su")
    private val SHELL_COMMANDS = setOf("sh", "bash", "zsh", "dash", "ash", "ksh")
    private val PRIVILEGE_WRAPPERS = setOf("sudo", "doas", "command", "env")
    private val PKG_MANAGERS = setOf("pkg", "apt", "apt-get", "aptitude", "dnf", "yum", "pacman")
    private val PKG_REMOVE_SUBCMDS = setOf("remove", "uninstall", "purge", "autoremove")
    private val SAFE_DEV_SINKS = setOf(
        "/dev/null", "/dev/zero", "/dev/stdout", "/dev/stderr",
        "/dev/tty", "/dev/urandom", "/dev/random"
    )
    private val SENSITIVE_DEV_PREFIXES = listOf(
        "/dev/block", "/dev/sd", "/dev/hd", "/dev/vd",
        "/dev/nvme", "/dev/mmcblk", "/dev/loop",
        "/dev/mapper/", "/dev/disk/"
    )

    fun classify(command: String): CommandRiskAssessment {
        val rawTrimmed = command.trim()
        if (rawTrimmed.isEmpty()) {
            return CommandRiskAssessment(CommandRiskLevel.LOW, CommandRiskReason.SAFE)
        }

        // Bound raw scan window so malicious multi-MB strings never cause allocation spikes.
        val rawBounded = if (rawTrimmed.length > MAX_SCAN_WINDOW) {
            rawTrimmed.substring(0, MAX_SCAN_WINDOW)
        } else {
            rawTrimmed
        }

        val normalized = normalizeCommand(rawBounded)
        if (normalized.isEmpty()) {
            return CommandRiskAssessment(CommandRiskLevel.LOW, CommandRiskReason.SAFE)
        }

        val exceedsLimit = rawTrimmed.length > MAX_CLASSIFY_LENGTH || normalized.length > MAX_CLASSIFY_LENGTH
        val analysisWindow = if (normalized.length > MAX_CLASSIFY_LENGTH) {
            normalized.substring(0, MAX_CLASSIFY_LENGTH)
        } else {
            normalized
        }

        if (isForkBombLinear(analysisWindow)) {
            return CommandRiskAssessment(CommandRiskLevel.HIGH, CommandRiskReason.FORK_BOMB)
        }

        val statements = splitLogicalStatements(analysisWindow)

        // Pass 1: HIGH risk rules across all statements
        for (stmt in statements) {
            if (isCommentOnly(stmt)) continue
            val pipelineStages = splitPipelineStages(stmt)
            val tokenizedStages = pipelineStages.map { tokenizeUnquoted(it) }

            if (hasPipeToShell(tokenizedStages, stmt)) {
                return CommandRiskAssessment(CommandRiskLevel.HIGH, CommandRiskReason.PIPE_TO_SHELL)
            }

            for (idx in tokenizedStages.indices) {
                val rawStage = pipelineStages[idx]
                val tokens = tokenizedStages[idx]
                if (tokens.isEmpty()) continue

                detectHighRiskStage(tokens, rawStage)?.let { return it }
            }
        }

        // Pass 2: Enforce length limit minimum MEDIUM before checking other MEDIUM rules
        if (exceedsLimit) {
            return CommandRiskAssessment(CommandRiskLevel.MEDIUM, CommandRiskReason.OVERSIZED_COMMAND)
        }

        // Pass 3: MEDIUM risk rules across all statements
        for (stmt in statements) {
            if (isCommentOnly(stmt)) continue
            val pipelineStages = splitPipelineStages(stmt)
            for (idx in pipelineStages.indices) {
                val rawStage = pipelineStages[idx]
                val tokens = tokenizeUnquoted(rawStage)
                if (tokens.isEmpty()) continue

                detectMediumRiskStage(tokens, rawStage)?.let { return it }
            }
        }

        return CommandRiskAssessment(CommandRiskLevel.LOW, CommandRiskReason.SAFE)
    }

    /**
     * Linear normalization:
     * 1. Converts `\r\n` and `\r` to `\n`.
     * 2. Joins POSIX line continuations (`\<newline>`).
     * 3. Collapses repeated horizontal whitespace (`' '`, `'\t'`) into single spaces.
     */
    internal fun normalizeCommand(input: String): String {
        val sb = StringBuilder(input.length)
        var i = 0
        var lastWasSpace = true
        while (i < input.length) {
            val ch = input[i]
            when (ch) {
                '\\' -> {
                    if (i + 1 < input.length && input[i + 1] == '\r') {
                        i += if (i + 2 < input.length && input[i + 2] == '\n') 3 else 2
                        continue
                    } else if (i + 1 < input.length && input[i + 1] == '\n') {
                        i += 2
                        continue
                    } else {
                        sb.append(ch)
                        lastWasSpace = false
                    }
                }
                '\r' -> {
                    if (sb.isNotEmpty() && sb[sb.length - 1] != '\n') {
                        sb.append('\n')
                    }
                    lastWasSpace = true
                    if (i + 1 < input.length && input[i + 1] == '\n') {
                        i += 2
                        continue
                    }
                }
                '\n' -> {
                    if (sb.isNotEmpty() && sb[sb.length - 1] != '\n') {
                        sb.append('\n')
                    }
                    lastWasSpace = true
                }
                ' ', '\t' -> {
                    if (!lastWasSpace) {
                        sb.append(' ')
                        lastWasSpace = true
                    }
                }
                else -> {
                    sb.append(ch)
                    lastWasSpace = false
                }
            }
            i++
        }
        return sb.toString().trim()
    }

    private fun isCommentOnly(stmt: String): Boolean {
        val t = stmt.trimStart()
        return t.isEmpty() || t.startsWith('#')
    }

    private fun detectHighRiskStage(tokens: List<String>, rawStage: String): CommandRiskAssessment? {
        val cmdIdx = findCommandIndex(tokens)
        if (cmdIdx >= tokens.size) return null
        val cmdName = tokens[cmdIdx].substringAfterLast('/').lowercase()
        val args = tokens.subList(cmdIdx + 1, tokens.size)

        if (cmdName == "rm" && isDestructiveRmArgs(args)) {
            return CommandRiskAssessment(CommandRiskLevel.HIGH, CommandRiskReason.DESTRUCTIVE_RM_ROOT)
        }
        if (cmdName == "dd" && isDdToBlockDevice(args)) {
            return CommandRiskAssessment(CommandRiskLevel.HIGH, CommandRiskReason.RAW_BLOCK_DEVICE_WRITE)
        }
        if (cmdName == "mkfs" || cmdName.startsWith("mkfs.") || cmdName == "wipefs" || cmdName == "mkswap") {
            return CommandRiskAssessment(CommandRiskLevel.HIGH, CommandRiskReason.FILESYSTEM_FORMAT)
        }
        if (cmdName in PARTITION_WRITE_TOOLS && isMutatingPartitionArgs(cmdName, args)) {
            return CommandRiskAssessment(CommandRiskLevel.HIGH, CommandRiskReason.FILESYSTEM_FORMAT)
        }
        if (cmdName == "chmod") {
            chmodRisk(args)?.let { return it }
        }
        if (cmdName == "chown" && isRecursiveRootChown(args)) {
            return CommandRiskAssessment(CommandRiskLevel.HIGH, CommandRiskReason.RECURSIVE_ROOT_PERMISSION)
        }
        if (cmdName == "find" && isFindDeleteOrRm(args)) {
            return CommandRiskAssessment(CommandRiskLevel.HIGH, CommandRiskReason.FIND_DELETE)
        }
        val isReadOnlyPartitionInspect =
            cmdName in PARTITION_WRITE_TOOLS && !isMutatingPartitionArgs(cmdName, args)
        if (hasBlockDeviceWrite(tokens, rawStage, allowDirectDeviceArgs = isReadOnlyPartitionInspect)) {
            return CommandRiskAssessment(CommandRiskLevel.HIGH, CommandRiskReason.DEVICE_REDIRECT)
        }
        return null
    }

    private fun detectMediumRiskStage(tokens: List<String>, rawStage: String): CommandRiskAssessment? {
        if (tokens.any { it.equals("sudo", ignoreCase = true) || it.equals("su", ignoreCase = true) || it.equals("doas", ignoreCase = true) }) {
            return CommandRiskAssessment(CommandRiskLevel.MEDIUM, CommandRiskReason.PRIVILEGE_ESCALATION)
        }
        val cmdIdx = findCommandIndex(tokens)
        if (cmdIdx < tokens.size) {
            val cmdName = tokens[cmdIdx].substringAfterLast('/').lowercase()
            val args = tokens.subList(cmdIdx + 1, tokens.size)
            if (cmdName in PKG_MANAGERS && args.any { it.lowercase() in PKG_REMOVE_SUBCMDS }) {
                return CommandRiskAssessment(CommandRiskLevel.MEDIUM, CommandRiskReason.PACKAGE_REMOVAL)
            }
            if (cmdName == "dpkg" && args.any { it == "-r" || it == "--remove" || it == "-P" || it == "--purge" }) {
                return CommandRiskAssessment(CommandRiskLevel.MEDIUM, CommandRiskReason.PACKAGE_REMOVAL)
            }
        }
        if (hasFileOverwriteRedirect(rawStage)) {
            return CommandRiskAssessment(CommandRiskLevel.MEDIUM, CommandRiskReason.FILE_OVERWRITE_REDIRECT)
        }
        return null
    }

    private fun findCommandIndex(tokens: List<String>): Int {
        var idx = 0
        while (idx < tokens.size) {
            val base = tokens[idx].substringAfterLast('/').lowercase()
            if (base in PRIVILEGE_WRAPPERS) {
                idx++
                // Skip simple wrapper options/env assignments like sudo -u root or env VAR=1
                while (idx < tokens.size) {
                    val t = tokens[idx]
                    if (t.startsWith("-")) {
                        idx++
                        // If previous option takes a single value in sudo/env (-u, -g, -C) we don't over-skip flags
                    } else if (base == "env" && t.contains('=')) {
                        idx++
                    } else {
                        break
                    }
                }
            } else if (isEnvAssignment(tokens[idx])) {
                idx++
            } else {
                break
            }
        }
        return idx
    }

    private fun isEnvAssignment(token: String): Boolean {
        val eq = token.indexOf('=')
        if (eq <= 0) return false
        for (i in 0 until eq) {
            val c = token[i]
            if (!(c.isLetterOrDigit() || c == '_')) return false
        }
        return true
    }

    /**
     * Strictly O(N) fork bomb detector without regex backtracking.
     */
    private fun isForkBombLinear(input: String): Boolean {
        // Check compact signature ignoring spaces
        val noSpace = buildString(input.length) {
            for (ch in input) {
                if (ch != ' ' && ch != '\t') append(ch)
            }
        }
        if (noSpace.contains(":(){:|:&};:")) return true

        // Linear scan for `ident(){ ... | ... & ... }`
        var i = 0
        while (i < input.length) {
            val openParen = input.indexOf("()", i)
            if (openParen == -1) break

            // Extract function identifier right before "()" (skipping optional spaces)
            var nameEnd = openParen
            while (nameEnd > 0 && (input[nameEnd - 1] == ' ' || input[nameEnd - 1] == '\t')) {
                nameEnd--
            }
            var nameStart = nameEnd
            while (nameStart > 0 && isForkIdentChar(input[nameStart - 1])) {
                nameStart--
            }
            val fnName = input.substring(nameStart, nameEnd)
            if (fnName.isEmpty()) {
                i = openParen + 2
                continue
            }

            // Advance past whitespace after "()" looking for '{'
            var pos = openParen + 2
            while (pos < input.length && (input[pos] == ' ' || input[pos] == '\t')) pos++
            if (pos >= input.length || input[pos] != '{') {
                i = openParen + 2
                continue
            }

            // Scan to matching '}' tracking brace depth (linear forward walk)
            val bodyStart = pos + 1
            var depth = 1
            var scan = bodyStart
            var hasPipe = false
            var hasAmp = false
            while (scan < input.length && depth > 0) {
                when (input[scan]) {
                    '{' -> depth++
                    '}' -> depth--
                    '|' -> if (depth >= 1) hasPipe = true
                    '&' -> if (depth >= 1) hasAmp = true
                }
                if (depth > 0) scan++
            }
            if (depth == 0 && hasPipe && hasAmp) {
                val body = input.substring(bodyStart, scan)
                // Self-recursion via pipe in background (`fnName | fnName &`)
                if (countOccurrences(body, fnName) >= 2) {
                    return true
                }
            }
            i = maxOf(openParen + 2, scan)
        }
        return false
    }

    private fun isForkIdentChar(ch: Char): Boolean =
        ch == ':' || ch == '_' || ch.isLetterOrDigit()

    private fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var idx = 0
        while (true) {
            val found = haystack.indexOf(needle, idx)
            if (found == -1) return count
            count++
            idx = found + needle.length
        }
    }

    private fun hasPipeToShell(tokenizedStages: List<List<String>>, rawStatement: String): Boolean {
        // 1. Direct pipeline check: stage i runs curl/wget/fetch -> stage j > i runs sh/bash/sudo/...
        var sawFetch = false
        for (tokens in tokenizedStages) {
            if (tokens.isEmpty()) continue
            val cmdIdx = findCommandIndex(tokens)
            val hasSudo = tokens.any { it.equals("sudo", ignoreCase = true) }
            val cmdName = if (cmdIdx < tokens.size) tokens[cmdIdx].substringAfterLast('/').lowercase() else ""
            if (sawFetch) {
                if (cmdName in SHELL_AND_ELEVATE_TARGETS || hasSudo) {
                    return true
                }
            }
            if (cmdName in FETCH_COMMANDS) {
                sawFetch = true
            }
        }

        // 2. Process substitution / command substitution: bash <(curl ...) or sh -c "$(curl ...)"
        val lower = rawStatement.lowercase()
        for (tokens in tokenizedStages) {
            if (tokens.isEmpty()) continue
            val cmdIdx = findCommandIndex(tokens)
            if (cmdIdx >= tokens.size) continue
            val cmdName = tokens[cmdIdx].substringAfterLast('/').lowercase()
            if (cmdName in SHELL_COMMANDS) {
                val rest = tokens.subList(cmdIdx + 1, tokens.size)
                if (rest.any { tok ->
                        val t = tok.lowercase()
                        FETCH_COMMANDS.any { f ->
                            t.contains("<($f") || t.contains("\$($f") || t.contains("`$f")
                        }
                    }
                ) {
                    return true
                }
            }
        }
        if ((lower.contains("sh -c") || lower.contains("bash -c")) &&
            (lower.contains("\$(curl") || lower.contains("\$(wget") || lower.contains("<(curl") || lower.contains("<(wget"))
        ) {
            return true
        }
        return false
    }

    private fun isDestructiveRmArgs(args: List<String>): Boolean {
        var hasRecursive = false
        var hasForce = false
        var noPreserveRoot = false
        var endOfFlags = false
        val targets = mutableListOf<String>()

        for (tok in args) {
            if (!endOfFlags && tok == "--") {
                endOfFlags = true
                continue
            }
            if (!endOfFlags && tok.startsWith("--")) {
                when (tok) {
                    "--recursive" -> hasRecursive = true
                    "--force" -> hasForce = true
                    "--no-preserve-root" -> {
                        hasRecursive = true
                        hasForce = true
                        noPreserveRoot = true
                    }
                }
            } else if (!endOfFlags && tok.startsWith("-") && tok.length > 1) {
                for (i in 1 until tok.length) {
                    when (tok[i]) {
                        'r', 'R' -> hasRecursive = true
                        'f' -> hasForce = true
                    }
                }
            } else {
                targets.add(tok)
            }
        }

        if (noPreserveRoot) return true
        if (!(hasRecursive && hasForce)) return false

        return targets.any { rawTarget ->
            val target = rawTarget.trimEnd('/') .ifEmpty { if (rawTarget.startsWith("/")) "/" else "" }
            target == "/" ||
                target == "~" ||
                target == "." ||
                target == ".." ||
                rawTarget == "./*" ||
                rawTarget == ".*" ||
                containsShellVariable(rawTarget) ||
                containsGlobWildcard(rawTarget)
        }
    }

    private fun containsShellVariable(token: String): Boolean {
        return token.contains('$')
    }

    private fun containsGlobWildcard(token: String): Boolean {
        for (i in token.indices) {
            val ch = token[i]
            if (ch == '\\' && i + 1 < token.length) continue
            if (i > 0 && token[i - 1] == '\\') continue
            if (ch == '*' || ch == '?') return true
        }
        return false
    }

    private fun isDdToBlockDevice(args: List<String>): Boolean {
        for (arg in args) {
            val lower = arg.lowercase()
            if (lower.startsWith("of=/dev/")) {
                if (lower !in SAFE_DEV_SINKS) {
                    return true
                }
            }
        }
        return false
    }

    private fun isMutatingPartitionArgs(cmdName: String, args: List<String>): Boolean {
        val hasListFlag = args.any { arg ->
            arg == "-l" || arg == "--list" ||
                (arg.startsWith("-") && !arg.startsWith("--") && 'l' in arg.substring(1))
        }
        val hasPartedWriteSubcmd = cmdName == "parted" && args.any { arg ->
            arg.lowercase() in PARTED_WRITE_SUBCMDS
        }
        return !hasListFlag || hasPartedWriteSubcmd
    }

    private fun chmodRisk(args: List<String>): CommandRiskAssessment? {
        var recursive = false
        val positional = mutableListOf<String>()
        for (arg in args) {
            when {
                arg == "--recursive" -> recursive = true
                arg.startsWith("-") && !arg.startsWith("--") && arg.length > 1 -> {
                    if ('R' in arg.substring(1)) recursive = true
                }
                !arg.startsWith("-") -> positional.add(arg)
            }
        }
        if (positional.isEmpty()) return null
        val mode = positional.first().lowercase()
        val targets = positional.drop(1)
        val isWorldWritableMode = mode == "777" || mode == "0777" || mode == "a+rwx" || mode == "ugo+rwx" || mode == "a=rwx"
        val targetsRoot = targets.any { isRootTargetPath(it) }

        if (recursive && isWorldWritableMode) {
            return CommandRiskAssessment(CommandRiskLevel.HIGH, CommandRiskReason.WORLD_WRITABLE_CHMOD)
        }
        if (isWorldWritableMode && targetsRoot) {
            return CommandRiskAssessment(CommandRiskLevel.HIGH, CommandRiskReason.WORLD_WRITABLE_CHMOD)
        }
        if (recursive && targetsRoot) {
            return CommandRiskAssessment(CommandRiskLevel.HIGH, CommandRiskReason.RECURSIVE_ROOT_PERMISSION)
        }
        return null
    }

    private fun isRecursiveRootChown(args: List<String>): Boolean {
        var recursive = false
        val positional = mutableListOf<String>()
        for (arg in args) {
            when {
                arg == "--recursive" -> recursive = true
                arg.startsWith("-") && !arg.startsWith("--") && arg.length > 1 -> {
                    if ('R' in arg.substring(1)) recursive = true
                }
                !arg.startsWith("-") -> positional.add(arg)
            }
        }
        if (!recursive || positional.size < 2) return false
        val targets = positional.drop(1)
        return targets.any { isRootTargetPath(it) }
    }

    private fun isRootTargetPath(raw: String): Boolean {
        val cleaned = raw.trim().trim('"', '\'')
        return cleaned == "/" || cleaned == "/*"
    }

    private fun isFindDeleteOrRm(args: List<String>): Boolean {
        for (i in args.indices) {
            val tok = args[i].lowercase()
            if (tok == "-delete") return true
            if ((tok == "-exec" || tok == "-execdir") && i + 1 < args.size) {
                val execTarget = args[i + 1].substringAfterLast('/').lowercase()
                if (execTarget == "rm") return true
            }
        }
        return false
    }

    private fun hasBlockDeviceWrite(
        tokens: List<String>,
        rawStage: String,
        allowDirectDeviceArgs: Boolean = false
    ): Boolean {
        // 1. Redirection targets outside quotes
        val redirectTargets = extractRedirectTargets(rawStage)
        if (redirectTargets.any { isSensitiveBlockDevicePath(it) }) {
            return true
        }
        if (allowDirectDeviceArgs) {
            return false
        }
        // 2. Direct token targets (`>/dev/sda`, `tee /dev/block/...`, `cp img /dev/sda`, etc.)
        for (tok in tokens) {
            val candidate = tok.substringAfter('>').trim()
            if (isSensitiveBlockDevicePath(candidate)) {
                return true
            }
        }
        return false
    }

    private fun isSensitiveBlockDevicePath(path: String): Boolean {
        val lower = path.trim('"', '\'').lowercase()
        return SENSITIVE_DEV_PREFIXES.any { lower.startsWith(it) }
    }

    private fun splitLogicalStatements(input: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var i = 0
        while (i < input.length) {
            val ch = input[i]
            if (ch == '\\' && quote != '\'' && i + 1 < input.length) {
                current.append(ch).append(input[i + 1])
                i += 2
                continue
            }
            if (quote != null) {
                if (ch == quote) quote = null
                current.append(ch)
                i++
                continue
            }
            if (ch == '\'' || ch == '"') {
                quote = ch
                current.append(ch)
                i++
                continue
            }
            val isDoubleOp = (ch == '&' || ch == '|') && i + 1 < input.length && input[i + 1] == ch
            if (ch == '\n' || ch == ';') {
                result.add(current.toString())
                current.clear()
                i++
            } else if (isDoubleOp) {
                result.add(current.toString())
                current.clear()
                i += 2
            } else {
                current.append(ch)
                i++
            }
        }
        if (current.isNotEmpty()) result.add(current.toString())
        return result
    }

    private fun splitPipelineStages(statement: String): List<String> {
        val stages = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var parenDepth = 0
        var i = 0
        while (i < statement.length) {
            val ch = statement[i]
            if (ch == '\\' && quote != '\'' && i + 1 < statement.length) {
                current.append(ch).append(statement[i + 1])
                i += 2
                continue
            }
            if (quote != null) {
                if (ch == quote) quote = null
                current.append(ch)
                i++
                continue
            }
            if (ch == '\'' || ch == '"') {
                quote = ch
                current.append(ch)
                i++
                continue
            }
            if (ch == '(') parenDepth++
            else if (ch == ')' && parenDepth > 0) parenDepth--

            if (ch == '|' && parenDepth == 0) {
                stages.add(current.toString())
                current.clear()
            } else {
                current.append(ch)
            }
            i++
        }
        if (current.isNotEmpty()) stages.add(current.toString())
        return stages
    }

    /**
     * Tokenizes [stage] linearly, unquoting quoted sections (so `'rm' "-rf" '/'` -> `rm`, `-rf`, `/`)
     * and collapsing backslash escapes inside/outside quotes.
     */
    private fun tokenizeUnquoted(stage: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var hasTokenContent = false
        var i = 0
        while (i < stage.length) {
            val ch = stage[i]
            when {
                quote != null -> {
                    if (ch == '\\' && quote == '"' && i + 1 < stage.length) {
                        current.append(stage[i + 1])
                        i += 2
                        continue
                    }
                    if (ch == quote) {
                        quote = null
                    } else {
                        current.append(ch)
                    }
                }
                ch == '\\' && i + 1 < stage.length -> {
                    // Preserve \$ so variable-targeted rm -rf \$HOME is still recognized,
                    // while `c\url` unescapes cleanly to `curl`.
                    val next = stage[i + 1]
                    if (next == '$' || next == '*' || next == '?') {
                        current.append('\\').append(next)
                    } else {
                        current.append(next)
                    }
                    hasTokenContent = true
                    i += 2
                    continue
                }
                ch == '\'' || ch == '"' -> {
                    quote = ch
                    hasTokenContent = true
                }
                ch == ' ' || ch == '\t' || ch == '\n' -> {
                    if (hasTokenContent || current.isNotEmpty()) {
                        tokens.add(current.toString())
                        current.clear()
                        hasTokenContent = false
                    }
                }
                else -> {
                    current.append(ch)
                    hasTokenContent = true
                }
            }
            i++
        }
        if (hasTokenContent || current.isNotEmpty()) {
            tokens.add(current.toString())
        }
        return tokens
    }

    private fun extractRedirectTargets(rawStage: String): List<String> {
        val unquoted = stripQuotedSections(rawStage)
        val targets = mutableListOf<String>()
        var i = 0
        while (i < unquoted.length) {
            if (unquoted[i] == '>') {
                var pos = i + 1
                if (pos < unquoted.length && unquoted[pos] == '>') pos++ // >>
                if (pos < unquoted.length && unquoted[pos] == '&') {
                    i = pos + 1
                    continue
                }
                while (pos < unquoted.length && (unquoted[pos] == ' ' || unquoted[pos] == '\t')) pos++
                val start = pos
                while (pos < unquoted.length && unquoted[pos] !in charArrayOf(' ', '\t', ';', '|', '&', '\n')) {
                    pos++
                }
                if (pos > start) {
                    targets.add(unquoted.substring(start, pos))
                }
                i = pos
            } else {
                i++
            }
        }
        return targets
    }

    private fun hasFileOverwriteRedirect(statement: String): Boolean {
        val unquoted = stripQuotedSections(statement)
        var i = 0
        while (i < unquoted.length) {
            if (unquoted[i] == '>') {
                val prev = if (i > 0) unquoted[i - 1] else ' '
                val next = if (i + 1 < unquoted.length) unquoted[i + 1] else ' '
                // Ignore append `>>` and dup `>&`
                if (next == '>' || next == '&') {
                    i += 2
                    continue
                }
                // Check fd prefix if present (`1>`, `2>`, `&>`); skip `>>` already handled
                val isSupportedFd = prev == ' ' || prev == '\t' || prev == '1' || prev == '2' || prev == '&' || i == 0
                if (isSupportedFd) {
                    var pos = i + 1
                    while (pos < unquoted.length && (unquoted[pos] == ' ' || unquoted[pos] == '\t')) pos++
                    if (pos < unquoted.length && unquoted[pos] == '&') {
                        i = pos + 1
                        continue
                    }
                    val start = pos
                    while (pos < unquoted.length && unquoted[pos] !in charArrayOf(' ', '\t', ';', '|', '&', '\n')) {
                        pos++
                    }
                    val target = unquoted.substring(start, pos).trim()
                    if (target.isNotEmpty() && target != "/dev/null") {
                        return true
                    }
                }
            }
            i++
        }
        return false
    }

    private fun stripQuotedSections(input: String): String {
        val sb = StringBuilder(input.length)
        var quote: Char? = null
        var escaped = false
        for (ch in input) {
            if (escaped) {
                escaped = false
                if (quote == null) sb.append(ch)
                continue
            }
            if (ch == '\\') {
                escaped = true
                if (quote == null) sb.append(ch)
                continue
            }
            if (quote != null) {
                if (ch == quote) quote = null
            } else if (ch == '\'' || ch == '"') {
                quote = ch
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }
}
