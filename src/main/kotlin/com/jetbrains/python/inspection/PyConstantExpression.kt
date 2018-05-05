package com.jetbrains.python.inspection

import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import com.jetbrains.python.PyTokenTypes.*
import com.jetbrains.python.inspections.PyInspection
import com.jetbrains.python.inspections.PyInspectionVisitor
import com.jetbrains.python.psi.*
import kotlin.collections.ArrayList

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
                    is PyReferenceExpression -> VarExpr(expression.text)

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

        data class VarExpr(val name: String) : PyExpr() {
            override fun eq(other: PyExpr) = other.applyInt { RangesExpr(name, Range.dot(it)) }

            override fun ne(other: PyExpr) = other.applyInt {
                RangesExpr(name, Range.less(it, false), Range.greater(it, false))
            }

            override fun lt(other: PyExpr) = other.applyInt { RangesExpr(name, Range.less(it, false)) }
            override fun gt(other: PyExpr) = other.applyInt { RangesExpr(name, Range.greater(it, false)) }
            override fun le(other: PyExpr) = other.applyInt { RangesExpr(name, Range.less(it, true)) }
            override fun ge(other: PyExpr) = other.applyInt { RangesExpr(name, Range.greater(it, true)) }
        }

        class RangesExpr(variable: String, vararg varRanges: Range) : PyExpr() {
            private var ranges: MutableMap<String, SortedRangeList> = HashMap()

            // we lost our knowledge about variable ranges just after last "and" in a row applied if it was different
            // variables in there, see tests/variables/test9.py
            private val unknownVarSet: MutableSet<String> = HashSet()

            private val isUnknown: Boolean
                get() {
                    for (variable in unknownVarSet) {
                        ranges.remove(variable)
                    }
                    return ranges.isEmpty()
                }

            init {
                ArrayList<Range>(varRanges.size).let {
                    ranges[variable] = it
                    for (range in varRanges) {
                        it.add(range)
                    }
                }
            }

            fun thisOrUnknown(): PyExpr = if (isUnknown) UnknownExpr else this

            private fun applyLogical(other: PyExpr, isAndKeyword: Boolean): PyExpr {
                if (other !is RangesExpr) return super.andKeyword(other)

                if (!isAndKeyword && (isUnknown || other.isUnknown)) return UnknownExpr

                val allVariables = ranges.keys.union(other.ranges.keys)

                for (variable in allVariables) {
                    val newList = if (isAndKeyword) {
                        ranges[variable].intersect(other.ranges[variable])
                    } else {
                        ranges[variable].union(other.ranges[variable])
                    }

                    if (newList == null) {
                        ranges.remove(variable)
                        continue
                    }

                    val result = newList.toBoolean()
                    if (result != null) {
                        return BoolExpr(result)
                    }
                    ranges[variable] = newList
                }

                if (isAndKeyword) {
                    unknownVarSet.addAll(ranges.keys.subtract(other.ranges.keys))
                    unknownVarSet.addAll(other.ranges.keys.subtract(ranges.keys))
                }

                return this
            }

            override fun andKeyword(other: PyExpr) = applyLogical(other, true)
            override fun orKeyword(other: PyExpr) = applyLogical(other, false)

            override fun not(): PyExpr {
                if (isUnknown) return UnknownExpr
                ranges.replaceAll { _, list -> list.complement() }
                return this
            }

            // for comparisons like `1 < y < 2` == `1 < y and y < 2`
            fun comparison(other: PyExpr, applierBuilder: (VarExpr) -> (Int) -> PyExpr): PyExpr {
                if (ranges.size != 1) return UnknownExpr

                val applier = applierBuilder(VarExpr(ranges.keys.first()))

                return this.andKeyword(other.applyInt(applier))
            }

            override fun eq(other: PyExpr) = comparison(other) { varExpr -> { varExpr.eq(IntExpr(it)) } }
            override fun ne(other: PyExpr) = comparison(other) { varExpr -> { varExpr.ne(IntExpr(it)) } }
            override fun lt(other: PyExpr) = comparison(other) { varExpr -> { varExpr.lt(IntExpr(it)) } }
            override fun gt(other: PyExpr) = comparison(other) { varExpr -> { varExpr.gt(IntExpr(it)) } }
            override fun le(other: PyExpr) = comparison(other) { varExpr -> { varExpr.le(IntExpr(it)) } }
            override fun ge(other: PyExpr) = comparison(other) { varExpr -> { varExpr.ge(IntExpr(it)) } }
        }

        object UnknownExpr : PyExpr()

        fun applyBool(applier: (Boolean) -> PyExpr = { BoolExpr(it) }) = when (this) {
            is BoolExpr -> applier(value)
            is IntExpr -> applier(value != 0)
            is RangesExpr -> thisOrUnknown()
            else -> UnknownExpr
        }

        fun applyInt(applier: (Int) -> PyExpr = { IntExpr(it) }) = when (this) {
            is IntExpr -> applier(value)
            is BoolExpr -> applier(if (value) 1 else 0)
            else -> UnknownExpr
        }

        fun applyBinaryInt(other: PyExpr, applier: (Int, Int) -> PyExpr) = applyInt { left ->
            other.applyInt { right -> applier(left, right) }
        }

        operator fun unaryPlus() = applyInt()
        operator fun unaryMinus() = applyInt { IntExpr(-it) }

        open operator fun not() = applyBool { BoolExpr(!it) }

        open fun andKeyword(other: PyExpr): PyExpr = applyBool { left ->
            if (left) other.applyBool() else BoolExpr(false)
        }

        open fun orKeyword(other: PyExpr): PyExpr = applyBool { left ->
            if (left) BoolExpr(true) else other.applyBool()
        }

        open fun eq(other: PyExpr): PyExpr = when (other) {
            is VarExpr -> other.eq(this)
            else -> applyBinaryInt(other) { left, right -> BoolExpr(left == right) }
        }

        open fun ne(other: PyExpr): PyExpr = when (other) {
            is VarExpr -> other.ne(this)
            else -> applyBinaryInt(other) { left, right -> BoolExpr(left != right) }
        }

        open fun lt(other: PyExpr): PyExpr = when (other) {
            is VarExpr -> other.lt(this)
            else -> applyBinaryInt(other) { left, right -> BoolExpr(left < right) }
        }

        open fun gt(other: PyExpr): PyExpr = when (other) {
            is VarExpr -> other.gt(this)
            else -> applyBinaryInt(other) { left, right -> BoolExpr(left > right) }
        }

        open fun le(other: PyExpr): PyExpr = when (other) {
            is VarExpr -> other.le(this)
            else -> applyBinaryInt(other) { left, right -> BoolExpr(left <= right) }
        }

        open fun ge(other: PyExpr): PyExpr = when (other) {
            is VarExpr -> other.ge(this)
            else -> applyBinaryInt(other) { left, right -> BoolExpr(left >= right) }
        }

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

