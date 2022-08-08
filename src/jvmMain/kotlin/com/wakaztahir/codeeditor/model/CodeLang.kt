package com.wakaztahir.codeeditor.model

import com.wakaztahir.codeeditor.prettify.parser.Prettify

class CodeLang(val langProvider: Prettify.LangProvider?, val value: List<String>)