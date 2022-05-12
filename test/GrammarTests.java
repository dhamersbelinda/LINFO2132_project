import norswap.autumn.AutumnTestFixture;
import norswap.sigh.SighGrammar;
import norswap.sigh.ast.*;
import norswap.sigh.types.AtomType;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static java.util.Arrays.asList;
import static norswap.sigh.ast.BinaryOperator.*;

public class GrammarTests extends AutumnTestFixture {
    // ---------------------------------------------------------------------------------------------

    private final SighGrammar grammar = new SighGrammar();
    private final Class<?> grammarClass = grammar.getClass();

    // ---------------------------------------------------------------------------------------------

    private static IntLiteralNode intlit (long i) {
        return new IntLiteralNode(null, i);
    }

    private static FloatLiteralNode floatlit (double d) {
        return new FloatLiteralNode(null, d);
    }

    private static AtomLiteralNode atomlit (String a) {
        return new AtomLiteralNode(null, a);
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    public void testLiteralsAndUnary () {
        rule = grammar.expression;

        successExpect("42", intlit(42));
        //successExpect("_asd.", new AtomLiteralNode(null, "_asd"));
        /*successExpect("._a",
            new AtomDeclarationNode(null,
                new AtomLiteralNode(null, "_a")));*/
        successExpect("42.0", floatlit(42d));
        successExpect("\"hello\"", new StringLiteralNode(null, "hello"));
        successExpect("(42)", new ParenthesizedNode(null, intlit(42)));
        successExpect("[1, 2, 3]", new ArrayLiteralNode(null, asList(intlit(1), intlit(2), intlit(3))));
        successExpect("true", new ReferenceNode(null, "true"));
        successExpect("false", new ReferenceNode(null, "false"));
        successExpect("null", new ReferenceNode(null, "null"));
        successExpect("!false", new UnaryExpressionNode(null, UnaryOperator.NOT, new ReferenceNode(null, "false")));
    }

    @Test
    public void testLogicExpression () {
        rule = grammar.statement;

        successExpect(".._a", new AtomDeclarationNode(null, new AtomLiteralNode(null, "_a")));
        successExpect(".._atomFact", new AtomDeclarationNode(null, atomlit("_atomFact")));
        failure(".._"); //anonymous variable should only be used for unification
        failure("_atomFact"); //needs two DOTS

        successExpect("..dog(_poodle)",
            new PredicateDeclarationNode(null,
                new PredicateNode(null,
                    "dog", asList(atomlit("_poodle")))));
        successExpect("..dog(42)",
            new PredicateDeclarationNode(null,
                new PredicateNode(null,
                    "dog", asList(intlit(42)))));
        successExpect("..dog(_poodle, _labrador)",
            new PredicateDeclarationNode(null,
                new PredicateNode(null,
                        "dog", asList(atomlit("_poodle"), atomlit("_labrador")))));
        successExpect("..dog(_poodle, labrador)", //TODO this test should allow this to be parsed, but we need to check that there are no atoms in funcall
            new PredicateDeclarationNode(null,
                new PredicateNode(null, "dog", asList(atomlit("_poodle"), new ReferenceNode(null, "labrador"))
            )));
        successExpect("..dog(poodle)",
                new PredicateDeclarationNode(null,
                        new PredicateNode(null, "dog",
                                asList(
                                new ReferenceNode(null, "poodle")))
                ));
        failure(".._dog(_poodle)"); //functor should not be an atom identifier
    }

    @Test
    public void boolqueries () {
        rule = grammar.expression_stmt;
        /*successExpect("..boolean1 ?= 1 + 2",
            new ExpressionStatementNode(null,
            new BoolQueryNode(null,
                new ReferenceNode(null, "boolean1"),
                new BinaryExpressionNode(null, intlit(1), ADD, intlit(2)))
            ));*/
        success("..boolean1 ?= 1 + 2");
        successExpect("..boolean1 ?= _atomFact",
            new ExpressionStatementNode(null,
            new BoolQueryNode(null,
                new ReferenceNode(null, "boolean1"),
                atomlit("_atomFact"))
        ));
        successExpect("..boolean1 ?= dog(_poodle)",
            new ExpressionStatementNode(null,
            new BoolQueryNode(null,
                new ReferenceNode(null, "boolean1"),
                new PredicateNode(null, "dog",
                    asList(new AtomLiteralNode(null, "_poodle"))
                ))
        ));
        failure("..boolean1 ?= dog(_poodle) + 1");
        /*successExpect(".boolean1 ?= dog(_poodle) ?= _atomFact",
            new ExpressionStatementNode(null,
                new BoolQueryNode(null,
                    new ReferenceNode(null, "boolean1"),
                    new ExpressionStatementNode(null,
                        new BoolQueryNode(null,
                            new PredicateNode(null,
                                "dog",
                                asList(atomlit("_poodle"))),
                            atomlit("_atomFact"))))));*/
        failure("..boolean1 ?= dog(_poodle) ?= _atomFact");
        successExpect("..boolean1 ?= dog(1)",
            new ExpressionStatementNode(null,
                new BoolQueryNode(null,
                    new ReferenceNode(null, "boolean1"),
                    new PredicateNode(null,
                        "dog",
                        asList(intlit(1)))
                )));
        successExpect("..boolean1 ?= dog(cat(1))",
                new ExpressionStatementNode(null,
                        new BoolQueryNode(null,
                                new ReferenceNode(null, "boolean1"),
                                new PredicateNode(null,
                                        "dog",
                                        asList(new FunCallNode(null,
                                                new ReferenceNode(null, "cat"),
                                                asList(intlit(1))))
                        ))));
        /*
        successExpect("..boolean1 ?= true",
                new ExpressionStatementNode(null,
                        new BoolQueryNode(null,
                                new ReferenceNode(null, "boolean1"),
                                new BinaryExpressionNode(null, intlit(1), ADD, intlit(2)))
                ));*/
        success("..boolean1 ?= true");
        //TODO think of more tests
    }

    @Test
    public void predicate_rules () {
        rule = grammar.statement;
        successExpect("..cat(breed: Int) :- dog(breed)",
            new PredicateRuleNode(null,
                "cat",
                asList(new ParameterNode(null,
                    "breed",
                    new SimpleTypeNode(null,
                        "Int"))),
                new PredicateNode(null,
                    "dog",
                    asList(new ReferenceNode(null,
                            "breed"))))
            );
        failure("..cat(breed) :- { return true }"); // not right structure
        failure("..cat(breed: Int) :- true"); //TODO not (yet) a predicate
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    public void testNumericBinary () {
        rule = grammar.expression;
        successExpect("1 + 2", new BinaryExpressionNode(null, intlit(1), ADD, intlit(2)));
        successExpect("2 - 1", new BinaryExpressionNode(null, intlit(2), SUBTRACT,  intlit(1)));
        successExpect("2 * 3", new BinaryExpressionNode(null, intlit(2), MULTIPLY, intlit(3)));
        successExpect("2 / 3", new BinaryExpressionNode(null, intlit(2), DIVIDE, intlit(3)));
        successExpect("2 % 3", new BinaryExpressionNode(null, intlit(2), REMAINDER, intlit(3)));

        successExpect("1.0 + 2.0", new BinaryExpressionNode(null, floatlit(1), ADD, floatlit(2)));
        successExpect("2.0 - 1.0", new BinaryExpressionNode(null, floatlit(2), SUBTRACT, floatlit(1)));
        successExpect("2.0 * 3.0", new BinaryExpressionNode(null, floatlit(2), MULTIPLY, floatlit(3)));
        successExpect("2.0 / 3.0", new BinaryExpressionNode(null, floatlit(2), DIVIDE, floatlit(3)));
        successExpect("2.0 % 3.0", new BinaryExpressionNode(null, floatlit(2), REMAINDER, floatlit(3)));

        successExpect("2 * (4-1) * 4.0 / 6 % (2+1)", new BinaryExpressionNode(null,
            new BinaryExpressionNode(null,
                new BinaryExpressionNode(null,
                    new BinaryExpressionNode(null,
                        intlit(2),
                        MULTIPLY,
                        new ParenthesizedNode(null, new BinaryExpressionNode(null,
                            intlit(4),
                            SUBTRACT,
                            intlit(1)))),
                    MULTIPLY,
                    floatlit(4d)),
                DIVIDE,
                intlit(6)),
            REMAINDER,
            new ParenthesizedNode(null, new BinaryExpressionNode(null,
                intlit(2),
                ADD,
                intlit(1)))));
    }

    // ---------------------------------------------------------------------------------------------

    @Test public void testArrayStructAccess () {
        rule = grammar.expression;
        successExpect("[1][0]", new ArrayAccessNode(null,
            new ArrayLiteralNode(null, asList(intlit(1))), intlit(0)));
        successExpect("[1].length", new FieldAccessNode(null,
            new ArrayLiteralNode(null, asList(intlit(1))), "length"));
        successExpect("p.x", new FieldAccessNode(null, new ReferenceNode(null, "p"), "x"));
    }

    // ---------------------------------------------------------------------------------------------

    @Test public void testDeclarations() {
        rule = grammar.statement;

        successExpect("var x: Int = 1", new VarDeclarationNode(null,
            "x", new SimpleTypeNode(null, "Int"), intlit(1)));

        successExpect("struct P {}", new StructDeclarationNode(null, "P", asList()));

        successExpect("struct P { var x: Int; var y: Int }",
            new StructDeclarationNode(null, "P", asList(
                new FieldDeclarationNode(null, "x", new SimpleTypeNode(null, "Int")),
                new FieldDeclarationNode(null, "y", new SimpleTypeNode(null, "Int")))));

        successExpect("fun f (x: Int): Int { return 1 }",
            new FunDeclarationNode(null, "f",
                asList(new ParameterNode(null, "x", new SimpleTypeNode(null, "Int"))),
                new SimpleTypeNode(null, "Int"),
                new BlockNode(null, asList(new ReturnNode(null, intlit(1))))));
    }

    // ---------------------------------------------------------------------------------------------

    @Test public void testStatements() {
        rule = grammar.statement;

        successExpect("return", new ReturnNode(null, null));
        //successExpect("_a.", new ExpressionStatementNode(null, new AtomLiteralNode(null, "_a")));
        successExpect("return 1", new ReturnNode(null, intlit(1)));
        successExpect("print(1)", new ExpressionStatementNode(null,
            new FunCallNode(null, new ReferenceNode(null, "print"), asList(intlit(1)))));
        successExpect("{ return }", new BlockNode(null, asList(new ReturnNode(null, null))));


        successExpect("if true return 1 else return 2", new IfNode(null, new ReferenceNode(null, "true"),
            new ReturnNode(null, intlit(1)),
            new ReturnNode(null, intlit(2))));

        successExpect("if false return 1 else if true return 2 else return 3 ",
            new IfNode(null, new ReferenceNode(null, "false"),
                new ReturnNode(null, intlit(1)),
                new IfNode(null, new ReferenceNode(null, "true"),
                    new ReturnNode(null, intlit(2)),
                    new ReturnNode(null, intlit(3)))));

        successExpect("while 1 < 2 { return } ", new WhileNode(null,
            new BinaryExpressionNode(null, intlit(1), LOWER, intlit(2)),
            new BlockNode(null, asList(new ReturnNode(null, null)))));
    }

    // ---------------------------------------------------------------------------------------------
}