private data class Range(val low: RangeBound, val high: RangeBound) {
    companion object {
        val total = Range(RangeBound.NegativeInfinity, RangeBound.PositiveInfinity)

        fun dot(value: Int) = Range(
                RangeBound.Bound(value, BoundType.EXACT),
                RangeBound.Bound(value, BoundType.EXACT)
        )

        fun less(value: Int, include: Boolean) = Range(
                RangeBound.NegativeInfinity,
                RangeBound.Bound(value, if (include) BoundType.EXACT else BoundType.UPPER)
        )

        fun greater(value: Int, include: Boolean) = Range(
                RangeBound.Bound(value, if (include) BoundType.EXACT else BoundType.LOWER),
                RangeBound.PositiveInfinity
        )
    }

    fun isIntersect(other: Range) = high.isGreaterEqualOrNear(other.low) && other.high.isGreaterEqualOrNear(low)

    fun intersect(other: Range) = Range(maxOf(low, other.low), minOf(high, other.high))
    fun union(other: Range) = Range(minOf(low, other.low), maxOf(high, other.high))
}

private typealias SortedRangeList = MutableList<Range>

private fun SortedRangeList.toBoolean() = when {
    isEmpty() -> false
    first() == Range.total -> true
    else -> null
}

private fun SortedRangeList?.intersect(otherList: SortedRangeList?): SortedRangeList? {
    // conditions separated for smart casts
    otherList ?: return this
    this ?: return otherList

    when {
        otherList.isEmpty() -> return otherList
        isEmpty() -> return this
    }

    val firstIterator = this.iterator()
    val secondIterator = otherList.iterator()
    val result = ArrayList<Range>(maxOf(size, otherList.size) + 1)

    var first = firstIterator.next()
    var second = secondIterator.next()
    while (true) {
        if (first.isIntersect(second)) {
            result.add(first.intersect(second))
        }

        if (firstIterator.hasNext() && first.high >= second.high) {
            first = firstIterator.next()
        } else if (secondIterator.hasNext() && second.high >= first.high) {
            second = secondIterator.next()
        } else {
            break
        }
    }

    return result
}

