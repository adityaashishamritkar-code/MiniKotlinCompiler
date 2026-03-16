package org.example.compiler

import MiniKotlinBaseVisitor
import MiniKotlinParser
import org.antlr.v4.runtime.tree.TerminalNode

class MiniKotlinCompiler : MiniKotlinBaseVisitor<String>() {

    private var argCounter = 0
    private fun nextArg() = "arg${argCounter++}"

    fun compile(ctx: MiniKotlinParser.ProgramContext): String {
        val functions = ctx.functionDeclaration().joinToString("\n\n") { visit(it) }
        return """
public class MiniProgram {
    $functions
}
        """.trimIndent()
    }

    override fun visitFunctionDeclaration(ctx: MiniKotlinParser.FunctionDeclarationContext): String {
        val name = ctx.IDENTIFIER().text
        val params = ctx.parameterList()?.parameter()?.joinToString(", ") {
            "${mapType(it.type().text)} ${it.IDENTIFIER().text}"
        } ?: ""

        val retType = mapType(ctx.type().text)

        if (name == "main") {
            return """
    public static void main(String[] args) { 
        ${// Inside visitFunctionDeclaration for main
                visitStatements(ctx.block().statement(), "(unused) -> {}")}
    }"""
        }

        return """
    public static void $name($params${if (params.isNotEmpty()) ", " else ""}Continuation<$retType> __continuation) { 
        ${visitStatements(ctx.block().statement(), "__continuation")}
    }"""
    }

    private fun visitStatements(stmts: List<MiniKotlinParser.StatementContext>, currentCont: String?): String {
        if (stmts.isEmpty()) return ""
        val head = stmts.first()
        val tail = stmts.drop(1)

        // Handle Return
        head.returnStatement()?.let { ret ->
            return translateExpr(ret.expression()) { result ->
                if (currentCont != null) "$currentCont.accept($result);\nreturn;" else "return;"
            }
        }

        // Handle Var Declaration
        head.variableDeclaration()?.let { decl ->
            val name = decl.IDENTIFIER().text
            val type = mapType(decl.type().text)
            return translateExpr(decl.expression()) { result ->
                // Force the semicolon and a newline here
                "$type $name = $result;\n${visitStatements(tail, currentCont)}"
            }
        }

        head.variableAssignment()?.let { assign ->
            val name = assign.IDENTIFIER().text
            return translateExpr(assign.expression()) { result ->
                // This ensures "a = 15;" is generated with a semicolon and newline
                "$name = $result;\n${visitStatements(tail, currentCont)}"
            }
        }

        // Handle If Statement
        head.ifStatement()?.let { ifStmt ->
            return translateExpr(ifStmt.expression()) { cond ->
                val thenBlock = visitStatements(ifStmt.block(0).statement(), currentCont)
                val elseBlock = if (ifStmt.block().size > 1) {
                    visitStatements(ifStmt.block(1).statement(), currentCont)
                } else ""
                """if ($cond) {
                    $thenBlock
                } else {
                    $elseBlock
                }"""
            }
        }

        // Handle While Statement
        head.whileStatement()?.let { whileStmt ->
            val loopName = "loop${nextArg()}"
            return translateExpr(whileStmt.expression()) { cond ->
                // The body MUST call the loopName.accept(null) at the end
                val body = visitStatements(whileStmt.block().statement(), "$loopName")
                val rest = visitStatements(tail, currentCont)

                """
        final Continuation<Void>[] $loopName = new Continuation[1];
        $loopName[0] = new Continuation<Void>() {
            @Override
            public void accept(Void __unused) {
                if ($cond) {
                    $body
                    $loopName[0].accept(null);
                } else {
                    $rest
                }
            }
        };
        $loopName[0].accept(null);
        """
            }
        }

        // Handle Expression statement (e.g. just calling a function)
// Handle Expression statement
        head.expression()?.let { expr ->
            return translateExpr(expr) {
                // We discard the 'result' here because this expression
                // is a standalone statement (like println), not a 'val' assignment.
                visitStatements(tail, currentCont)
            }
        }

        return visitStatements(tail, currentCont)
    }

    /**
     * The recursive "Expression flattener".
     * If an expression contains a function call, it nests 'next' inside a continuation.
     */
    private fun translateExpr(ctx: MiniKotlinParser.ExpressionContext?, next: (String) -> String): String {
        if (ctx == null) return next("null")

        return when (ctx) {
            is MiniKotlinParser.FunctionCallExprContext -> {
                val funcName = ctx.IDENTIFIER().text
                val argList = ctx.argumentList().expression()

                // Recursively translate arguments first (in case an argument is a function call!)
                translateArgs(argList) { translatedArgs ->
                    val resVar = nextArg()
                    val target = if (funcName == "println") "Prelude.println" else funcName
                    "$target(${translatedArgs.joinToString(", ")}, ($resVar) -> {\n${next(resVar)}\n});"
                }
            }

            is MiniKotlinParser.MulDivExprContext -> {
                // If you have n * factorial(n-1), we must translate both sides
                translateExpr(ctx.expression(0)) { left ->
                    translateExpr(ctx.expression(1)) { right ->
                        next("($left ${ctx.getChild(1).text} $right)")
                    }
                }
            }

            is MiniKotlinParser.PrimaryExprContext -> translatePrimary(ctx.primary(), next)

            // Fallback for simple binary ops: recursive descent
            is MiniKotlinParser.AddSubExprContext,
            is MiniKotlinParser.ComparisonExprContext,
            is MiniKotlinParser.EqualityExprContext -> {
                translateExpr(ctx.getChild(0) as MiniKotlinParser.ExpressionContext) { left ->
                    translateExpr(ctx.getChild(2) as MiniKotlinParser.ExpressionContext) { right ->
                        next("($left ${ctx.getChild(1).text} $right)")
                    }
                }
            }

            else -> next(ctx.text)
        }
    }

    private fun translateArgs(
        args: List<MiniKotlinParser.ExpressionContext>,
        acc: List<String> = emptyList(),
        final: (List<String>) -> String
    ): String {
        if (args.isEmpty()) return final(acc)
        return translateExpr(args.first()) { res ->
            // Create a NEW list including the new result
            translateArgs(args.drop(1), acc + res, final)
        }
    }

    private fun translatePrimary(ctx: MiniKotlinParser.PrimaryContext, next: (String) -> String): String {
        val child = ctx.getChild(0)
        return when (child) {
            is MiniKotlinParser.ParenExprContext -> translateExpr(child.expression(), next)
            else -> next(ctx.text)
        }
    }

    private fun mapType(type: String): String = when(type) {
        "Int" -> "Integer"
        "Boolean" -> "Boolean"
        "Unit" -> "Void"
        "String" -> "String"
        else -> type
    }
}