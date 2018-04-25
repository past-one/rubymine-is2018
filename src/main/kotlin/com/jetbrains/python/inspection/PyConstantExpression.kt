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
            val result = evaluate(condition).toBoolean() ?: return
            registerProblem(condition, "The condition is always $result")
        }

        private fun evaluate(expression: PyExpression?): Any? {
            return when (expression) {
                is PyNumericLiteralExpression -> expression.getInt()
                is PyBoolLiteralExpression -> expression.value
                is PyBinaryExpression -> evaluateBinaryExpression(expression)
                is PyParenthesizedExpression -> evaluate(expression.containedExpression)

                is PyPrefixExpression -> when (expression.operator) {
                    NOT_KEYWORD -> !(evaluate(expression.operand).toBoolean() ?: return null)
                    PLUS -> evaluate(expression.operand).toInt()
                    MINUS -> -(evaluate(expression.operand).toInt() ?: return null)
                    else -> null
                }
                else -> null
            }
        }

        private fun evaluateBinaryExpression(expression: PyBinaryExpression): Any? {
            val operator = expression.operator
            val left = evaluate(expression.leftExpression)
            val right = evaluate(expression.rightExpression)
            return when (operator) {
                AND_KEYWORD, OR_KEYWORD -> {
                    val leftBool = left.toBoolean()
                    val rightBool = right.toBoolean()
                    when (operator) {
                        AND_KEYWORD -> if (leftBool == true) rightBool else leftBool
                        OR_KEYWORD -> if (leftBool == false) rightBool else leftBool
                        else -> null
                    }
                }
                else -> evaluateIntBinaryExpression(operator, left.toInt(), right.toInt())
            }
        }

        private fun evaluateIntBinaryExpression(operator: PyElementType?, left: Int?, right: Int?): Any? {
            left ?: return null
            right ?: return null
            return when (operator) {
                EQEQ -> left == right
                NE -> left != right

                LT -> left < right
                GT -> left > right
                LE -> left <= right
                GE -> left >= right

                PLUS -> left + right
                MINUS -> left - right
                MULT -> left * right
                EXP -> if (right < 0) null else Math.pow(left.toDouble(), right.toDouble()).toInt()

                DIV -> if (right != 0 && left % right == 0) left / right else null // skip floats
                FLOORDIV -> if (right != 0) left / right else null
                PERC -> if (right != 0) left % right else null
                else -> null
            }
        }
    }
}

private fun Any?.toBoolean(): Boolean? {
    return when (this) {
        is Boolean -> this
        is Int -> this != 0
        else -> null
    }
}

private fun Any?.toInt(): Int? {
    return when (this) {
        is Boolean -> if (this) 1 else 0
        is Int -> this
        else -> null
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
