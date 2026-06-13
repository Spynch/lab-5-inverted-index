package searchengine.query;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import searchengine.query.antlr.SearchQueryBaseVisitor;
import searchengine.query.antlr.SearchQueryLexer;
import searchengine.query.antlr.SearchQueryParser;

import java.util.Objects;

public final class AntlrQueryParser implements QueryParser {
    @Override
    public QueryNode parse(String query) {
        Objects.requireNonNull(query, "query");
        SearchQueryLexer lexer = new SearchQueryLexer(CharStreams.fromString(query));
        ThrowingErrorListener errorListener = new ThrowingErrorListener();
        lexer.removeErrorListeners();
        lexer.addErrorListener(errorListener);
        SearchQueryParser parser = new SearchQueryParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        parser.addErrorListener(errorListener);
        return new AstBuilder().visit(parser.query());
    }

    private static final class AstBuilder extends SearchQueryBaseVisitor<QueryNode> {
        @Override
        public QueryNode visitQuery(SearchQueryParser.QueryContext ctx) {
            return visit(ctx.expression());
        }

        @Override
        public QueryNode visitExpression(SearchQueryParser.ExpressionContext ctx) {
            return visit(ctx.orExpression());
        }

        @Override
        public QueryNode visitOrExpression(SearchQueryParser.OrExpressionContext ctx) {
            QueryNode node = visit(ctx.andExpression(0));
            for (int i = 1; i < ctx.andExpression().size(); i++) {
                node = new OrNode(node, visit(ctx.andExpression(i)));
            }
            return node;
        }

        @Override
        public QueryNode visitAndExpression(SearchQueryParser.AndExpressionContext ctx) {
            QueryNode node = visit(ctx.unaryExpression(0));
            for (int i = 1; i < ctx.unaryExpression().size(); i++) {
                node = new AndNode(node, visit(ctx.unaryExpression(i)));
            }
            return node;
        }

        @Override
        public QueryNode visitUnaryExpression(SearchQueryParser.UnaryExpressionContext ctx) {
            if (ctx.NOT() != null) {
                return new NotNode(visit(ctx.unaryExpression()));
            }
            return visit(ctx.positionalExpression());
        }

        @Override
        public QueryNode visitPositionalExpression(SearchQueryParser.PositionalExpressionContext ctx) {
            QueryNode node = visit(ctx.primaryExpression(0));
            for (int i = 1; i < ctx.primaryExpression().size(); i++) {
                String operator = ctx.getChild(2 * i - 1).getText();
                QueryNode right = visit(ctx.primaryExpression(i));
                if (operator.equalsIgnoreCase("ADJ") || operator.equalsIgnoreCase("EDGE")) {
                    node = adjacent(node, right);
                } else if (operator.toUpperCase().startsWith("NEAR/")) {
                    node = new NearNode(node, right, Integer.parseInt(operator.substring(operator.indexOf('/') + 1)));
                } else {
                    throw new IllegalArgumentException("Unsupported positional operator: " + operator);
                }
            }
            return node;
        }

        @Override
        public QueryNode visitPrimaryExpression(SearchQueryParser.PrimaryExpressionContext ctx) {
            if (ctx.TERM() != null) {
                return new TermNode(ctx.TERM().getText());
            }
            return visit(ctx.expression());
        }

        private static QueryNode adjacent(QueryNode left, QueryNode right) {
            if (right instanceof AdjNode) {
                AdjNode adjacentRight = (AdjNode) right;
                return adjacent(new AdjNode(left, adjacentRight.getLeft()), adjacentRight.getRight());
            }
            return new AdjNode(left, right);
        }
    }

    private static final class ThrowingErrorListener extends BaseErrorListener {
        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line, int charPositionInLine,
                                String msg, RecognitionException e) {
            throw new IllegalArgumentException("Invalid query at " + line + ":" + charPositionInLine + " - " + msg, e);
        }
    }
}
