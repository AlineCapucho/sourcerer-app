// Copyright 2017 Sourcerer Inc. All Rights Reserved.
// Author: Anatoly Kislov (anatoly@sourcerer.io)

package app.extractors

import app.model.CommitStats
import app.model.DiffFile

/**
 * Simplified extractor that detects languages by file extension
 * and computes line-level stats per language.
 */
class Extractor {
    companion object {
        val RESTRICTED_EXTS = listOf(".min.js")
        const val TYPE_LANGUAGE = 1
        const val TYPE_LIBRARY = 2

        val stringRegex = Regex("""(".+?"|'.+?')""")

        private val extensionToLanguage = mapOf(
            "kt" to "kotlin",
            "java" to "java",
            "py" to "python",
            "py3" to "python",
            "js" to "javascript",
            "jsx" to "javascript",
            "ts" to "typescript",
            "tsx" to "typescript",
            "rb" to "ruby",
            "rbw" to "ruby",
            "go" to "go",
            "rs" to "rust",
            "c" to "c",
            "h" to "c",
            "cpp" to "cpp",
            "cc" to "cpp",
            "cxx" to "cpp",
            "c++" to "cpp",
            "hpp" to "cpp",
            "hh" to "cpp",
            "hxx" to "cpp",
            "h++" to "cpp",
            "cs" to "csharp",
            "csx" to "csharp",
            "cake" to "csharp",
            "swift" to "swift",
            "m" to "objectivec",
            "mm" to "objectivec",
            "scala" to "scala",
            "sbt" to "scala",
            "clj" to "clojure",
            "cljs" to "clojure",
            "cljc" to "clojure",
            "hs" to "haskell",
            "lhs" to "haskell",
            "erl" to "erlang",
            "hrl" to "erlang",
            "ex" to "elixir",
            "exs" to "elixir",
            "php" to "php",
            "phtml" to "php",
            "php3" to "php",
            "php4" to "php",
            "php5" to "php",
            "pl" to "perl",
            "pm" to "perl",
            "r" to "r",
            "R" to "r",
            "lua" to "lua",
            "dart" to "dart",
            "groovy" to "groovy",
            "gradle" to "gradle",
            "sh" to "shell",
            "bash" to "shell",
            "zsh" to "shell",
            "css" to "css",
            "scss" to "css",
            "sass" to "css",
            "less" to "css",
            "html" to "html",
            "htm" to "html",
            "xml" to "xml",
            "json" to "json",
            "yaml" to "yaml",
            "yml" to "yaml",
            "sql" to "sql",
            "coffee" to "coffeescript",
            "litcoffee" to "coffeescript",
            "fs" to "fsharp",
            "fsx" to "fsharp",
            "fsi" to "fsharp",
            "vb" to "visualbasic",
            "vba" to "visualbasic",
            "d" to "d",
            "cr" to "crystal",
            "dm" to "dm",
            "vue" to "javascript",
            "svelte" to "javascript",
            "elm" to "elm",
            "jl" to "julia",
            "tex" to "tex",
            "ipynb" to "python",
            "sol" to "solidity",
            "ps1" to "powershell",
            "psm1" to "powershell",
            "psd1" to "powershell"
        )
    }

    /**
     * Extracts language stats from a list of diff files.
     * Filters out restricted extensions and detects language by extension.
     */
    fun extract(files: List<DiffFile>): List<CommitStats> {
        return files
            .filter { file -> !RESTRICTED_EXTS.contains(file.extension) }
            .mapNotNull { file -> analyzeFile(file) }
            .fold(mutableListOf()) { accStats, stats ->
                accStats.addAll(stats)
                accStats
            }
    }

    /**
     * Tokenizes a line of code for fact extraction (variable naming, etc.).
     */
    fun tokenize(line: String): List<String> {
        val newLine = stringRegex.replace(line, "")
        return newLine.split(' ', '[', ',', ';', '*', '\n', ')', '(',
            '[', ']', '}', '{', '+', '-', '=', '&', '$', '!', '.', '>',
            '<', '#', '@', ':', '?', ']')
            .filter {
                it.isNotBlank() && !it.contains('"') && !it.contains('\'') &&
                    it != "-" && it != "@"
            }
    }

    private fun analyzeFile(file: DiffFile): List<CommitStats>? {
        val language = detectLanguage(file.extension) ?: return null
        file.lang = language

        val linesAdded = file.getAllAdded().size
        val linesDeleted = file.getAllDeleted().size
        if (linesAdded == 0 && linesDeleted == 0) return null

        return listOf(CommitStats(
            numLinesAdded = linesAdded,
            numLinesDeleted = linesDeleted,
            type = TYPE_LANGUAGE,
            tech = language
        ))
    }

    private fun detectLanguage(extension: String): String? {
        return extensionToLanguage[extension]
    }
}
