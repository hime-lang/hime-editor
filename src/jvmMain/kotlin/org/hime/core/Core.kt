package org.hime.core

import ch.obermuhlner.math.big.BigDecimalMath
import org.hime.call
import org.hime.cast
import org.hime.lang.FuncType.BUILT_IN
import org.hime.lang.FuncType.STATIC
import org.hime.lang.*
import org.hime.lang.exception.HimeRuntimeException
import org.hime.parse.*
import org.hime.toToken
import java.io.File
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode
import java.nio.file.Files
import java.util.*
import kotlin.collections.ArrayList
import kotlin.system.exitProcess

fun initCore(env: Env) {
    env.symbol.table.putAll(
        mutableMapOf(
            "true" to env.himeTrue,
            "false" to env.himeFalse,
            "nil" to env.himeNil,
            "empty-stream" to env.himeEmptyStream,
            "def-structure" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    STATIC,
                    fun(ast: AstNode, symbol: SymbolTable): Token {
                        himeAssertRuntime(ast.size() > 1) { "not enough arguments." }
                        himeAssertRuntime(!symbol.table.containsKey(ast[0].tok.toString())) { "repeat binding ${ast[0].tok}." }
                        val key = ast[0].tok.toString()
                        val type = HimeType(key)
                        env.addType(type, arrayListOf(env.getType("structure")))
                        val types = ArrayList<HimeType>()
                        for (i in 1 until ast.size()) {
                            val name = ast[i].tok.toString()
                            himeAssertType(ast[i].tok, "id", env)
                            val typeEmbedded = cast<HimeTypeId>(ast[i].tok.type).type
                            types.add(typeEmbedded)
                            symbol.put("$key-$name", symbol.getFunction(env, "$key-$name").add(HimeFunction(
                                env,
                                BUILT_IN, fun(args: List<Token>, _: SymbolTable): Token {
                                    himeAssertRuntime(args[0].value is List<*>) { "structure is not list." }
                                    val result = cast<List<Token>>(args[0].value)[i - 1]
                                    himeAssertType(result, typeEmbedded, env)
                                    return result
                                },
                                listOf(type),
                                false
                            )).toToken(env))
                            symbol.put("set-$key-$name!", symbol.getFunction(env, "set-$key-$name!").add(HimeFunction(
                                env,
                                BUILT_IN, fun(args: List<Token>, _: SymbolTable): Token {
                                    himeAssertRuntime(args[0].value is ArrayList<*>) { "structure is not list." }
                                    cast<ArrayList<Token>>(args[0].value)[i - 1] = args[1]
                                    return env.himeNil
                                },
                                listOf(type, typeEmbedded),
                                false
                            )).toToken(env))
                        }
                        symbol.put("make-$key", symbol.getFunction(env, "make-$key").add(HimeFunction(
                            env,
                            BUILT_IN, fun(args: List<Token>, _: SymbolTable) = Token(type, ArrayList<Token>(args)),
                            types,
                            false
                        )).toToken(env))
                        return env.himeNil
                    })
            ).toToken(env),
            "def-symbol" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    STATIC,
                    fun(ast: AstNode, symbol: SymbolTable): Token {
                        himeAssertRuntime(ast.size() > 1) { "not enough arguments." }
                        himeAssertRuntime(!symbol.table.containsKey(ast[0].tok.toString())) { "repeat binding ${ast[0].tok}." }
                        himeAssertRuntime(ast[0].isNotEmpty()) { "Irregular definition." }
                        val parameters = ArrayList<String>()
                        for (i in 0 until ast[0].size())
                            parameters.add(ast[0][i].tok.toString())
                        val asts = ArrayList<AstNode>()
                        for (i in 1 until ast.size())
                            asts.add(ast[i].copy())
                        symbol.put(
                            ast[0].tok.toString(),
                            HimeFunctionScheduler(env).add(
                                HimeFunction(
                                    env,
                                    STATIC,
                                    fun(ast: AstNode, symbol: SymbolTable): Token {
                                        if (ast.type == AstType.FUNCTION) {
                                            var result = env.himeNil
                                            val newSymbol = symbol.createChild()
                                            for (node in ast.children)
                                                result = eval(env, AstNode(eval(env, node, newSymbol)), newSymbol)
                                            return result
                                        }
                                        val newAsts = ArrayList<AstNode>()
                                        for (node in asts) {
                                            val newAst = node.copy()

                                            // ???????????????
                                            fun rsc(ast: AstNode, id: String, value: AstNode) {
                                                if (env.isType(
                                                        ast.tok,
                                                        env.getType("id")
                                                    ) && ast.tok.toString() == id
                                                ) {
                                                    ast.tok = value.tok
                                                    ast.children = value.children
                                                }
                                                for (child in ast.children)
                                                    rsc(child, id, value)
                                            }
                                            himeAssertRuntime(ast.size() >= parameters.size) { "" }
                                            for (i in parameters.indices)
                                                rsc(newAst, parameters[i], ast[i])
                                            newAsts.add(newAst)
                                        }
                                        val newSymbol = symbol.createChild()
                                        var result = env.himeNil
                                        for (astNode in newAsts)
                                            result = eval(env, astNode.copy(), newSymbol)
                                        return result
                                    })
                            ).toToken(env)
                        )
                        return env.himeNil
                    })
            ).toToken(env),
            "cons-stream" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    STATIC,
                    fun(ast: AstNode, symbol: SymbolTable): Token {
                        himeAssertRuntime(ast.size() > 1) { "not enough arguments." }
                        // ???(cons-stream t1 t2)??????t1????????????
                        val t1 = eval(env, ast[0], symbol.createChild())
                        val asts = ArrayList<AstNode>()
                        // ???t1????????????????????????begin?????????asts???
                        for (i in 1 until ast.size())
                            asts.add(ast[i].copy())
                        // ?????????????????????(delay t2*)
                        return arrayListOf(
                            t1,
                            HimeFunctionScheduler(env).add(
                                structureHimeFunction(
                                    env,
                                    arrayListOf(),
                                    arrayListOf(),
                                    asts,
                                    symbol.createChild()
                                )
                            ).toToken(env)
                        ).toToken(env)
                    })
            ).toToken(env),
            "stream-car" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        // ??????(cons-stream t1 t2)???t1???????????????????????????????????????
                        return cast<List<Token>>(args[0].value)[0]
                    },
                    listOf(env.getType("list")),
                    false
                )
            ).toToken(env),
            "stream-cdr" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        val tokens = cast<List<Token>>(args[0].value)
                        val list = ArrayList<Token>()
                        // ???stream-cdr???????????????????????????1??????????????????????????????force?????????(cons-stream t1 t2)???????????????2??????????????????
                        for (i in 1 until tokens.size) {
                            himeAssertType(tokens[i], "function", env)
                            list.add(cast<HimeFunctionScheduler>(tokens[i].value).call(arrayListOf()))
                        }
                        // ??????????????????????????????????????????????????????????????????
                        if (list.size == 1)
                            return list[0].toToken(env)
                        // ????????????????????????
                        return list.toToken(env)
                    },
                    listOf(env.getType("list")),
                    false
                )
            ).toToken(env),
            "stream-map" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, symbol: SymbolTable): Token {
                        if (args[1] == env.himeEmptyStream)
                            return env.himeEmptyStream
                        himeAssertType(args[1], "list", env)
                        // ??????list?????????2???????????????????????????????????????????????????????????????(stream-map function list*)?????????list
                        var lists = ArrayList<List<Token>>()
                        // ?????????list?????????lists???
                        for (i in 1 until args.size)
                            lists.add(cast<List<Token>>(args[i].value))
                        val result = ArrayList<Token>()
                        // ????????????env.himeEmptyStream?????????
                        top@ while (true) {
                            // ????????????(stream-map f (stream-cons a b) (stream-cons c d))????????????(f a c)???
                            val parameters = ArrayList<Token>()
                            // ????????????????????????parameters
                            for (list in lists)
                                parameters.add(list[0])
                            // ?????????????????????????????????HimeFunction????????????????????????ast??????
                            val asts = env.himeAstEmpty.copy()
                            for (arg in parameters)
                                asts.add(AstNode(arg))
                            // ???parameters?????????????????????????????????????????????
                            result.add(cast<HimeFunctionScheduler>(args[0].value).call(asts, symbol))
                            val temp = ArrayList<List<Token>>()
                            // ????????????lists????????????delay
                            for (list in lists) {
                                val t = cast<HimeFunctionScheduler>(list[1].value).call(arrayListOf())
                                if (t == env.himeEmptyStream)
                                    break@top
                                temp.add(cast<List<Token>>(t.value))
                            }
                            lists = temp
                        }
                        return result.toToken(env)
                    },
                    listOf(env.getType("function"), env.getType("list")),
                    true
                )
            ).toToken(env),
            "stream-for-each" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, symbol: SymbolTable): Token {
                        if (args[1] == env.himeEmptyStream)
                            return env.himeEmptyStream
                        himeAssertType(args[1], "list", env)
                        // ??????list?????????2???????????????????????????????????????????????????????????????(stream-map function list*)?????????list
                        var lists = ArrayList<List<Token>>()
                        // ?????????list?????????lists???
                        for (i in 1 until args.size)
                            lists.add(cast<List<Token>>(args[i].value))
                        // ????????????env.himeEmptyStream?????????
                        top@ while (true) {
                            // ????????????(stream-map f (stream-cons a b) (stream-cons c d))????????????(f a c)???
                            val parameters = ArrayList<Token>()
                            // ????????????????????????parameters
                            for (list in lists)
                                parameters.add(list[0])
                            // ?????????????????????????????????HimeFunction????????????????????????ast??????
                            val asts = env.himeAstEmpty.copy()
                            for (arg in parameters)
                                asts.add(AstNode(arg))
                            // ???parameters?????????????????????????????????????????????
                            cast<HimeFunctionScheduler>(args[0].value).call(asts, symbol.createChild())
                            val temp = ArrayList<List<Token>>()
                            // ????????????lists????????????delay
                            for (list in lists) {
                                himeAssertType(list[1], "function", env)
                                val t = cast<HimeFunctionScheduler>(list[1].value).call(arrayListOf())
                                if (t == env.himeEmptyStream)
                                    break@top
                                temp.add(cast<List<Token>>(t.value))
                            }
                            lists = temp
                        }
                        return env.himeNil
                    },
                    listOf(env.getType("function"), env.getType("list")),
                    true
                )
            ).toToken(env),
            "stream-filter" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, symbol: SymbolTable): Token {
                        if (args[1] == env.himeEmptyStream)
                            return arrayListOf<Token>().toToken(env)
                        himeAssertType(args[1], "list", env)
                        val result = ArrayList<Token>()
                        var tokens = cast<List<Token>>(args[1].value)
                        while (tokens[0].value != env.himeEmptyStream) {
                            // ?????????????????????????????????HimeFunction????????????????????????ast??????
                            val asts = env.himeAstEmpty.copy()
                            asts.add(AstNode(tokens[0]))
                            val op = cast<HimeFunctionScheduler>(args[0].value).call(asts, symbol.createChild())
                            himeAssertType(op, "bool", env)
                            if (cast<Boolean>(op.value))
                                result.add(tokens[0])
                            val temp = cast<HimeFunctionScheduler>(tokens[1].value).call(arrayListOf())
                            if (temp == env.himeEmptyStream)
                                break
                            tokens = cast<List<Token>>(temp.value)
                        }
                        return result.toToken(env)
                    },
                    listOf(env.getType("function"), env.getType("list")),
                    false
                )
            ).toToken(env),
            "stream-ref" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        himeAssertRuntime(args.size > 1) { "not enough arguments." }
                        himeAssertType(args[0], "list", env)
                        himeAssertType(args[1], "int", env)
                        var temp = cast<List<Token>>(args[0].value)
                        var index = args[1].value.toString().toInt()
                        while ((index--) != 0) {
                            himeAssertType(temp[1], "function", env)
                            temp =
                                cast<List<Token>>(cast<HimeFunctionScheduler>(temp[1].value).call(arrayListOf()).value)
                        }
                        return temp[0]
                    },
                    listOf(env.getType("list"), env.getType("int")),
                    false
                )
            ).toToken(env),
            // (delay e) => (lambda () e)
            "delay" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    STATIC,
                    fun(ast: AstNode, symbol: SymbolTable): Token {
                        himeAssertRuntime(ast.isNotEmpty()) { "not enough arguments." }
                        val asts = ArrayList<AstNode>()
                        for (i in 0 until ast.size())
                            asts.add(ast[i].copy())
                        return HimeFunctionScheduler(env).add(
                            structureHimeFunction(
                                env,
                                arrayListOf(),
                                arrayListOf(),
                                asts,
                                symbol.createChild()
                            )
                        ).toToken(env)
                    })
            ).toToken(env),
            // (force d) => (d)
            "force" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        var result = env.himeNil
                        for (token in args) {
                            himeAssertType(token, "function", env)
                            result = cast<HimeFunctionScheduler>(token.value).call(arrayListOf())
                        }
                        return result
                    },
                    listOf(env.getType("any")),
                    true
                )
            ).toToken(env),
            // ??????????????????
            "let" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    STATIC,
                    fun(ast: AstNode, symbol: SymbolTable): Token {
                        himeAssertRuntime(ast.isNotEmpty()) { "not enough arguments." }
                        // ????????????????????????????????????
                        val newSymbol = symbol.createChild()
                        var result = env.himeNil
                        for (node in ast[0].children) {
                            if (node.tok.toString() == "apply") {
                                val parameters = ArrayList<String>()
                                val paramTypes = ArrayList<HimeType>()
                                for (i in 0 until ast[0].size()) {
                                    himeAssertType(ast[0][i].tok, "id", env)
                                    parameters.add(ast[0][i].tok.toString())
                                    paramTypes.add(cast<HimeTypeId>(ast[0][i].tok.type).type)
                                }
                                val asts = ArrayList<AstNode>()
                                for (i in 1 until node.size())
                                    asts.add(node[i].copy())
                                // ???????????????????????????????????????let??????????????????
                                newSymbol.put(
                                    node[0].tok.toString(),
                                    newSymbol.getFunction(env, node[0].tok.toString()).add(
                                        structureHimeFunction(
                                            env,
                                            parameters,
                                            paramTypes,
                                            asts,
                                            symbol.createChild()
                                        )
                                    ).toToken(env)
                                )
                            } else {
                                var value = env.himeNil
                                for (e in node.children)
                                    value = eval(env, e.copy(), symbol.createChild())
                                val type = cast<HimeTypeId>(node.tok.type).type
                                himeAssertType(value, type, env)
                                newSymbol.put(node.tok.toString(), value)
                            }
                        }
                        for (i in 1 until ast.size())
                            result = eval(env, ast[i].copy(), newSymbol.createChild())
                        return result
                    })
            ).toToken(env),
            "let*" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    STATIC,
                    fun(ast: AstNode, symbol: SymbolTable): Token {
                        himeAssertRuntime(ast.isNotEmpty()) { "not enough arguments." }
                        // ????????????????????????????????????
                        val newSymbol = symbol.createChild()
                        var result = env.himeNil
                        for (node in ast[0].children) {
                            if (node.tok.toString() == "apply") {
                                val parameters = ArrayList<String>()
                                val paramTypes = ArrayList<HimeType>()
                                for (i in 0 until ast[0].size()) {
                                    himeAssertType(ast[0][i].tok, "id", env)
                                    parameters.add(ast[0][i].tok.toString())
                                    paramTypes.add(cast<HimeTypeId>(ast[0][i].tok.type).type)
                                }
                                val asts = ArrayList<AstNode>()
                                for (i in 1 until node.size())
                                    asts.add(node[i].copy())
                                // ???????????????????????????????????????let*???????????????
                                newSymbol.put(
                                    node[0].tok.toString(),
                                    newSymbol.getFunction(env, node[0].tok.toString()).add(
                                        structureHimeFunction(
                                            env,
                                            parameters,
                                            paramTypes,
                                            asts,
                                            newSymbol.createChild()
                                        )
                                    ).toToken(env)
                                )
                            } else {
                                var value = env.himeNil
                                for (e in node.children)
                                    value = eval(env, e.copy(), newSymbol.createChild())
                                val type = cast<HimeTypeId>(node.tok.type).type
                                himeAssertType(value, type, env)
                                newSymbol.put(node.tok.toString(), value)
                            }
                        }
                        for (i in 1 until ast.size())
                            result = eval(env, ast[i].copy(), newSymbol.createChild())
                        return result
                    })
            ).toToken(env),
            // ???????????????
            "def" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    STATIC,
                    fun(ast: AstNode, symbol: SymbolTable): Token {
                        himeAssertRuntime(ast.size() > 1) { "not enough arguments." }
                        himeAssertRuntime(!symbol.table.containsKey(ast[0].tok.toString()) ||
                                !(ast[0].isEmpty() && ast[0].type != AstType.FUNCTION)) { "repeat binding ${ast[0].tok}." }
                        // ?????????(def key value)
                        if (ast[0].isEmpty() && ast[0].type != AstType.FUNCTION) {
                            var result = env.himeNil
                            for (i in 1 until ast.size())
                                result = eval(env, ast[i], symbol.createChild())
                            val type = cast<HimeTypeId>(ast[0].tok.type).type
                            himeAssertType(result, type, env)
                            symbol.put(ast[0].tok.toString(), result)
                        }
                        // ?????????(def (function-name p*) e)
                        else {
                            val parameters = ArrayList<String>()
                            val paramTypes = ArrayList<HimeType>()
                            for (i in 0 until ast[0].size()) {
                                himeAssertType(ast[0][i].tok, "id", env)
                                parameters.add(ast[0][i].tok.toString())
                                paramTypes.add(cast<HimeTypeId>(ast[0][i].tok.type).type)
                            }
                            val asts = ArrayList<AstNode>()
                            // ???ast????????????????????????asts???
                            for (i in 1 until ast.size())
                                asts.add(ast[i].copy())
                            symbol.put(
                                ast[0].tok.toString(),
                                symbol.getFunction(env, ast[0].tok.toString())
                                    .add(structureHimeFunction(env, parameters, paramTypes, asts, symbol.createChild()))
                                    .toToken(env)
                            )
                        }
                        return env.himeNil
                    })
            ).toToken(env),
            // ???????????????(??????)
            "def-variadic" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    STATIC,
                    fun(ast: AstNode, symbol: SymbolTable): Token {
                        himeAssertRuntime(ast.size() > 1) { "not enough arguments." }
                        himeAssertRuntime(!symbol.table.containsKey(ast[0].tok.toString()) ||
                                !(ast[0].isEmpty() && ast[0].type != AstType.FUNCTION)) { "repeat binding ${ast[0].tok}." }
                        himeAssertRuntime(ast[0].isNotEmpty() || ast[0].type != AstType.FUNCTION) { "format error." }
                        val parameters = ArrayList<String>()
                        val paramTypes = ArrayList<HimeType>()
                        for (i in 0 until ast[0].size()) {
                            himeAssertType(ast[0][i].tok, "id", env)
                            parameters.add(ast[0][i].tok.toString())
                            paramTypes.add(cast<HimeTypeId>(ast[0][i].tok.type).type)
                        }
                        val asts = ArrayList<AstNode>()
                        // ???ast????????????????????????asts???
                        for (i in 1 until ast.size())
                            asts.add(ast[i].copy())
                        symbol.put(
                            ast[0].tok.toString(),
                            symbol.getFunction(env, ast[0].tok.toString())
                                .add(variadicHimeFunction(env, parameters, paramTypes, asts, symbol.createChild()))
                                .toToken(env)
                        )
                        return env.himeNil
                    })
            ).toToken(env),
            // ????????????
            "undef" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    STATIC,
                    fun(ast: AstNode, symbol: SymbolTable): Token {
                        himeAssertRuntime(ast.isNotEmpty()) { "not enough arguments." }
                        himeAssertRuntime(symbol.contains(ast[0].tok.toString())) { "environment does not contain ${ast[0].tok} binding." }
                        // ????????????????????????
                        symbol.remove(ast[0].tok.toString())
                        return env.himeNil
                    })
            ).toToken(env),
            // ????????????
            "set!" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    STATIC,
                    fun(ast: AstNode, symbol: SymbolTable): Token {
                        himeAssertRuntime(ast.size() > 1) { "not enough arguments." }
                        himeAssertRuntime(symbol.contains(ast[0].tok.toString())) { "environment does not contain ${ast[0].tok} binding." }
                        // ?????????(set key value)
                        if (ast[0].isEmpty() && ast[0].type != AstType.FUNCTION) {
                            var result = env.himeNil
                            for (i in 1 until ast.size())
                                result = eval(env, ast[i], symbol.createChild())
                            val type = cast<HimeTypeId>(ast[0].tok.type).type
                            himeAssertType(result, type, env)
                            symbol.set(ast[0].tok.toString(), result)
                        } else {
                            // ?????????(set (function-name p*) e)
                            val parameters = ArrayList<String>()
                            val paramTypes = ArrayList<HimeType>()
                            for (i in 0 until ast[0].size()) {
                                himeAssertType(ast[0][i].tok, "id", env)
                                parameters.add(ast[0][i].tok.toString())
                                paramTypes.add(cast<HimeTypeId>(ast[0][i].tok.type).type)
                            }
                            val asts = ArrayList<AstNode>()
                            // ???ast????????????????????????asts???
                            for (i in 1 until ast.size())
                                asts.add(ast[i].copy())
                            symbol.set(
                                ast[0].tok.toString(),
                                symbol.getFunction(env, ast[0].tok.toString())
                                    .add(structureHimeFunction(env, parameters, paramTypes, asts, symbol))
                                    .toToken(env)
                            )
                        }
                        return env.himeNil
                    })
            ).toToken(env),
            "set-variadic!" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    STATIC,
                    fun(ast: AstNode, symbol: SymbolTable): Token {
                        himeAssertRuntime(ast.size() > 1) { "not enough arguments." }
                        himeAssertRuntime(symbol.contains(ast[0].tok.toString())) { "environment does not contain ${ast[0].tok} binding." }
                        himeAssertRuntime(ast[0].isNotEmpty() || ast[0].type != AstType.FUNCTION) { "format error." }
                        val parameters = ArrayList<String>()
                        val paramTypes = ArrayList<HimeType>()
                        for (i in 0 until ast[0].size()) {
                            himeAssertType(ast[0][i].tok, "id", env)
                            parameters.add(ast[0][i].tok.toString())
                            if (i != ast[0].size() - 1)
                                paramTypes.add(cast<HimeTypeId>(ast[0][i].tok.type).type)
                        }
                        val asts = ArrayList<AstNode>()
                        // ???ast????????????????????????asts???
                        for (i in 1 until ast.size())
                            asts.add(ast[i].copy())
                        symbol.set(
                            ast[0].tok.toString(),
                            symbol.getFunction(env, ast[0].tok.toString())
                                .add(variadicHimeFunction(env, parameters, paramTypes, asts, symbol.createChild()))
                                .toToken(env)
                        )
                        return env.himeNil
                    })
            ).toToken(env),
            "lambda" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    STATIC,
                    fun(ast: AstNode, symbol: SymbolTable): Token {
                        himeAssertRuntime(ast.size() > 1) { "not enough arguments." }
                        val parameters = ArrayList<String>()
                        val paramTypes = ArrayList<HimeType>()
                        // ?????????(lambda () e)
                        if (ast[0].tok != env.himeEmpty) {
                            himeAssertType(ast[0].tok, "id", env)
                            parameters.add(ast[0].tok.toString())
                            paramTypes.add(cast<HimeTypeId>(ast[0].tok.type).type)
                            for (i in 0 until ast[0].size()) {
                                himeAssertType(ast[0][i].tok, "id", env)
                                parameters.add(ast[0][i].tok.toString())
                                paramTypes.add(cast<HimeTypeId>(ast[0][i].tok.type).type)
                            }
                        }
                        val asts = ArrayList<AstNode>()
                        // ???ast????????????????????????asts???
                        for (i in 1 until ast.size())
                            asts.add(ast[i].copy())
                        return HimeFunctionScheduler(env).add(
                            structureHimeFunction(
                                env,
                                parameters,
                                paramTypes,
                                asts,
                                symbol.createChild()
                            )
                        ).toToken(env)
                    })
            ).toToken(env),
            "if" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    STATIC,
                    fun(ast: AstNode, symbol: SymbolTable): Token {
                        himeAssertRuntime(ast.size() > 1) { "not enough arguments." }
                        // ????????????????????????????????????
                        val newSymbol = symbol.createChild()
                        // ??????condition
                        val condition = eval(env, ast[0], newSymbol)
                        himeAssertType(condition, "bool", env)
                        // ????????????
                        if (cast<Boolean>(condition.value))
                            return eval(env, ast[1].copy(), newSymbol)
                        else if (ast.size() > 2)
                            return eval(env, ast[2].copy(), newSymbol)
                        return env.himeNil
                    })
            ).toToken(env),
            "cond" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    STATIC,
                    fun(ast: AstNode, symbol: SymbolTable): Token {
                        // ????????????????????????????????????
                        val newSymbol = symbol.createChild()
                        for (node in ast.children) {
                            // ????????????else????????????????????????
                            if (env.isType(node.tok, env.getType("id")) && node.tok.toString() == "else")
                                return eval(env, node[0].copy(), newSymbol)
                            else {
                                val result = eval(env, node[0].copy(), newSymbol)
                                himeAssertType(result, "bool", env)
                                if (cast<Boolean>(result.value)) {
                                    var r = env.himeNil
                                    for (index in 1 until node.size())
                                        r = eval(env, node[index].copy(), newSymbol)
                                    return r
                                }
                            }
                        }
                        return env.himeNil
                    })
            ).toToken(env),
            "switch" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    STATIC,
                    fun(ast: AstNode, symbol: SymbolTable): Token {
                        himeAssertRuntime(ast.size() > 1) { "not enough arguments." }
                        val newSymbol = symbol.createChild()
                        val op = eval(env, ast[0].copy(), newSymbol)
                        for (index in 1 until ast.size()) {
                            val node = ast[index]
                            if (env.isType(node.tok, env.getType("id")) && node.tok.toString() == "else")
                                return eval(env, node.copy(), newSymbol)
                            else
                                if (node.tok == op) {
                                    var r = env.himeNil
                                    for (i in 0 until node.size())
                                        r = eval(env, node[i].copy(), newSymbol)
                                    return r
                                }
                        }
                        return env.himeNil
                    })
            ).toToken(env),
            // ?????????????????????
            "begin" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    STATIC,
                    fun(ast: AstNode, symbol: SymbolTable): Token {
                        // ????????????????????????????????????
                        val newSymbol = symbol.createChild()
                        var result = env.himeNil
                        for (i in 0 until ast.size())
                            result = eval(env, ast[i].copy(), newSymbol)
                        return result
                    })
            ).toToken(env),
            "while" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    STATIC,
                    fun(ast: AstNode, symbol: SymbolTable): Token {
                        // ????????????????????????????????????
                        val newSymbol = symbol.createChild()
                        var result = env.himeNil
                        // ??????condition
                        var condition = eval(env, ast[0].copy(), newSymbol)
                        himeAssertType(condition, "bool", env)
                        while (cast<Boolean>(condition.value)) {
                            for (i in 1 until ast.size())
                                result = eval(env, ast[i].copy(), newSymbol)
                            // ????????????condition
                            condition = eval(env, ast[0].copy(), newSymbol)
                            himeAssertType(condition, "bool", env)
                        }
                        return result
                    })
            ).toToken(env),
            "new-type" to HimeFunctionScheduler(env,
                arrayListOf(
                    HimeFunction(
                        env,
                        BUILT_IN,
                        fun(_: List<Token>, _: SymbolTable): Token {
                            return HimeType().toToken(env)
                        },
                        0
                    ),
                    HimeFunction(
                        env,
                        BUILT_IN,
                        fun(args: List<Token>, _: SymbolTable): Token {
                            val scheduler = cast<HimeFunctionScheduler>(args[0].value)
                            himeAssertRuntime(scheduler.size == 1) {
                                "An overloaded function cannot be a type judge."
                            }
                            return HimeType(mode = HimeType.HimeTypeMode.JUDGE, judge = scheduler[0]).toToken(env)
                        },
                        listOf(env.getType("function")),
                        false
                    ),
                )
            ).toToken(env),
            "type-intersection" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        val types = args.map {cast<HimeType>(it.value)}
                        return HimeType(mode = HimeType.HimeTypeMode.JUDGE,
                            judge = HimeFunction(env, BUILT_IN,
                                fun(args: List<Token>, _: SymbolTable): Token {
                                    for (t in types) {
                                        val result = env.typeMatch(args[0], t)
                                        if (!result.matched())
                                            return false.toToken(env)
                                    }
                                    return true.toToken(env)
                                }, 1)).toToken(env)
                    },
                    listOf(env.getType("type")),
                    true,
                    env.getType("type")
                )
            ).toToken(env),
            "type-union" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        val types = args.map {cast<HimeType>(it.value)}
                        return HimeType(mode = HimeType.HimeTypeMode.JUDGE,
                            judge = HimeFunction(env, BUILT_IN,
                                fun(args: List<Token>, _: SymbolTable): Token {
                                    for (t in types) {
                                        val result = env.typeMatch(args[0], t)
                                        if (result.matched())
                                            return true.toToken(env)
                                    }
                                    return false.toToken(env)
                                }, 1)).toToken(env)
                    },
                    listOf(env.getType("type")),
                    true,
                    env.getType("type")
                )
            ).toToken(env),
            "type-complementary" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        val types = args.map {cast<HimeType>(it.value)}
                        return HimeType(mode = HimeType.HimeTypeMode.JUDGE,
                            judge = HimeFunction(env, BUILT_IN,
                                fun(args: List<Token>, _: SymbolTable): Token {
                                    val result = env.typeMatch(args[0], types[0])
                                    for (i in 1 until types.size) {
                                        if (!(result.matched() && !env.typeMatch(args[0], types[i]).matched()))
                                            return false.toToken(env)
                                    }
                                    return true.toToken(env)
                                }, 1)).toToken(env)
                    },
                    listOf(env.getType("type")),
                    true,
                    env.getType("type")
                )
            ).toToken(env),
            "type-wrong" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        val types = args.map {cast<HimeType>(it.value)}
                        return HimeType(mode = HimeType.HimeTypeMode.JUDGE,
                            judge = HimeFunction(env, BUILT_IN,
                                fun(args: List<Token>, _: SymbolTable): Token {
                                    for (t in types) {
                                        val result = env.typeMatch(args[0], t)
                                        if (result.matched())
                                            return false.toToken(env)
                                    }
                                    return true.toToken(env)
                                }, 1)).toToken(env)
                    },
                    listOf(env.getType("type")),
                    true,
                    env.getType("type")
                )
            ).toToken(env),
            "def-type" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        val type = cast<HimeType>(args[1].value)
                        env.addType(
                            HimeType(args[0].toString(), type.children, type.mode, type.judge, type.identifier),
                            args.subList(2, args.size).map {
                                himeAssertType(it, "type", env)
                                cast<HimeType>(it.value)
                            })
                        return env.himeNil
                    },
                    listOf(env.getType("id"), env.getType("type")),
                    true
                )
            ).toToken(env),
            "get-type" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        return args[0].type.toToken(env)
                    },
                    listOf(env.getType("any")),
                    false
                )
            ).toToken(env),
            "cast" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        return Token(cast<HimeType>(args[0].value), args[1].value)
                    },
                    listOf(env.getType("type"), env.getType("any")),
                    false
                )
            ).toToken(env),
            "is-type" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        return env.isType(args[0], cast<HimeType>(args[1].value)).toToken(env)
                    },
                    listOf(env.getType("any"), env.getType("type")),
                    true
                )
            ).toToken(env),
            "apply" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, symbol: SymbolTable): Token {
                        val parameters = ArrayList<Token>()
                        for (i in 1 until args.size)
                            parameters.add(args[i])
                        // ?????????????????????????????????HimeFunction????????????????????????ast??????
                        val asts = env.himeAstEmpty.copy()
                        for (arg in parameters)
                            asts.add(AstNode(arg))
                        return cast<HimeFunctionScheduler>(args[0].value).call(asts, symbol.createChild())
                    },
                    listOf(env.getType("function")),
                    true
                )
            ).toToken(env),
            "apply-list" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, symbol: SymbolTable): Token {
                        val parameters = cast<List<Token>>(args[1].value)
                        // ?????????????????????????????????HimeFunction????????????????????????ast??????
                        val asts = env.himeAstEmpty.copy()
                        for (arg in parameters)
                            asts.add(AstNode(arg))
                        return cast<HimeFunctionScheduler>(args[0].value).call(asts, symbol.createChild())
                    },
                    listOf(env.getType("function")),
                    true
                )
            ).toToken(env),
            "require" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, symbol: SymbolTable): Token {
                        val path = args[0].toString()
                        // ?????????????????????
                        if (module.containsKey(path)) {
                            module[path]!!(env)
                            return env.himeNil
                        }
                        // ???????????????????????????
                        val file = File(System.getProperty("user.dir") + "/" + path.replace(".", "/") + ".hime")
                        if (file.exists())
                            call(env, Files.readString(file.toPath()), symbol)
                        return env.himeNil
                    },
                    1
                )
            ).toToken(env),
            "read-bit" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(_: List<Token>, _: SymbolTable): Token {
                        return (env.io.`in`).read().toToken(env)
                    },
                    0
                )
            ).toToken(env),
            "read-line" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(_: List<Token>, _: SymbolTable): Token {
                        return Scanner(env.io.`in`).nextLine().toToken(env)
                    },
                    0
                )
            ).toToken(env),
            "read" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(_: List<Token>, _: SymbolTable): Token {
                        return Scanner(env.io.`in`).next().toToken(env)
                    },
                    0
                )
            ).toToken(env),
            "read-int" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(_: List<Token>, _: SymbolTable): Token {
                        return Scanner(env.io.`in`).nextBigInteger().toToken(env)
                    },
                    0
                )
            ).toToken(env),
            "read-real" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(_: List<Token>, _: SymbolTable): Token {
                        return Scanner(env.io.`in`).nextBigDecimal().toToken(env)
                    },
                    0
                )
            ).toToken(env),
            "read-bool" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(_: List<Token>, _: SymbolTable): Token {
                        return Scanner(env.io.`in`).nextBoolean().toToken(env)
                    },
                    0
                )
            ).toToken(env),
            "println" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        val builder = StringBuilder()
                        for (token in args)
                            builder.append(token.toString())
                        env.io.out.println(builder.toString())
                        return env.himeNil
                    })
            ).toToken(env),
            "print" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        val builder = StringBuilder()
                        for (token in args)
                            builder.append(token.toString())
                        env.io.out.print(builder.toString())
                        return env.himeNil
                    })
            ).toToken(env),
            "newline" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(_: List<Token>, _: SymbolTable): Token {
                        env.io.out.println()
                        return env.himeNil
                    },
                    0
                )
            ).toToken(env),
            "println-error" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        val builder = StringBuilder()
                        for (token in args)
                            builder.append(token.toString())
                        env.io.err.println(builder.toString())
                        return env.himeNil
                    })
            ).toToken(env),
            "print-error" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        val builder = StringBuilder()
                        for (token in args)
                            builder.append(token.toString())
                        env.io.err.print(builder.toString())
                        return env.himeNil
                    })
            ).toToken(env),
            "newline-error" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(_: List<Token>, _: SymbolTable): Token {
                        env.io.err.println()
                        return env.himeNil
                    },
                    0
                )
            ).toToken(env),
            "+" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        var num = args[0]
                        for (i in 1 until args.size) {
                            himeAssertType(args[i], "num", env)
                            num = env.himeAdd(num, args[i])
                        }
                        return num
                    },
                    listOf(env.getType("op")),
                    true
                )
            ).toToken(env),
            "-" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        var num = args[0]
                        if (args.size == 1)
                            return env.himeSub(
                                num,
                                env.himeMult(num, BigInteger.TWO.toToken(env))
                            )
                        for (i in 1 until args.size) {
                            himeAssertType(args[i], "num", env)
                            num = env.himeSub(num, args[i])
                        }
                        return num.toToken(env)
                    },
                    listOf(env.getType("op")),
                    true
                )
            ).toToken(env),
            "*" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        var num = args[0]
                        for (i in 1 until args.size) {
                            himeAssertType(args[i], "num", env)
                            num = env.himeMult(num, args[i])
                        }
                        return num
                    },
                    listOf(env.getType("op")),
                    true
                )
            ).toToken(env),
            "/" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        var num = args[0]
                        for (i in 1 until args.size) {
                            himeAssertType(args[i], "num", env)
                            num = env.himeDiv(num, args[i])
                        }
                        return num
                    },
                    listOf(env.getType("op")),
                    true
                )
            ).toToken(env),
            "and" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        himeAssertRuntime(args.isNotEmpty()) { "not enough arguments." }
                        for (arg in args) {
                            himeAssertType(arg, "bool", env)
                            if (!cast<Boolean>(arg.value))
                                return env.himeFalse
                        }
                        return env.himeTrue
                    },
                    listOf(env.getType("bool")),
                    true
                )
            ).toToken(env),
            "or" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        for (arg in args) {
                            himeAssertType(arg, "bool", env)
                            if (cast<Boolean>(arg.value))
                                return env.himeTrue
                        }
                        return env.himeFalse
                    },
                    listOf(env.getType("bool")),
                    true
                )
            ).toToken(env),
            "not" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        himeAssertRuntime(args.isNotEmpty()) { "not enough arguments." }
                        himeAssertType(args[0], "bool", env)
                        return if (cast<Boolean>(args[0].value)) env.himeFalse else env.himeTrue
                    },
                    listOf(env.getType("bool")),
                    false
                )
            ).toToken(env),
            "=" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        himeAssertRuntime(args.isNotEmpty()) { "not enough arguments." }
                        for (i in args.indices) {
                            himeAssertType(args[i], "eq", env)
                            for (j in args.indices) {
                                himeAssertType(args[j], "eq", env)
                                if (i != j && !cast<Boolean>(env.himeEq(args[i], args[j])))
                                    return env.himeFalse
                            }
                        }
                        return env.himeTrue
                    },
                    listOf(env.getType("eq")),
                    true
                )
            ).toToken(env),
            "/=" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        himeAssertRuntime(args.isNotEmpty()) { "not enough arguments." }
                        for (i in args.indices) {
                            himeAssertType(args[i], "ord", env)
                            for (j in args.indices) {
                                himeAssertType(args[j], "ord", env)
                                if (i != j && cast<Boolean>(env.himeEq(args[i], args[j])))
                                    return env.himeFalse
                            }
                        }
                        return env.himeTrue
                    },
                    listOf(env.getType("eq")),
                    true
                )
            ).toToken(env),
            ">" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        himeAssertRuntime(args.isNotEmpty()) { "not enough arguments." }
                        var token = args[0]
                        for (index in 1 until args.size) {
                            himeAssertType(args[index], "ord", env)
                            if (env.himeLessOrEq(token, args[index]))
                                return env.himeFalse
                            token = args[index]
                        }
                        return env.himeTrue
                    },
                    listOf(env.getType("ord")),
                    true
                )
            ).toToken(env),
            "<" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        himeAssertRuntime(args.isNotEmpty()) { "not enough arguments." }
                        var token = args[0]
                        for (index in 1 until args.size) {
                            himeAssertType(args[index], "ord", env)
                            if (env.himeGreaterOrEq(token, args[index]))
                                return env.himeFalse
                            token = args[index]
                        }
                        return env.himeTrue
                    },
                    listOf(env.getType("ord")),
                    true
                )
            ).toToken(env),
            ">=" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        himeAssertRuntime(args.isNotEmpty()) { "not enough arguments." }
                        var token = args[0]
                        for (index in 1 until args.size) {
                            himeAssertType(args[index], "ord", env)
                            if (env.himeLess(token, args[index]))
                                return env.himeFalse
                            token = args[index]
                        }
                        return env.himeTrue
                    },
                    listOf(env.getType("ord")),
                    true
                )
            ).toToken(env),
            "<=" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        himeAssertRuntime(args.isNotEmpty()) { "not enough arguments." }
                        var token = args[0]
                        for (index in 1 until args.size) {
                            himeAssertType(args[index], "ord", env)
                            if (env.himeGreater(token, args[index]))
                                return env.himeFalse
                            token = args[index]
                        }
                        return env.himeTrue
                    },
                    listOf(env.getType("ord")),
                    true
                )
            ).toToken(env),
            "random" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        himeAssertType(args[0], "num", env)
                        if (args.size > 1)
                            himeAssertType(args[1], "num", env)
                        val start = if (args.size == 1) BigInteger.ZERO else BigInteger(args[0].toString())
                        val end =
                            if (args.size == 1) BigInteger(args[0].toString()) else BigInteger(args[1].toString())
                        val rand = Random()
                        val scale = end.toString().length
                        var generated = ""
                        for (i in 0 until end.toString().length)
                            generated += rand.nextInt(10)
                        val inputRangeStart = BigDecimal("0").setScale(scale, RoundingMode.FLOOR)
                        val inputRangeEnd =
                            BigDecimal(String.format("%0" + end.toString().length + "d", 0).replace('0', '9')).setScale(
                                scale,
                                RoundingMode.FLOOR
                            )
                        val outputRangeStart = BigDecimal(start).setScale(scale, RoundingMode.FLOOR)
                        val outputRangeEnd = BigDecimal(end).add(BigDecimal("1"))
                            .setScale(scale, RoundingMode.FLOOR)
                        val bd1 =
                            BigDecimal(BigInteger(generated)).setScale(scale, RoundingMode.FLOOR)
                                .subtract(inputRangeStart)
                        val bd2 = inputRangeEnd.subtract(inputRangeStart)
                        val bd3 = bd1.divide(bd2, RoundingMode.FLOOR)
                        val bd4 = outputRangeEnd.subtract(outputRangeStart)
                        val bd5 = bd3.multiply(bd4)
                        val bd6 = bd5.add(outputRangeStart)
                        var returnInteger = bd6.setScale(0, RoundingMode.FLOOR).toBigInteger()
                        returnInteger =
                            if (returnInteger > end) end else returnInteger
                        return returnInteger.toToken(env)
                    },
                    listOf(env.getType("int")),
                    true
                )
            ).toToken(env),
            "list" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        return args.toToken(env)
                    })
            ).toToken(env),
            "head" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        val list = cast<List<Token>>(args[0].value)
                        if (list.isEmpty())
                            return env.himeNil
                        return list[0]
                    },
                    listOf(env.getType("list")),
                    false
                )
            ).toToken(env),
            "last" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        val tokens = cast<List<Token>>(args[0].value)
                        return tokens[tokens.size - 1]
                    },
                    listOf(env.getType("list")),
                    false
                )
            ).toToken(env),
            "tail" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        val tokens = cast<List<Token>>(args[0].value)
                        val list = ArrayList<Token>()
                        for (i in 1 until tokens.size)
                            list.add(tokens[i])
                        if (list.size == 1)
                            return arrayListOf(list[0]).toToken(env)
                        return list.toToken(env)
                    },
                    listOf(env.getType("list")),
                    false
                )
            ).toToken(env),
            "init" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        val tokens = cast<List<Token>>(args[0].value)
                        val list = ArrayList<Token>()
                        for (i in 0 until tokens.size - 1)
                            list.add(tokens[i])
                        if (list.size == 1)
                            return arrayListOf(list[0]).toToken(env)
                        return list.toToken(env)
                    },
                    listOf(env.getType("list")),
                    false
                )
            ).toToken(env),
            "list-contains" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        return cast<List<Token>>(args[0].value).contains(args[1]).toToken(env)
                    },
                    listOf(env.getType("list"), env.getType("any")),
                    false
                )
            ).toToken(env),
            "list-remove" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        val tokens = ArrayList(cast<List<Token>>(args[0].value))
                        tokens.removeAt(args[1].value.toString().toInt())
                        return tokens.toToken(env)
                    },
                    listOf(env.getType("list"), env.getType("int")),
                    false
                )
            ).toToken(env),
            "list-set" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        val index = args[1].value.toString().toInt()
                        val tokens = ArrayList(cast<List<Token>>(args[0].value))
                        himeAssertRuntime(index < tokens.size) { "index error." }
                        tokens[index] = args[2]
                        return tokens.toToken(env)
                    },
                    listOf(env.getType("list"), env.getType("int"), env.getType("any")),
                    false
                )
            ).toToken(env),
            "list-add" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        val tokens = ArrayList(cast<List<Token>>(args[0].value))
                        if (args.size > 2) {
                            himeAssertType(args[1], "int", env)
                            tokens.add(args[1].value.toString().toInt(), args[2])
                        } else
                            tokens.add(args[1])
                        return tokens.toToken(env)
                    },
                    listOf(env.getType("list"), env.getType("any")),
                    true
                )
            ).toToken(env),
            "list-remove!" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN, fun(args: List<Token>, _: SymbolTable): Token {
                        cast<MutableList<Token>>(args[0].value).removeAt(args[1].value.toString().toInt())
                        return args[0].toToken(env)
                    },
                    listOf(env.getType("list"), env.getType("int")),
                    false
                )
            ).toToken(env),
            "list-set!" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN, fun(args: List<Token>, _: SymbolTable): Token {
                        cast<MutableList<Token>>(args[0].value)[args[1].value.toString().toInt()] = args[2]
                        return args[0].toToken(env)
                    },
                    listOf(env.getType("list"), env.getType("int"), env.getType("any")),
                    false
                )
            ).toToken(env),
            "list-add!" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN, fun(args: List<Token>, _: SymbolTable): Token {
                        val tokens = cast<MutableList<Token>>(args[0].value)
                        if (args.size > 2) {
                            himeAssertType(args[1], "int", env)
                            tokens.add(args[1].value.toString().toInt(), args[2])
                        } else
                            tokens.add(args[1])
                        return args[0].toToken(env)
                    },
                    listOf(env.getType("list"), env.getType("any")),
                    true
                )
            ).toToken(env),
            "list-ref" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        val index = args[1].value.toString().toInt()
                        val tokens = cast<List<Token>>(args[0].value)
                        himeAssertRuntime(index < tokens.size) { "index error." }
                        return tokens[index]
                    },
                    listOf(env.getType("list"), env.getType("int")),
                    false
                )
            ).toToken(env),
            "++" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        var flag = false
                        // ???????????????List?????????string
                        for (arg in args)
                            if (env.isType(arg, env.getType("list"))) {
                                flag = true
                                break
                            }
                        return if (flag) {
                            val list = ArrayList<Token>()
                            for (arg in args) {
                                if (env.isType(arg, env.getType("list")))
                                    list.addAll(cast<List<Token>>(arg.value))
                                else
                                    list.add(arg)
                            }
                            list.toToken(env)
                        } else {
                            val builder = StringBuilder()
                            for (arg in args)
                                builder.append(arg.toString())
                            builder.toString().toToken(env)
                        }
                    },
                    listOf(env.getType("any")),
                    true
                )
            ).toToken(env),
            "range" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        val start = if (args.size >= 2) BigInteger(args[0].toString()) else BigInteger.ZERO
                        val end =
                            if (args.size >= 2) BigInteger(args[1].toString()) else BigInteger(args[0].toString())
                        // ???????????????step
                        val step = if (args.size >= 3) BigInteger(args[2].toString()) else BigInteger.ONE
                        val size = end.subtract(start).divide(step)
                        val list = ArrayList<Token>()
                        var i = BigInteger.ZERO
                        // index???size????????????
                        while (i.compareTo(size) != 1) {
                            list.add(start.add(i.multiply(step)).toToken(env))
                            i = i.add(BigInteger.ONE)
                        }
                        return Token(env.getType("list"), list)
                    },
                    listOf(env.getType("any")),
                    true
                )
            ).toToken(env),
            // ???????????????????????????????????????????????????
            "length" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        return if (env.isType(args[0], env.getType("list")))
                            cast<List<Token>>(args[0].value).size.toToken(env)
                        else
                            args[0].toString().length.toToken(env)
                    },
                    listOf(env.getType("any")),
                    false
                )
            ).toToken(env),
            // ????????????
            "reverse" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        val result = ArrayList<Token>()
                        val tokens = cast<MutableList<Token>>(args[0].value)
                        for (i in tokens.size - 1 downTo 0)
                            result.add(tokens[i])
                        tokens.clear()
                        for (t in result)
                            tokens.add(t)
                        return tokens.toToken(env)
                    },
                    listOf(env.getType("list")),
                    false
                )
            ).toToken(env),
            // ??????
            "sort" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        // ????????????
                        fun merge(a: Array<BigDecimal?>, low: Int, mid: Int, high: Int) {
                            val temp = arrayOfNulls<BigDecimal>(high - low + 1)
                            var i = low
                            var j = mid + 1
                            var k = 0
                            while (i <= mid && j <= high)
                                temp[k++] = if (a[i]!! < a[j]) a[i++] else a[j++]
                            while (i <= mid)
                                temp[k++] = a[i++]
                            while (j <= high)
                                temp[k++] = a[j++]
                            for (k2 in temp.indices)
                                a[k2 + low] = temp[k2]!!
                        }

                        fun mergeSort(a: Array<BigDecimal?>, low: Int, high: Int) {
                            val mid = (low + high) / 2
                            if (low < high) {
                                mergeSort(a, low, mid)
                                mergeSort(a, mid + 1, high)
                                merge(a, low, mid, high)
                            }
                        }

                        val tokens = cast<List<Token>>(args[0].value)
                        val result = ArrayList<Token>()
                        val list = arrayOfNulls<BigDecimal>(tokens.size)
                        for (i in tokens.indices)
                            list[i] = BigDecimal(tokens[i].toString())
                        mergeSort(list, 0, list.size - 1)
                        for (e in list)
                            result.add(e!!.toToken(env))
                        return result.toToken(env)
                    },
                    listOf(env.getType("list")),
                    false
                )
            ).toToken(env),
            "curry" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, symbol: SymbolTable): Token {
                        fun rsc(func: Token, n: Int, parameters: ArrayList<Token>): Token {
                            if (n == 0) {
                                // ?????????????????????????????????HimeFunction????????????????????????ast??????
                                val asts = env.himeAstEmpty.copy()
                                for (arg in parameters)
                                    asts.add(AstNode(arg))
                                return cast<HimeFunctionScheduler>(func.value).call(asts, symbol.createChild())
                            }
                            return HimeFunctionScheduler(env).add(
                                HimeFunction(
                                    env,
                                    BUILT_IN,
                                    fun(args: List<Token>, _: SymbolTable): Token {
                                        parameters.add(args[0])
                                        return rsc(func, n - 1, parameters)
                                    },
                                    1
                                )
                            ).toToken(env)
                        }
                        return rsc(args[0], args[1].value.toString().toInt(), ArrayList())
                    },
                    listOf(env.getType("function"), env.getType("int")),
                    false
                )
            ).toToken(env),
            "maybe" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, symbol: SymbolTable): Token {
                        val parameters = ArrayList<Token>()
                        for (i in 1 until args.size) {
                            if (args[i] == env.himeNil)
                                return env.himeNil
                            parameters.add(args[i])
                        }
                        // ?????????????????????????????????HimeFunction????????????????????????ast??????
                        val asts = env.himeAstEmpty.copy()
                        for (arg in parameters)
                            asts.add(AstNode(arg))
                        return cast<HimeFunctionScheduler>(args[0].value).call(asts, symbol.createChild())

                    },
                    listOf(env.getType("function")),
                    true
                )
            ).toToken(env),
            "map" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, symbol: SymbolTable): Token {
                        val result = ArrayList<Token>()
                        val tokens = cast<List<Token>>(args[1].value)
                        for (i in tokens.indices) {
                            val parameters = ArrayList<Token>()
                            parameters.add(tokens[i])
                            // ????????????(map f (list a b) (list c d))????????????(f a c)???
                            for (j in 1 until args.size - 1) {
                                himeAssertType(args[j + 1], "list", env)
                                parameters.add(cast<List<Token>>(args[j + 1].value)[i])
                            }
                            // ?????????????????????????????????HimeFunction????????????????????????ast??????
                            val asts = env.himeAstEmpty.copy()
                            for (arg in parameters)
                                asts.add(AstNode(arg))
                            result.add(cast<HimeFunctionScheduler>(args[0].value).call(asts, symbol.createChild()))
                        }
                        return result.toToken(env)
                    },
                    listOf(env.getType("function"), env.getType("list")),
                    true
                )
            ).toToken(env),
            "foldr" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, symbol: SymbolTable): Token {
                        var result = args[1]
                        val tokens = cast<List<Token>>(args[2].value)
                        for (i in tokens.size - 1 downTo 0) {
                            // ?????????????????????????????????HimeFunction????????????????????????ast??????
                            val asts = env.himeAstEmpty.copy()
                            for (arg in arrayListOf(tokens[i], result))
                                asts.add(AstNode(arg))
                            result = cast<HimeFunctionScheduler>(args[0].value).call(asts, symbol.createChild())
                        }
                        return result
                    },
                    listOf(env.getType("function"), env.getType("any"), env.getType("list")),
                    false
                )
            ).toToken(env),
            "foldl" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, symbol: SymbolTable): Token {
                        var result = args[1]
                        val tokens = cast<List<Token>>(args[2].value)
                        for (i in tokens.size - 1 downTo 0) {
                            // ?????????????????????????????????HimeFunction????????????????????????ast??????
                            val asts = env.himeAstEmpty.copy()
                            for (arg in arrayListOf(result, tokens[i]))
                                asts.add(AstNode(arg))
                            result = cast<HimeFunctionScheduler>(args[0].value).call(asts, symbol.createChild())
                        }
                        return result
                    },
                    listOf(env.getType("function"), env.getType("any"), env.getType("list")),
                    false
                )
            ).toToken(env),
            "for-each" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, symbol: SymbolTable): Token {
                        val tokens = cast<List<Token>>(args[1].value)
                        for (i in tokens.indices) {
                            val parameters = ArrayList<Token>()
                            parameters.add(tokens[i])
                            // ????????????(map f (list a b) (list c d))????????????(f a c)???
                            for (j in 1 until args.size - 1) {
                                himeAssertType(args[j + 1], "list", env)
                                parameters.add(cast<List<Token>>(args[j + 1].value)[i])
                            }
                            // ?????????????????????????????????HimeFunction????????????????????????ast??????
                            val asts = env.himeAstEmpty.copy()
                            for (arg in parameters)
                                asts.add(AstNode(arg))
                            cast<HimeFunctionScheduler>(args[0].value).call(asts, symbol.createChild())
                        }
                        return env.himeNil
                    },
                    listOf(env.getType("function"), env.getType("list")),
                    true
                )
            ).toToken(env),
            "filter" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, symbol: SymbolTable): Token {
                        val result = ArrayList<Token>()
                        val tokens = cast<List<Token>>(args[1].value)
                        for (token in tokens) {
                            // ?????????????????????????????????HimeFunction????????????????????????ast??????
                            val asts = env.himeAstEmpty.copy()
                            asts.add(AstNode(token))
                            val op = cast<HimeFunctionScheduler>(args[0].value).call(asts, symbol.createChild())
                            himeAssertType(op, "bool", env)
                            if (cast<Boolean>(op.value))
                                result.add(token)
                        }
                        return result.toToken(env)
                    },
                    listOf(env.getType("function"), env.getType("list")),
                    false
                )
            ).toToken(env),
            "sqrt" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        return BigDecimalMath.sqrt(BigDecimal(args[0].toString()), MathContext.DECIMAL64).toToken(env)
                    },
                    listOf(env.getType("num")),
                    false
                )
            ).toToken(env),
            "sin" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        return BigDecimalMath.sin(BigDecimal(args[0].toString()), MathContext.DECIMAL64).toToken(env)
                    },
                    listOf(env.getType("num")),
                    false
                )
            ).toToken(env),
            "sinh" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        return BigDecimalMath.sinh(BigDecimal(args[0].toString()), MathContext.DECIMAL64).toToken(env)
                    },
                    listOf(env.getType("num")),
                    false
                )
            ).toToken(env),
            "asin" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        return BigDecimalMath.asin(BigDecimal(args[0].toString()), MathContext.DECIMAL64).toToken(env)
                    },
                    listOf(env.getType("num")),
                    false
                )
            ).toToken(env),
            "asinh" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        return BigDecimalMath.asinh(BigDecimal(args[0].toString()), MathContext.DECIMAL64).toToken(env)
                    },
                    listOf(env.getType("num")),
                    false
                )
            ).toToken(env),
            "cos" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        return BigDecimalMath.cos(BigDecimal(args[0].toString()), MathContext.DECIMAL64).toToken(env)
                    },
                    listOf(env.getType("num")),
                    false
                )
            ).toToken(env),
            "cosh" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        return BigDecimalMath.cosh(BigDecimal(args[0].toString()), MathContext.DECIMAL64).toToken(env)
                    },
                    listOf(env.getType("num")),
                    false
                )
            ).toToken(env),
            "acos" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        return BigDecimalMath.acos(BigDecimal(args[0].toString()), MathContext.DECIMAL64).toToken(env)
                    },
                    listOf(env.getType("num")),
                    false
                )
            ).toToken(env),
            "acosh" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        return BigDecimalMath.acosh(BigDecimal(args[0].toString()), MathContext.DECIMAL64).toToken(env)
                    },
                    listOf(env.getType("num")),
                    false
                )
            ).toToken(env),
            "tan" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        return BigDecimalMath.tan(BigDecimal(args[0].toString()), MathContext.DECIMAL64).toToken(env)
                    },
                    listOf(env.getType("num")),
                    false
                )
            ).toToken(env),
            "tanh" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        return BigDecimalMath.tanh(BigDecimal(args[0].toString()), MathContext.DECIMAL64).toToken(env)
                    },
                    listOf(env.getType("num")),
                    false
                )
            ).toToken(env),
            "atan" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        return BigDecimalMath.atan(BigDecimal(args[0].toString()), MathContext.DECIMAL64).toToken(env)
                    },
                    listOf(env.getType("num")),
                    false
                )
            ).toToken(env),
            "atanh" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        return BigDecimalMath.atanh(BigDecimal(args[0].toString()), MathContext.DECIMAL64).toToken(env)
                    },
                    listOf(env.getType("num")),
                    false
                )
            ).toToken(env),
            "atan2" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        return BigDecimalMath.atan2(
                            BigDecimal(args[0].toString()),
                            BigDecimal(args[1].toString()),
                            MathContext.DECIMAL64
                        ).toToken(env)
                    },
                    listOf(env.getType("num"), env.getType("num")),
                    false
                )
            ).toToken(env),
            "log" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        return BigDecimalMath.log(BigDecimal(args[0].toString()), MathContext.DECIMAL64).toToken(env)
                    },
                    listOf(env.getType("num")),
                    false
                )
            ).toToken(env),
            "log10" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        return BigDecimalMath.log10(BigDecimal(args[0].toString()), MathContext.DECIMAL64).toToken(env)
                    },
                    listOf(env.getType("num")),
                    false
                )
            ).toToken(env),
            "log2" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        return BigDecimalMath.log2(BigDecimal(args[0].toString()), MathContext.DECIMAL64).toToken(env)
                    },
                    listOf(env.getType("num")),
                    false
                )
            ).toToken(env),
            "exp" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        return BigDecimalMath.exp(BigDecimal(args[0].toString()), MathContext.DECIMAL64).toToken(env)
                    },
                    listOf(env.getType("num")),
                    false
                )
            ).toToken(env),
            "pow" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        return BigDecimalMath.pow(
                            BigDecimal(args[0].toString()),
                            BigDecimal(args[1].toString()),
                            MathContext.DECIMAL64
                        ).toToken(env)
                    },
                    listOf(env.getType("num"), env.getType("num")),
                    false
                )
            ).toToken(env),
            "mod" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        return BigInteger(args[0].toString()).mod(BigInteger(args[1].toString())).toToken(env)
                    },
                    listOf(env.getType("int"), env.getType("int")),
                    false
                )
            ).toToken(env),
            "max" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        var max = BigDecimal(args[0].toString())
                        for (i in 1 until args.size) {
                            himeAssertType(args[i], "num", env)
                            max = max.max(BigDecimal(args[i].toString()))
                        }
                        return max.toToken(env)
                    },
                    listOf(env.getType("num")),
                    true
                )
            ).toToken(env),
            "min" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        var min = BigDecimal(args[0].toString())
                        for (i in 1 until args.size) {
                            himeAssertType(args[i], "num", env)
                            min = min.min(BigDecimal(args[i].toString()))
                        }
                        return min.toToken(env)
                    },
                    listOf(env.getType("num")),
                    true
                )
            ).toToken(env),
            "abs" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        return BigDecimal(args[0].toString()).abs().toToken(env)
                    },
                    1
                )
            ).toToken(env),
            "average" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        himeAssertRuntime(args.isNotEmpty()) { "not enough arguments." }
                        var num = BigDecimal.ZERO
                        for (arg in args) {
                            himeAssertType(arg, "num", env)
                            num = num.add(BigDecimal(arg.value.toString()))
                        }
                        return num.divide(args.size.toBigDecimal()).toToken(env)
                    },
                    listOf(env.getType("num")),
                    true
                )
            ).toToken(env),
            "floor" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        return BigInteger(
                            BigDecimal(args[0].toString()).setScale(0, RoundingMode.FLOOR).toPlainString()
                        ).toToken(env)
                    },
                    1
                )
            ).toToken(env),
            "ceil" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        return BigInteger(
                            BigDecimal(args[0].toString()).setScale(0, RoundingMode.CEILING).toPlainString()
                        ).toToken(env)
                    },
                    1
                )
            ).toToken(env),
            "gcd" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        for (arg in args)
                            himeAssertType(arg, "int", env)
                        var temp = BigInteger(args[0].toString()).gcd(BigInteger(args[1].toString()))
                        for (i in 2 until args.size)
                            temp = temp.gcd(BigInteger(args[i].toString()))
                        return temp.toToken(env)
                    },
                    listOf(env.getType("int"), env.getType("int")),
                    true
                )
            ).toToken(env),
            // (lcm a b) = (/ (* a b) (gcd a b))
            "lcm" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        fun BigInteger.lcm(n: BigInteger): BigInteger = (this.multiply(n).abs()).divide(this.gcd(n))
                        for (arg in args)
                            himeAssertType(arg, "num", env)
                        var temp = BigInteger(args[0].toString()).lcm(BigInteger(args[1].toString()))
                        for (i in 2 until args.size)
                            temp = temp.lcm(BigInteger(args[i].toString()))
                        return temp.toToken(env)
                    },
                    listOf(env.getType("int"), env.getType("int")),
                    true
                )
            ).toToken(env),
            "->bool" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        return if (args[0].toString() == "true") env.himeTrue else env.himeFalse
                    },
                    1
                )
            ).toToken(env),
            "->string" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        return args[0].toString().toToken(env)
                    },
                    1
                )
            ).toToken(env),
            "->int" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        return BigInteger(args[0].toString()).toToken(env)
                    },
                    1
                )
            ).toToken(env),
            "->real" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        return BigDecimal(args[0].toString()).toToken(env)
                    },
                    1
                )
            ).toToken(env),
            "string-replace" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        return args[0].toString().replace(args[1].toString(), args[2].toString()).toToken(env)
                    },
                    3
                )
            ).toToken(env),
            "string-substring" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        return args[0].toString()
                            .substring(args[1].value.toString().toInt(), args[2].value.toString().toInt())
                            .toToken(env)
                    },
                    listOf(env.getType("string"), env.getType("int"), env.getType("int")),
                    false
                )
            ).toToken(env),
            "string-split" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        return args[0].toString().split(args[1].toString()).toList().toToken(env)
                    },
                    listOf(env.getType("string"), env.getType("string")),
                    false
                )
            ).toToken(env),
            "string-index" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        return args[0].toString().indexOf(args[1].toString()).toToken(env)
                    },
                    listOf(env.getType("string"), env.getType("string")),
                    false
                )
            ).toToken(env),
            "string-last-index" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        himeAssertRuntime(args.size > 1) { "not enough arguments." }
                        return args[0].toString().lastIndexOf(args[1].toString()).toToken(env)
                    },
                    listOf(env.getType("string"), env.getType("string")),
                    false
                )
            ).toToken(env),
            "string-format" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        val newArgs = arrayOfNulls<Any>(args.size - 1)
                        for (i in 1 until args.size)
                            newArgs[i - 1] = args[i].value
                        return String.format(args[0].toString(), *newArgs).toToken(env)
                    },
                    listOf(env.getType("string")),
                    true
                )
            ).toToken(env),
            "string->list" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        himeAssertRuntime(args.isNotEmpty()) { "not enough arguments." }
                        val chars = args[0].toString().toCharArray()
                        val list = ArrayList<Token>()
                        for (c in chars)
                            list.add(c.toString().toToken(env))
                        return list.toToken(env)
                    },
                    listOf(env.getType("string")),
                    false
                )
            ).toToken(env),
            "list->string" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        val builder = StringBuilder()
                        val list = cast<List<Token>>(args[0].value)
                        for (token in list)
                            builder.append(token.toString())
                        return builder.toString().toToken(env)
                    },
                    listOf(env.getType("list")),
                    false
                )
            ).toToken(env),
            "string->bytes" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        val builder = StringBuilder()
                        for (token in args)
                            builder.append(token.toString())
                        val list = ArrayList<Token>()
                        val bytes = builder.toString().toByteArray()
                        for (byte in bytes)
                            list.add(byte.toToken(env))
                        return list.toToken(env)
                    },
                    listOf(env.getType("string")),
                    false
                )
            ).toToken(env),
            "bytes->string" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        val list = cast<List<Token>>(args[0].value)
                        val bytes = ByteArray(list.size)
                        for (index in list.indices) {
                            himeAssertType(list[index], "byte", env)
                            bytes[index] = cast<Byte>(list[index].value)
                        }
                        return String(bytes).toToken(env)
                    },
                    listOf(env.getType("list")),
                    false
                )
            ).toToken(env),
            "string->bits" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        val s = args[0].toString()
                        val result = ArrayList<Token>()
                        for (c in s)
                            result.add(c.code.toToken(env))
                        return result.toToken(env)
                    },
                    listOf(env.getType("string")),
                    false
                )
            ).toToken(env),
            "bits->string" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        val result = StringBuilder()
                        val tokens = cast<List<Token>>(args[0].value)
                        for (t in tokens) {
                            himeAssertType(t, "int", env)
                            result.append(t.value.toString().toInt().toChar())
                        }
                        return result.toToken(env)
                    },
                    listOf(env.getType("list")),
                    false
                )
            ).toToken(env),
            "exit" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        exitProcess(args[0].value.toString().toInt())
                    },
                    listOf(env.getType("int")),
                    false
                )
            ).toToken(env),
            "eval" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, symbol: SymbolTable): Token {
                        val newSymbol = symbol.createChild()
                        var result = env.himeNil
                        for (node in args)
                            result = call(env, node.toString(), newSymbol)
                        return result
                    },
                    listOf(env.getType("string")),
                    true
                )
            ).toToken(env),
            "bit-and" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        return BigInteger(args[0].toString()).and(BigInteger(args[1].toString())).toToken(env)
                    },
                    listOf(env.getType("int"), env.getType("int")),
                    false
                )
            ).toToken(env),
            "bit-or" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        return BigInteger(args[0].toString()).or(BigInteger(args[1].toString())).toToken(env)
                    },
                    listOf(env.getType("int"), env.getType("int")),
                    false
                )
            ).toToken(env),
            "bit-xor" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        return BigInteger(args[0].toString()).xor(BigInteger(args[1].toString())).toToken(env)
                    },
                    listOf(env.getType("int"), env.getType("int")),
                    false
                )
            ).toToken(env),
            "bit-left" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        return BigInteger(args[0].toString()).shiftLeft(BigInteger(args[1].toString()).toInt())
                            .toToken(env)
                    },
                    listOf(env.getType("int"), env.getType("int")),
                    false
                )
            ).toToken(env),
            "bit-right" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        return BigInteger(args[0].toString()).shiftRight(BigInteger(args[1].toString()).toInt())
                            .toToken(env)
                    },
                    listOf(env.getType("int"), env.getType("int")),
                    false
                )
            ).toToken(env),
            "error" to HimeFunctionScheduler(env).add(
                HimeFunction(
                    env,
                    BUILT_IN,
                    fun(args: List<Token>, _: SymbolTable): Token {
                        throw HimeRuntimeException(args[0].toString())
                    },
                    listOf(env.getType("any")),
                    false
                )
            ).toToken(env)
        )
    )
}