private fun SortedRangeList?.union(otherList: SortedRangeList?): SortedRangeList? {
    // conditions separated for smart casts
    otherList ?: return this
    this ?: return otherList

    when {
        otherList.isEmpty() -> return this
        isEmpty() -> return otherList
    }

    val firstIterator = this.iterator()
    val secondIterator = otherList.iterator()
    val result = ArrayList<Range>(size + otherList.size + 1)

    var first = firstIterator.next()
    var second = secondIterator.next()
    var buffer: Range? = null

    val addBuffered = { buf: Range?, nextRange: Range ->
        when {
            buf == null -> nextRange
            buf.isIntersect(nextRange) -> buf.union(nextRange)
            else -> {
                result.add(buf)
                nextRange
            }
        }
    }

    loop@ while (true) {
        when {
            first.isIntersect(second) -> {
                buffer = addBuffered(buffer, first.union(second))

                if (!firstIterator.hasNext() && !secondIterator.hasNext()) break@loop

                if (firstIterator.hasNext()) first = firstIterator.next()
                if (secondIterator.hasNext()) second = secondIterator.next()
            }
            first.high < second.low -> {
                buffer = addBuffered(buffer, first)

                if (firstIterator.hasNext()) {
                    first = firstIterator.next()
                } else {
                    while (secondIterator.hasNext()) {
                        result.add(secondIterator.next())
                    }
                    break@loop
                }
            }
            else -> {
                buffer = addBuffered(buffer, second)

                if (secondIterator.hasNext()) {
                    second = secondIterator.next()
                } else {
                    while (firstIterator.hasNext()) {
                        result.add(firstIterator.next())
                    }
                    break@loop
                }
            }
        }
    }
    if (buffer != null) result.add(buffer)

    return result
}

private fun SortedRangeList.complement(): SortedRangeList {
    val newList = ArrayList<Range>(size)
    var currentBound: RangeBound = RangeBound.NegativeInfinity
    for (range in this) {
        if (currentBound < range.low) {
            newList.add(Range(currentBound, range.low.changeType(true)))
        }
        currentBound = range.high.changeType(false)
    }
    if (currentBound !== RangeBound.PositiveInfinity) {
        newList.add(Range(currentBound, RangeBound.PositiveInfinity))
    }
    return newList
}

private sealed class RangeBound : Comparable<RangeBound> {
    data class Bound(val value: Int, val type: BoundType) : RangeBound() {
        override operator fun compareTo(other: RangeBound) = when (other) {
            is PositiveInfinity -> -1
            is NegativeInfinity -> 1
            is Bound -> {
                val result = value.compareTo(other.value)
                if (result == 0) type.compareTo(other.type) else result
            }
        }

        override fun changeType(isUpper: Boolean): Bound {
            val newType = when {
                type != BoundType.EXACT -> BoundType.EXACT
                isUpper -> BoundType.UPPER
                else -> BoundType.LOWER
            }
            return copy(type = newType)
        }

        override fun isGreaterEqualOrNear(other: RangeBound): Boolean {
            if (other !is Bound) return false
            if (this >= other) return true
            if (value != other.value) return false

            return when {
                type == BoundType.UPPER && other.type == BoundType.EXACT -> true
                type == BoundType.EXACT && other.type == BoundType.LOWER -> true
                else -> false
            }
        }
    }

    object PositiveInfinity : RangeBound() {
        override operator fun compareTo(other: RangeBound) = if (this === other) 0 else 1
    }

    object NegativeInfinity : RangeBound() {
        override operator fun compareTo(other: RangeBound) = if (this === other) 0 else -1
    }

    open fun changeType(isUpper: Boolean) = this
    open fun isGreaterEqualOrNear(other: RangeBound) = this >= other
}

private enum class BoundType {
    UPPER(),
    EXACT(),
    LOWER(),
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
