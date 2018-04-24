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

            val result = when (condition) {
                is PyBoolLiteralExpression -> condition.value
                is PyBinaryExpression -> evaluateBinaryExpression(condition)
                else -> null
            }

            result ?: return
            registerProblem(condition, "The condition is always $result")
        }

        private fun evaluateBinaryExpression(expression: PyBinaryExpression): Boolean? {
            val left = expression.leftExpression.getInt() ?: return null
            val right = expression.rightExpression?.getInt() ?: return null

            return when (expression.operator) {
                LT -> left < right
                GT -> left > right
                LE -> left <= right
                GE -> left >= right
                EQEQ -> left == right
                NE -> left != right
                else -> null
            }
        }
    }
}

private fun PyExpression.getInt(): Int? {
    if (this is PyNumericLiteralExpression && isIntegerLiteral) {
        val value = bigIntegerValue ?: return null
        if (value.toInt().toLong() == value.toLong()) {
            return value.toInt()
        }
    }
    return null
}
