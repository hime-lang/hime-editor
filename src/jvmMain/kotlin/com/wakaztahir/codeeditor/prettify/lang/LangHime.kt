package com.wakaztahir.codeeditor.prettify.lang

import com.wakaztahir.codeeditor.prettify.parser.Prettify
import com.wakaztahir.codeeditor.prettify.parser.StylePattern
import com.wakaztahir.codeeditor.utils.new

var fallthroughStylePatternBackups = ArrayList<StylePattern>()

class LangHime(s: String) : Lang() {
    companion object {
        val fileExtensions: List<String>
            get() = listOf("hime")
    }

    override var fallthroughStylePatterns = ArrayList<StylePattern>()
    override val shortcutStylePatterns = ArrayList<StylePattern>()

    init {
        shortcutStylePatterns.new("opn", Regex("^\\(+"), null, "(")
        shortcutStylePatterns.new("clo", Regex("^\\)+"), null, ")")
        shortcutStylePatterns.new(
            Prettify.PR_COMMENT,
            Regex("^;[^\r\n]*"),
            null,
            ";"
        )
        shortcutStylePatterns.new(
            Prettify.PR_PLAIN, Regex("^[\t\n\r \\xA0]+"), null, "\t\n\r " + 0xA0.toChar().toString()
        )
        shortcutStylePatterns.new(
            Prettify.PR_STRING,
            Regex("^\\\"(?:[^\\\"\\\\]|\\\\[\\s\\S])*(?:\\\"|$)"),
            null,
            "\""
        )
        fallthroughStylePatterns.new(
            Prettify.PR_LITERAL, Regex(
                "^[+\\-]?(?:[0#]x[0-9a-f]+|\\d+\\/\\d+|(?:\\.\\d+|\\d+(?:\\.\\d*)?)(?:[ed][+\\-]?\\d+)?)",
                RegexOption.IGNORE_CASE
            )
        )
        var code = s
        var index = 0
        var flag = 0
        var start = false
        if (code.isNotEmpty()) {
            while (code[index] != '(')
                ++index
            do {
                if (code[index] == '\"') {
                    var skip = false
                    while (true) {
                        ++index
                        if (index < code.length - 1 && code[index] == '\\') {
                            skip = false
                            continue
                        } else if (index >= code.length - 1 || code[index] == '\"') {
                            if (skip) {
                                skip = false
                                continue
                            } else
                                break
                        } else if (skip) {
                            skip = false
                            continue
                        }
                    }
                    ++index
                    continue
                }
                if (code[index] == '(')
                    ++flag
                else if (code[index] == ')')
                    --flag
                ++index
            } while (index < code.length)
            if (flag == 0) {
                val expressions = ArrayList<String>()
                code = preprocessor(s)
                index = 0
                var end = false
                loop@ while (index < code.length) {
                    flag = 0
                    val builder = StringBuilder()
                    while (code[index] != '(') {
                        if (index >= code.length - 1) {
                            end = true
                            break@loop
                        }
                        ++index
                    }
                    do {
                        if (code[index] == '\"') {
                            builder.append("\"")
                            val value = StringBuilder()
                            var skip = false
                            while (true) {
                                ++index
                                if (index < code.length - 1 && code[index] == '\\') {
                                    if (skip) {
                                        skip = false
                                        value.append("\\\\")
                                    } else
                                        skip = true
                                    continue
                                } else if (index >= code.length - 1 || code[index] == '\"') {
                                    if (skip) {
                                        skip = false
                                        value.append("\\\"")
                                        continue
                                    } else
                                        break
                                } else if (skip) {
                                    value.append("\\${code[index]}")
                                    skip = false
                                    continue
                                }
                                value.append(code[index])
                            }
                            builder.append(value.append("\"").toString())
                            ++index
                            continue
                        }
                        if (code[index] == '(')
                            ++flag
                        else if (code[index] == ')')
                            --flag
                        builder.append(code[index++])
                    } while (flag > 0)
                    expressions.add(builder.toString())
                }
                if (!end) {
                    for (expression in expressions) {
                        index = -1
                        while (++index < expression.length) {
                            when (expression[index]) {
                                '(' -> {
                                    start = true
                                    continue
                                }

                                ')' -> continue
                                ' ' -> continue
                            }
                            if (expression[index] == '-' && index < expression.length - 1 && Character.isDigit(
                                    expression[index + 1]
                                )
                            )
                                ++index
                            if (expression[index].isDigit()) {
                                while (true) {
                                    if (index >= expression.length - 1 || !expression[index].isDigit()) {
                                        --index
                                        break
                                    }
                                    ++index
                                }
                                if (expression[index + 1] != '.')
                                    continue
                                ++index
                                while (true) {
                                    ++index
                                    if (index >= expression.length - 1 || !expression[index].isDigit()) {
                                        --index
                                        break
                                    }
                                }
                                continue
                            }
                            if (expression[index] == '\"') {
                                var skip = false
                                while (true) {
                                    ++index
                                    if (index < expression.length - 1 && expression[index] == '\\') {
                                        skip = !skip
                                        continue
                                    } else if (index >= expression.length - 1 || expression[index] == '\"') {
                                        if (skip) {
                                            skip = false
                                            continue
                                        } else
                                            break
                                    }
                                    if (skip)
                                        skip = false
                                }
                                continue
                            }
                            if (expression[index] != ' ' && expression[index] != '(' && expression[index] != ')') {
                                val builder = StringBuilder()
                                while (true) {
                                    if (index >= expression.length - 1 || expression[index] == ' ' || expression[index] == ')') {
                                        --index
                                        break
                                    }
                                    builder.append(expression[index])
                                    ++index
                                }
                                fun transformation(ss: String): String {
                                    return when (ss) {
                                        "+" -> "\\+"
                                        "*" -> "\\*"
                                        "-" -> "\\-"
                                        else -> ss
                                    }
                                }
                                if (start) {
                                    fallthroughStylePatterns.new(
                                        Prettify.PR_KEYWORD,
                                        Regex(
                                            "^${transformation(builder.toString())}\\b",
                                            RegexOption.IGNORE_CASE
                                        ),
                                        null
                                    )
                                    start = false
                                }
                            }
                            continue
                        }
                    }
                    fallthroughStylePatternBackups = fallthroughStylePatterns
                } else {
                    fallthroughStylePatterns = fallthroughStylePatternBackups
                    if (fallthroughStylePatterns.isEmpty())
                        fallthroughStylePatterns.new(
                            Prettify.PR_LITERAL, Regex(
                                "^[+\\-]?(?:[0#]x[0-9a-f]+|\\d+\\/\\d+|(?:\\.\\d+|\\d+(?:\\.\\d*)?)(?:[ed][+\\-]?\\d+)?)",
                                RegexOption.IGNORE_CASE
                            )
                        )
                }
            } else {
                fallthroughStylePatterns = fallthroughStylePatternBackups
                if (fallthroughStylePatterns.isEmpty())
                    fallthroughStylePatterns.new(
                        Prettify.PR_LITERAL, Regex(
                            "^[+\\-]?(?:[0#]x[0-9a-f]+|\\d+\\/\\d+|(?:\\.\\d+|\\d+(?:\\.\\d*)?)(?:[ed][+\\-]?\\d+)?)",
                            RegexOption.IGNORE_CASE
                        )
                    )
            }
        }
    }

    override fun getFileExtensions(): List<String> {
        return fileExtensions
    }
}
