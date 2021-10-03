package xyz.qwewqa.relive.simulator.core.stage.strategy.conpletestrategy

interface CsNode

data class CsScriptNode(
    val initialize: CsStatementNode?,
    val body: CsStatementNode,
) : CsNode

sealed interface CsStatementNode : CsNode {
    fun execute(context: CsContext)
}

sealed interface CsExpressionNode : CsStatementNode {
    override fun execute(context: CsContext) {
        evaluate(context)
    }

    fun evaluate(context: CsContext): CsObject
}

data class CsSwitchClause(val expression: CsExpressionNode?, val cases: List<CsCaseClause>) : CsStatementNode {
    override fun execute(context: CsContext) {
        if (expression != null) {
            val value = expression.evaluate(context)
            cases.first { case ->
                (case is CsDefaultCase) ||
                        (case is CsExpressionCase) && case.condition.any { it.evaluate(context) == value }
            }.body.execute(context)
        } else {
            cases.first { case ->
                (case is CsDefaultCase) ||
                        (case is CsExpressionCase) && case.condition.any { it.evaluate(context).bool() }
            }.body.execute(context)
        }
    }
}

sealed interface CsCaseClause {
    val body: CsStatementNode
}

data class CsExpressionCase(val condition: List<CsExpressionNode>, override val body: CsStatementNode) : CsCaseClause
data class CsDefaultCase(override val body: CsStatementNode) : CsCaseClause

data class CsIfNode(val condition: CsExpressionNode, val tbranch: CsStatementNode, val fbranch: CsStatementNode?) : CsStatementNode {
    override fun execute(context: CsContext) {
        if (condition.evaluate(context).bool()) {
            tbranch.execute(context)
        } else {
            fbranch?.execute(context)
        }
    }
}

data class CsBlockNode(val statements: List<CsStatementNode>) : CsStatementNode {
    override fun execute(context: CsContext) {
        statements.forEach { it.execute(context) }
    }
}

data class CsAssignmentNode(val name: String, val value: CsExpressionNode) : CsStatementNode {
    override fun execute(context: CsContext) {
        context.variables[name] = value.evaluate(context)
    }
}

data class CsIdentifierNode(val name: String) : CsExpressionNode {
    override fun evaluate(context: CsContext): CsObject {
        return context.variables[name] ?: csError("Value for identifier '$name' not found.")
    }
}

data class CsLiteralNode(val value: CsObject) : CsExpressionNode {
    override fun evaluate(context: CsContext) = value
}

data class CsAttributeAccessNode(val value: CsExpressionNode, val name: String) : CsExpressionNode {
    override fun evaluate(context: CsContext): CsObject {
        return value.evaluate(context).getAttribute(name) ?: csError("Attribute '$name' not found.")
    }
}

data class CsCallNode(val value: CsExpressionNode, val args: List<CsExpressionNode>) : CsExpressionNode {
    override fun evaluate(context: CsContext): CsObject {
        return value.evaluate(context).invoke(args.map { it.evaluate(context) })
    }
}

data class CsNumericalInfixOperatorNode(
    val lhs: CsExpressionNode,
    val rhs: CsExpressionNode,
    val op: NumericalInfixOperator,
) : CsExpressionNode {
    override fun evaluate(context: CsContext): CsObject {
        val l = lhs.evaluate(context).number()
        val r = lhs.evaluate(context).number()
        return when (op) {
            NumericalInfixOperator.PLUS -> l + r
            NumericalInfixOperator.MINUS -> l - r
            NumericalInfixOperator.DIV -> l / r
            NumericalInfixOperator.TIMES -> l * r
            NumericalInfixOperator.MOD -> l % r
        }.asCsNumber()
    }
}

data class CsBooleanInfixOperatorNode(
    val lhs: CsExpressionNode,
    val rhs: CsExpressionNode,
    val op: BooleanInfixOperator,
) : CsExpressionNode {
    override fun evaluate(context: CsContext): CsObject {
        val l = lhs.evaluate(context).bool()
        return when (op) {
            BooleanInfixOperator.OR -> l || rhs.evaluate(context).bool()
            BooleanInfixOperator.AND -> l && rhs.evaluate(context).bool()
        }.asCsBoolean()
    }
}

data class CsComparisonOperatorNode(
    val lhs: CsExpressionNode,
    val rhs: CsExpressionNode,
    val op: ComparisonOperator,
) : CsExpressionNode {
    override fun evaluate(context: CsContext): CsObject {
        val l = lhs.evaluate(context)
        val r = lhs.evaluate(context)
        return when (op) {
            ComparisonOperator.EQUAL -> l == r
            ComparisonOperator.NOT_EQUAL -> l != r
            ComparisonOperator.LESS -> l.number() < r.number()
            ComparisonOperator.LESS_OR_EQUAL -> l.number() <= r.number()
            ComparisonOperator.GREATER -> l.number() > r.number()
            ComparisonOperator.GREATER_OR_EQUAL -> l.number() >= r.number()
        }.asCsBoolean()
    }
}

data class CsPosOperatorNode(
    val value: CsExpressionNode,
) : CsExpressionNode {
    override fun evaluate(context: CsContext): CsObject {
        return (+value.evaluate(context).number()).asCsNumber()
    }
}

data class CsNegOperatorNode(
    val value: CsExpressionNode,
) : CsExpressionNode {
    override fun evaluate(context: CsContext): CsObject {
        return (-value.evaluate(context).number()).asCsNumber()
    }
}

data class CsNotOperatorNode(
    val value: CsExpressionNode,
) : CsExpressionNode {
    override fun evaluate(context: CsContext): CsObject {
        return (!value.evaluate(context).bool()).asCsBoolean()
    }
}
