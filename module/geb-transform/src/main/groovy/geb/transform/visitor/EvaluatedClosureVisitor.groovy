package geb.transform.visitor

import geb.transform.Runtime
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.builder.AstBuilder
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.ast.stmt.Statement
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.messages.SyntaxErrorMessage
import org.codehaus.groovy.syntax.SyntaxException
import org.codehaus.groovy.ast.expr.*
import static org.codehaus.groovy.syntax.Types.ASSIGNMENT_OPERATOR
import static org.codehaus.groovy.syntax.Types.ofType

class EvaluatedClosureVisitor extends ClassCodeVisitorSupport {
	SourceUnit sourceUnit

	EvaluatedClosureVisitor(SourceUnit sourceUnit) {
		this.sourceUnit = sourceUnit
	}

	@Override
	void visitField(FieldNode node) {
		if (node.static && node.name == 'at') {
			if (node.initialExpression in ClosureExpression) {
				rewriteClosureStatements(node.initialExpression)
			}
		}
	}

	@Override
	void visitExpressionStatement(ExpressionStatement statement) {
		if (statement.expression in MethodCallExpression) {
			MethodCallExpression expression = statement.expression
			if (expression.methodAsString == 'waitFor' && expression.arguments in ArgumentListExpression) {
				ArgumentListExpression arguments = expression.arguments
				if (arguments.expressions && arguments.expressions[-1] in ClosureExpression) {
					rewriteClosureStatements(arguments.expressions[-1])
				}
			}
		}
	}

	@Override
	protected SourceUnit getSourceUnit() {
		sourceUnit
	}

	private void rewriteClosureStatements(ClosureExpression closureExpression) {
		BlockStatement blockStatement = closureExpression.code
		ListIterator iterator = blockStatement.statements.listIterator()
		while (iterator.hasNext()) {
			iterator.set(rewriteClosureStatement(iterator.next()))
		}
		iterator.add(new ExpressionStatement(new ConstantExpression(true)))
	}

	private Statement rewriteClosureStatement(Statement statement) {
		Statement result = statement
		Expression toBeRewritten = getExpressionToBeRewritten(statement)
		if (toBeRewritten) {
			result = encloseWithVoidCheckAndAssert(toBeRewritten, statement)
		}
		return result
	}

	private Expression getExpressionToBeRewritten(Statement statement) {
		if (statement in ExpressionStatement) {
			ExpressionStatement expressionStatement = statement
			if (!(expressionStatement.expression in DeclarationExpression)
					&& checkIsValidCondition(expressionStatement)) {
				return expressionStatement.expression
			}
		}
	}

	boolean checkIsValidCondition(ExpressionStatement statement) {
		if (statement.expression in BinaryExpression) {
			BinaryExpression binaryExpression = statement.expression
			if (ofType(binaryExpression.operation.type, ASSIGNMENT_OPERATOR)) {
				reportError(statement, "Expected a condition, but found an assignment. Did you intend to write '==' ?")
				false
			}
		}
		true
	}

	private Statement encloseWithVoidCheckAndAssert(Expression toBeRewritten, Statement original) {
		if (toBeRewritten in MethodCallExpression) {
			MethodCallExpression rewrittenMethodCall = toBeRewritten
			new AstBuilder().buildFromSpec {
				ifStatement {
					booleanExpression {
						staticMethodCall(Runtime, 'isVoidMethod') {
							argumentList {
								expression.add(rewrittenMethodCall.objectExpression)
								expression.add(rewrittenMethodCall.method)
								expression.add(toArgumentArray(rewrittenMethodCall.arguments))
							}
						}
					}
					expression {
						expression.add(toBeRewritten)
					}
					assertStatement {
						booleanExpression {
							expression.add(toBeRewritten)
						}
					}
					expression.first().setSourcePosition(original)
				}
			}.first()
		} else {
			Statement result = new AstBuilder().buildFromSpec {
				assertStatement {
					booleanExpression {
						expression.add(toBeRewritten)
					}
				}
			}.first()
			result.setSourcePosition(original)
			result
		}
	}

	private Expression toArgumentArray(Expression arguments) {
		List<Expression> argumentList
		if (arguments instanceof NamedArgumentListExpression) {
			argumentList = [arguments]
		} else {
			TupleExpression tuple = arguments
			argumentList = tuple.expressions
		}
		List<SpreadExpression> spreadExpressions = argumentList.findAll { it in SpreadExpression }
		if (spreadExpressions) {
			spreadExpressions.each { reportError(it, 'Spread expressions are not allowed here') }
			return null
		} else {
			new ArrayExpression(ClassHelper.OBJECT_TYPE, argumentList);
		}
	}

	private void reportError(ASTNode node, String message) {
		def line = node.lineNumber > 0 ? node.lineNumber : node.lastLineNumber
		def column = node.columnNumber > 0 ? node.columnNumber : node.lastColumnNumber
		def errorMessage = new SyntaxErrorMessage(new SyntaxException(message, line, column), sourceUnit)
		sourceUnit.errorCollector.addErrorAndContinue(errorMessage)
	}
}

