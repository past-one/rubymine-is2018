package com.jetbrains.python.inspection

import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import com.jetbrains.python.PyTokenTypes.*
import com.jetbrains.python.inspections.PyInspection
import com.jetbrains.python.inspections.PyInspectionVisitor
import com.jetbrains.python.psi.*

class PyConstantExpression : PyInspection() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean,
                              session: LocalInspectionToolSession): PsiElementVisitor {
        return Visitor(holder, session)
    }

    class Visitor(holder: ProblemsHolder?, session: LocalInspectionToolSession) : PyInspectionVisitor(holder, session) {

        override fun visitPyIfStatement(node: PyIfStatement) {
            super.visitPyIfStatement(node)
            processIfPart(node.ifPart)
            for (part in node.elifParts) {
                processIfPart(part)
            }
        }

        private fun processIfPart(pyIfPart: PyIfPart) {
            val condition = pyIfPart.condition
            val result = PyExpr.evaluate(condition).result ?: return
            registerProblem(condition, "The condition is always $result")
        }
    }

    private sealed class PyExpr {
        val result: Boolean?
            get() = (applyBool() as? BoolExpr)?.value

        companion object {
            fun evaluate(expression: PyExpression?): PyExpr {
                return when (expression) {
                    is PyBoolLiteralExpression -> BoolExpr(expression.value)
                    is PyBinaryExpression -> evaluateBinaryExpression(expression)
                    is PyParenthesizedExpression -> evaluate(expression.containedExpression)

                    is PyNumericLiteralExpression -> {
                        val value = expression.getInt()
                        if (value != null) IntExpr(value) else UnknownExpr
                    }

                    is PyPrefixExpression -> {
                        val value = evaluate(expression.operand)
                        when (expression.operator) {
                            NOT_KEYWORD -> !value
                            PLUS -> +value
                            MINUS -> -value
                            else -> UnknownExpr
                        }
                    }
                    else -> UnknownExpr
                }
            }

            private fun evaluateBinaryExpression(expression: PyBinaryExpression): PyExpr {
                val operator = expression.operator
                val left = evaluate(expression.leftExpression)
                val right = evaluate(expression.rightExpression)
                return when (operator) {
                    AND_KEYWORD -> left.andKeyword(right)
                    OR_KEYWORD -> left.orKeyword(right)

                    EQEQ -> left.eq(right)
                    NE -> left.ne(right)

                    LT -> left.lt(right)
                    GT -> left.gt(right)
                    LE -> left.le(right)
                    GE -> left.ge(right)

                    PLUS -> left + right
                    MINUS -> left - right
                    MULT -> left * right
                    EXP -> left.pow(right)

                    DIV -> left / right
                    FLOORDIV -> left.floorDiv(right)
                    PERC -> left % right
                    else -> UnknownExpr
                }
            }
        }

        data class IntExpr(val value: Int) : PyExpr()
        data class BoolExpr(val value: Boolean) : PyExpr()

        object UnknownExpr : PyExpr()

        fun applyBool(mapper: (Boolean) -> PyExpr = { BoolExpr(it) }) = when (this) {
            is BoolExpr -> mapper(value)
            is IntExpr -> mapper(value != 0)
            is UnknownExpr -> this
        }

        fun applyInt(mapper: (Int) -> PyExpr = { IntExpr(it) }) = when (this) {
            is IntExpr -> mapper(value)
            is BoolExpr -> mapper(if (value) 1 else 0)
            is UnknownExpr -> this
        }

        fun applyBinaryInt(other: PyExpr, mapper: (Int, Int) -> PyExpr) = applyInt { left ->
            other.applyInt { right -> mapper(left, right) }
        }

        operator fun unaryPlus() = applyInt()
        operator fun unaryMinus() = applyInt { IntExpr(-it) }

        operator fun not() = applyBool { BoolExpr(!it) }

        fun andKeyword(other: PyExpr): PyExpr = applyBool { left ->
            if (left) other.applyBool() else BoolExpr(false)
        }

        fun orKeyword(other: PyExpr): PyExpr = applyBool { left ->
            if (left) BoolExpr(true) else other.applyBool()
        }

        fun eq(other: PyExpr): PyExpr = applyBinaryInt(other) { left, right -> BoolExpr(left == right) }
        fun ne(other: PyExpr): PyExpr = applyBinaryInt(other) { left, right -> BoolExpr(left != right) }

        fun lt(other: PyExpr): PyExpr = applyBinaryInt(other) { left, right -> BoolExpr(left < right) }
        fun gt(other: PyExpr): PyExpr = applyBinaryInt(other) { left, right -> BoolExpr(left > right) }
        fun le(other: PyExpr): PyExpr = applyBinaryInt(other) { left, right -> BoolExpr(left <= right) }
        fun ge(other: PyExpr): PyExpr = applyBinaryInt(other) { left, right -> BoolExpr(left >= right) }

        operator fun plus(other: PyExpr) = applyBinaryInt(other) { left, right -> IntExpr(left + right) }
        operator fun minus(other: PyExpr) = applyBinaryInt(other) { left, right -> IntExpr(left - right) }
        operator fun times(other: PyExpr) = applyBinaryInt(other) { left, right -> IntExpr(left * right) }

        fun pow(other: PyExpr) = applyBinaryInt(other) { left, right ->
            if (right < 0) {
                UnknownExpr
            } else {
                var result = 1
                for (i in 1..right) result *= left
                IntExpr(result)
            }
        }

        operator fun div(other: PyExpr) = applyBinaryInt(other) { left, right ->
            if (right != 0 && left % right == 0) IntExpr(left / right) else UnknownExpr // skip floats
        }

        fun floorDiv(other: PyExpr) = applyBinaryInt(other) { left, right ->
            if (right != 0) IntExpr(left / right) else UnknownExpr
        }

        operator fun rem(other: PyExpr) = applyBinaryInt(other) { left, right ->
            if (right != 0) IntExpr(left % right) else UnknownExpr
        }
    }
}

private fun PyNumericLiteralExpression.getInt(): Int? {
    if (isIntegerLiteral) {
        val value = bigIntegerValue ?: return null
        if (value.toInt().toLong() == value.toLong()) {
            return value.toInt()
        }
    }
    return null
}
