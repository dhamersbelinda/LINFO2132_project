import norswap.autumn.AutumnTestFixture;
import norswap.autumn.Grammar;
import norswap.autumn.Grammar.rule;
import norswap.autumn.ParseResult;
import norswap.autumn.positions.LineMapString;
import norswap.sigh.SemanticAnalysis;
import norswap.sigh.SighGrammar;
import norswap.sigh.ast.SighNode;
import norswap.sigh.interpreter.Interpreter;
import norswap.sigh.interpreter.InterpreterException;
import norswap.sigh.interpreter.Null;
import norswap.uranium.Reactor;
import norswap.uranium.SemanticError;
import norswap.utils.IO;
import norswap.utils.TestFixture;
import norswap.utils.data.wrappers.Pair;
import norswap.utils.visitors.Walker;
import org.testng.annotations.Test;
import java.util.HashMap;
import java.util.Set;

import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertThrows;

public final class InterpreterTests extends TestFixture {

    // TODO peeling

    // ---------------------------------------------------------------------------------------------

    private final SighGrammar grammar = new SighGrammar();
    private final AutumnTestFixture autumnFixture = new AutumnTestFixture();

    {
        autumnFixture.runTwice = false;
        autumnFixture.bottomClass = this.getClass();
    }

    // ---------------------------------------------------------------------------------------------

    private Grammar.rule rule;

    // ---------------------------------------------------------------------------------------------

    private void check (String input, Object expectedReturn) {
        assertNotNull(rule, "You forgot to initialize the rule field.");
        check(rule, input, expectedReturn, null);
    }

    // ---------------------------------------------------------------------------------------------

    private void check (String input, Object expectedReturn, String expectedOutput) {
        assertNotNull(rule, "You forgot to initialize the rule field.");
        check(rule, input, expectedReturn, expectedOutput);
    }

    // ---------------------------------------------------------------------------------------------

    private void check (rule rule, String input, Object expectedReturn, String expectedOutput) {
        // TODO
        // (1) write proper parsing tests
        // (2) write some kind of automated runner, and use it here

        autumnFixture.rule = rule;
        ParseResult parseResult = autumnFixture.success(input);
        SighNode root = parseResult.topValue();

        Reactor reactor = new Reactor();
        Walker<SighNode> walker = SemanticAnalysis.createWalker(reactor);
        Interpreter interpreter = new Interpreter(reactor);
        walker.walk(root);
        reactor.run();
        Set<SemanticError> errors = reactor.errors();

        if (!errors.isEmpty()) {
            LineMapString map = new LineMapString("<test>", input);
            String report = reactor.reportErrors(it ->
                it.toString() + " (" + ((SighNode) it).span.startString(map) + ")");
            //            String tree = AttributeTreeFormatter.format(root, reactor,
            //                    new ReflectiveFieldWalker<>(SighNode.class, PRE_VISIT, POST_VISIT));
            //            System.err.println(tree);
            throw new AssertionError(report);
        }

        Pair<String, Object> result = IO.captureStdout(() -> interpreter.interpret(root));
        assertEquals(result.b, expectedReturn);
        if (expectedOutput != null) assertEquals(result.a, expectedOutput);
    }

    // ---------------------------------------------------------------------------------------------

    private void checkExpr (String input, Object expectedReturn, String expectedOutput) {
        rule = grammar.root;
        check("return " + input, expectedReturn, expectedOutput);
    }

    // ---------------------------------------------------------------------------------------------

    private void checkExpr (String input, Object expectedReturn) {
        rule = grammar.root;
        check("return " + input, expectedReturn);
    }

    // ---------------------------------------------------------------------------------------------

    private void checkThrows (String input, Class<? extends Throwable> expected) {
        assertThrows(expected, () -> check(input, null));
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    public void testLiteralsAndUnary () {
        checkExpr("42", 42L);
        checkExpr("42.0", 42.0d);
        checkExpr("\"hello\"", "hello");
        checkExpr("(42)", 42L);
        checkExpr("[1, 2, 3]", new Object[]{1L, 2L, 3L});
        checkExpr("true", true);
        checkExpr("false", false);
        checkExpr("null", Null.INSTANCE);
        checkExpr("!false", true);
        checkExpr("!true", false);
        checkExpr("!!true", true);
    }

    @Test
    public void testLogicFacts () {
        rule = grammar.root;
        //unification
        checkThrows("..dog(1) = cat(1)", InterpreterException.class);
        checkThrows("..dog(1) = dog(1, 3)", InterpreterException.class);
        checkThrows("..dog(1, 3) = dog(a: Int)", InterpreterException.class);
        checkThrows("..dog(1, a: Int) = dog(1, b: Int)", InterpreterException.class);
        checkThrows("..dog(1) = dog(2)", InterpreterException.class);
        checkThrows("..dog(1, a: Atom) = dog(b: Int, 3)", InterpreterException.class);

        check("..dog(1) = dog(1)", null);
        check("..dog(1) = dog(a: Int); return a", 1L);
        check("..dog(1+2) = dog(a: Int); return a", 3L);
        check("..dog(a: Int) = dog(1+2); return a", 3L);
        check("..dog(1, a: Atom) = dog(1, _a); return a", "_a");
        check("..dog(1, a: Int) = dog(b: Int, 5); return a + b", 6L);
        check("var x: Atom = _atom; ..dog(2, x) = dog(2, a: Atom); return a", "_atom");

        //aliasing already exists
        check("var x: Bool = false; x = x; return x;", false);
        check("var y: Bool = false; var x: Bool = true; x = y; return x;", false);

        //predicate rules
        check("var x: Bool = false; ..cat(_x); ..dog(y: Bool) :- cat(_x);", null);
        check("var x: Bool = false; ..cat(_x); ..dog(y: Bool) :- cat(_x) && cat(y);", null);
        check("var a: Int = 1; var x: Bool = false; fun dog (a: Bool): Bool { return a }; ..x ?= dog(a); return x;", false);

        //fact declarations
        check(".._a", null);
        check(".._a; .._b",null);
        check(".._a; ..dog(_poodle)", null);
        check("..dog(_poodle, _labrador); ..dog(_persian)", null);
    }

    @Test
    public void testBoolQueries () {
        rule = grammar.root;

        check("var x: Bool = false; .._atomFact; ..x ?= _atomic; return x;", false);
        check("var x: Bool = false; .._atomFact; ..x ?= _atomFact; return x;", true);

        check("var x: Bool = false; ..dog(_poodle); ..x ?= dog(_poodle); return x;", true);
        check("var x: Bool = false; ..dog(42); ..x ?= dog(42); return x;", true);

        check("var x: Bool = true; ..dog(_poodle); ..x ?= dog(_labrador); return x;", false);
        check("var x: Bool = true; ..cat(_poodle); ..x ?= dog(_poodle); return x;", false);

        //"normal" boolean expressions are not allowed in boolQueries
        checkThrows("var x: Bool = true; var y: Bool = false; ..x ?= y; return x;", InterpreterException.class);
        checkThrows("var x: Bool = false; var y: Bool = true; ..x ?= y; return x;", InterpreterException.class);
        checkThrows("var x: Bool = false; var y: Bool = true; ..x ?= y || x; return x;", InterpreterException.class);
        checkThrows("var x: Bool = false; var y: Bool = true; ..x ?= y && x; return x;", InterpreterException.class);
    }

    @Test
    public void predicateRuleTests () {
        rule = grammar.root;

        check("..dog(breed: Atom) :- cat(breed);", null);
        check("..dog(breed: Atom) :- cat(breed);" +
                "var x: Bool = true; "+
                "..x ?= dog(_atomic);" +
                " return x;", false);
        check("..dog(breed: Atom) :- _atom;" +
                "var x: Bool = true; "+
                "..x ?= dog(_atomic);" +
                " return x;", false);
        check("..dog(breed: Atom) :- _atom;" +
                "var x: Bool = true; "+
                ".._atom;"+
                "..x ?= dog(_atomic);" +
                " return x;", true);
        check("..dog(breed: Atom) :- cat(breed);" +
                "var x: Bool = true; "+
                "..x ?= cat(_atomic);" +
                " return x;", false);
        check("..dog(breed: Atom) :- cat(breed);" +
                "var x: Bool = false; "+
                "..cat(_atomic)"+
                "..x ?= dog(_atomic);" +
                " return x;", true);
        check("..dog(breed: Atom) :- cat(breed);" +
                "var x: Bool = true; "+
                "..cat(_atomic)"+
                "..x ?= dog(_atomical);" +
                " return x;", false);
        check("..dog(breed: Atom) :- cat(breed);" +
                "var x: Bool = false; "+
                "..cat(_atomic)"+
                "..x ?= cat(_atomic);" +
                " return x;", true);

        check("..dog(breed: Atom) :- cat(breed, _atomic);" +
                "var x: Bool = false; "+
                "..cat(_atomic)"+
                "..cat(_atomical)"+
                "..x ?= dog(_atomical);" +
                " return x;", true);
        check("..dog(breed: Atom) :- cat(breed, _atomic);" +
                "var x: Bool = true; "+
                "..cat(_atomical)"+
                "..x ?= dog(_atomical);" +
                " return x;", false);
        check("..dog(breed: Atom) :- cat(breed, _siamese);" +
                "var x: Bool = true; "+
                "..cat(_atomical)"+
                "..x ?= dog(_atomical);" +
                " return x;", false);
        check("..dog(breed: Atom) :- cat(breed, _siamese);" +
                "var x: Bool = false; "+
                "..cat(_atomical)"+
                "..cat(_siamese)"+
                "..x ?= dog(_atomical);" +
                " return x;", true);
        check("..dog(breed: Atom) :- cat(breed, _atomic);" +
                "var x: Bool = true; "+
                "..cat(_atomic)"+
                "..x ?= dog(_atomical);" +
                " return x;", false);
        check("..dog(breed: Atom, size: Atom) :- cat(size, breed, _atomic);" +
                "var x: Bool = true; "+
                "..cat(_atomic)"+
                "..x ?= dog(_atomical, _atom);" +
                " return x;", false);
        check("..dog(breed: Atom, size: Atom) :- cat(size, breed, _atomic);" +
                "var x: Bool = false; "+
                "..cat(_atomic)"+
                "..cat(_atomical)"+
                "..cat(_atom)"+
                "..x ?= dog(_atomical, _atom);" +
                " return x;", true);
        check("..dog(breed: Int, size: Atom) :- cat(size, breed, _atomic);" +
                "var x: Bool = true; "+
                "..cat(_atomic)"+
                "..cat(1)"+
                "..cat(_atom)"+
                "..x ?= dog(1, _atom);" +
                " return x;", true);
        check("..dog(breed: Int, size: Atom) :- cat(size, breed, _atomic);" +
                "var x: Bool = true; "+
                "..cat(_atomic)"+
                "..cat(_atom)"+
                "..x ?= dog(1, _atom);" +
                " return x;", false);
        check("..dog(breed: Int, size: Atom) :- cat(size, breed, _atomic);" +
                "var x: Bool = true; "+
                "var i: Int = 1; "+
                "..cat(_atomic)"+
                "..cat(i)"+
                "..cat(_atom)"+
                "..x ?= dog(1, _atom);" +
                " return x;", true);

        //matching or non-matching types
        check("..dog(breed: Int, size: Atom) :- cat(size, breed, _atomic);" +
                "var x: Bool = true; "+
                "var i: Int = 1; "+
                "..cat(_atomic)"+
                "..cat(1)"+
                "..cat(_atom)"+
                "..x ?= dog(1, i);" +
                " return x;", false);
        check("..dog(breed: Int, size: Atom) :- cat(size, breed, _atomic);" +
                "var x: Bool = true; "+
                "var i: Int = 1; "+
                "..cat(_atomic)"+
                "..cat(1)"+
                "..cat(_atom)"+
                "..x ?= dog(2, i);" +
                " return x;", false);
        check("..dog(breed: Int, size: Atom) :- cat(size, breed, _atomic);" +
                "var x: Bool = true; "+
                "var i: Int = 1; "+
                "..cat(_atomic)"+
                "..cat(1)"+
                "..cat(_atom)"+
                "..x ?= dog(i, _atom);" +
                " return x;", true);
        check("..dog(breed: Int, size: Atom) :- cat(size, breed, _atomic);" +
                "var x: Bool = true; "+
                "var i: Int = 1; "+
                "..cat(_atomic)"+
                "..cat(1)"+
                "..cat(_atom)"+
                "..x ?= dog(_atomic, _atom);" +
                " return x;", false);
        check("var y: Int = 2;" +
                "..dog(breed: Int, size: Atom) :- cat(size, y, _atomic);" +
                "var x: Bool = true; "+
                "var i: Int = 1; "+
                "..cat(_atomic)"+
                "..cat(1)"+
                "..cat(_atom)"+
                "..x ?= dog(i, _atom);" +
                " return x;", false);
        check("var y: Int = 2;" +
                "..dog(breed: Int, size: Atom) :- cat(size, y, _atomic);" +
                "var x: Bool = true; "+
                "var i: Int = 1; "+
                "..cat(_atomic)"+
                "..cat(1)"+
                "..cat(_atom)"+
                "..cat(2)" +
                "..x ?= dog(i, _atom);" +
                " return x;", true);
        check("var y: Int = 2;" +
                "..dog(breed: Int, size: Atom) :- cat(size, y, _atomic);" +
                "var x: Bool = true; "+
                "var i: Int = 1; "+
                "..cat(_atomic)"+
                "..cat(1)"+
                "..cat(_atom)"+
                "..cat(y)" +
                "..x ?= dog(i, _atom);" +
                " return x;", true);

        //tests with references
        check("var breed: Int = 3;" +
                "..dog(breed: Int) :- cat(breed, _atomic);" +
                "var x: Bool = true; "+
                "var i: Int = 1; "+
                "..cat(breed)"+ //3
                "..cat(_atomic)"+
                "breed = 2"+
                "..x ?= dog(breed);" +
                " return x;", false);
        check("var breed: Int = 3;" +
                "..dog(breed: Int) :- cat(breed, _atomic);" +
                "var x: Bool = true; "+
                "var i: Int = 1; "+
                "..cat(breed)"+ //3
                "..cat(_atomic)"+
                "..x ?= dog(breed);" +
                " return x;", true);
        check("var breed: Int = 3;" +
                "..dog(size: Int) :- cat(size, _atomic);" +
                "var x: Bool = true; "+
                "var i: Int = 1; "+
                "..cat(breed)"+ //3
                "..cat(_atomic)"+
                "..x ?= dog(breed);" +
                " return x;", true);
        check("var breed: Int = 3;" +
                "..dog(size: Int) :- cat(size, _atomic);" +
                "var x: Bool = true; "+
                "var i: Int = 1; "+
                "..cat(breed)"+ //3
                "..cat(_atomic)"+
                "..x ?= dog(1+2);" +
                " return x;", true);
        check("var breed: Int = 3;" +
                "..dog(size: Int) :- cat(size, 3+4);" +
                "var x: Bool = true; "+
                "var i: Int = 1; "+
                "..cat(breed)"+ //3
                "..cat(7)"+
                "..x ?= dog(breed);" +
                " return x;", true);
        checkThrows("var breed: Int = 3;" +
                "..dog(size: Int) :- cat(size, 3+4);" +
                "var x: Bool = true; "+
                "var i: Int = 1; "+
                "..cat(breed)"+ //3
                "..cat(3)"+
                "..x ?= dog(breed);" +
                " return x;", InterpreterException.class); //same thing declared twice

        //tests with several declarations (rules and facts)
        check("..dog(breed: Int, size: Atom) :- cat(size, breed);" +
                "..dog(3);"+
                "var x: Bool = true; "+
                "..cat(_atomic)"+
                "..cat(1)"+
                "..x ?= dog(1, _atomic) && dog(3);" +
                " return x;", true);
        check("..dog(breed: Int, size: Atom) :- cat(size, breed);" +
                "..dog(3);"+
                "var x: Bool = true; "+
                "..cat(_atomic)"+
                "..cat(1)"+
                "..x ?= dog(1, _atomic) && dog(2);" +
                " return x;", false);
        check("..dog(breed: Int, size: Atom) :- cat(size, breed);" +
                "var x: Bool = true; "+
                "..cat(_atomic)"+
                "..cat(1)"+
                "..x ?= dog(1, _atomic) || dog(3);" +
                " return x;", true);

    }

    @Test
    public void predicateRuleCombiTests () {
        rule = grammar.root;
        //rule in rule
        check("..dog(breed : Int) :- cat(breed);" +
                "..cat(tail : Int) :- mouse(tail)" +
                "var x: Bool = false; "+
                "var i: Int = 1; " +
                "..mouse(i)" +
                "..x ?= dog(1);" +
                "return x", true);
        check("..dog(breed : Int) :- cat(breed);" +
                "..cat(tail : Int) :- mouse(tail)" +
                "var x: Bool = false; "+
                "var i: Int = 1; " +
                "..mouse(i)" +
                "..x ?= dog(2);" +
                "return x", false);
        check("..dog(breed : Int) :- cat(breed);" +
                "..cat(tail : Float) :- mouse(tail)" +
                "var x: Bool = false; "+
                "var i: String = \"hey\"; " +
                "..mouse(i)" +
                "..x ?= dog(2);" +
                "return x", false);

        //combirules
        check("..dog(breed : Int) :- cat(breed) && mouse(_atomic);" +
                "var x: Bool = false; "+
                "var i: Int = 1; " +
                "..cat(i);" +
                "..mouse(_atomic);" +
                "..x ?= dog(1);" +
                "return x", true);

        check("..dog(breed : Int) :- cat(breed) && mouse(_atomic);" +
                "var x: Bool = false; "+
                "var i: Int = 1; " +
                "..cat(i);" +
                "..x ?= dog(1);" +
                "return x", false);

        check("..dog(breed : Int) :- cat(breed) && mouse(_atomic);" +
                "var x: Bool = false; "+
                "var i: Int = 1; " +
                "..cat(i);" +
                "..mouse(_atom);" +
                "..x ?= dog(1);" +
                "return x", false);
        check("..dog(breed : Int) :- cat(breed) && mouse(_atomic);" +
                "var x: Bool = false; "+
                "var i: Int = 1; " +
                "..cat(1);" +
                "..mouse(_atomic);" +
                "..x ?= dog(i);" +
                "return x", true);

        check("..dog(breed : Int) :- horse(breed) || (cat(breed) && mouse(_atomic));" +
                "var x: Bool = false; "+
                "var i: Int = 1; " +
                "..cat(1);" +
                "..mouse(_atomic);" +
                "..x ?= dog(i);" +
                "return x", true);
        check("..dog(breed : Int) :- horse(breed) && (cat(breed) && mouse(_atomic));" +
                "var x: Bool = false; "+
                "var i: Int = 1; " +
                "..cat(1);" +
                "..mouse(_atomic);" +
                "..x ?= dog(i);" +
                "return x", false);
        check("..dog(breed : Int) :- horse(breed) && (cat(breed) && mouse(_atomic));" +
                "var x: Bool = false; "+
                "var i: Int = 1; " +
                "..horse(\"hey\");" +
                "..cat(1);" +
                "..mouse(_atomic);" +
                "..x ?= dog(i);" +
                "return x", false);
        check("..dog(breed : Int) :- horse(breed) || (cat(breed) && mouse(_atomic));" +
                "var x: Bool = false; "+
                "var i: Int = 1; " +
                "..horse(2);" +
                "..cat(1);" +
                "..mouse(_atomic);" +
                "..x ?= dog(i);" +
                "return x", true);

        //querycombi
        check("..dog(breed : Int) :- horse(breed) || (cat(breed) && mouse(_atomic));" +
                "var x: Bool = false; "+
                "var i: Int = 1; " +
                "..horse(2);" +
                "..cat(1);" +
                "..mouse(_atomic);" +
                "..x ?= dog(i) && dog(2);" +
                "return x", true);

        check("..dog(breed : Int) :- horse(breed) || (cat(breed) && mouse(_atomic));" +
                "var x: Bool = false; "+
                "var i: Int = 1; " +
                "..horse(2);" +
                "..cat(1);" +
                "..mouse(_atomic);" +
                "..x ?= dog(i) && dog(2);" +
                "return x", true);
        check("..dog(breed : Int) :- horse(breed) || (cat(breed) && mouse(_atomic));" +
                "var x: Bool = false; "+
                "var i: Int = 1; " +
                "..horse(1);" +
                "..cat(1);" +
                "..mouse(_atomic);" +
                "..x ?= !(dog(i) && dog(2));" +
                "return x", true);
        check("..dog(breed : Int) :- horse(breed) || (cat(breed) && mouse(_atomic));" +
                "var x: Bool = false; "+
                "var i: Int = 1; " +
                "..horse(1);" +
                "..cat(1);" +
                "..mouse(_atomic);" +
                "..x ?= dog(2) || dog(i);" +
                "return x", true);
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    public void testNumericBinary () {
        checkExpr("1 + 2", 3L);
        checkExpr("2 - 1", 1L);
        checkExpr("2 * 3", 6L);
        checkExpr("2 / 3", 0L);
        checkExpr("3 / 2", 1L);
        checkExpr("2 % 3", 2L);
        checkExpr("3 % 2", 1L);

        checkExpr("1.0 + 2.0", 3.0d);
        checkExpr("2.0 - 1.0", 1.0d);
        checkExpr("2.0 * 3.0", 6.0d);
        checkExpr("2.0 / 3.0", 2d / 3d);
        checkExpr("3.0 / 2.0", 3d / 2d);
        checkExpr("2.0 % 3.0", 2.0d);
        checkExpr("3.0 % 2.0", 1.0d);

        checkExpr("1 + 2.0", 3.0d);
        checkExpr("2 - 1.0", 1.0d);
        checkExpr("2 * 3.0", 6.0d);
        checkExpr("2 / 3.0", 2d / 3d);
        checkExpr("3 / 2.0", 3d / 2d);
        checkExpr("2 % 3.0", 2.0d);
        checkExpr("3 % 2.0", 1.0d);

        checkExpr("1.0 + 2", 3.0d);
        checkExpr("2.0 - 1", 1.0d);
        checkExpr("2.0 * 3", 6.0d);
        checkExpr("2.0 / 3", 2d / 3d);
        checkExpr("3.0 / 2", 3d / 2d);
        checkExpr("2.0 % 3", 2.0d);
        checkExpr("3.0 % 2", 1.0d);

        checkExpr("2 * (4-1) * 4.0 / 6 % (2+1)", 1.0d);
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    public void testOtherBinary () {
        checkExpr("true  && true",  true);
        checkExpr("true  || true",  true);
        checkExpr("true  || false", true);
        checkExpr("false || true",  true);
        checkExpr("false && true",  false);
        checkExpr("true  && false", false);
        checkExpr("false && false", false);
        checkExpr("false || false", false);

        checkExpr("1 + \"a\"", "1a");
        checkExpr("\"a\" + 1", "a1");
        checkExpr("\"a\" + true", "atrue");

        checkExpr("1 == 1", true);
        checkExpr("1 == 2", false);
        checkExpr("1.0 == 1.0", true);
        checkExpr("1.0 == 2.0", false);
        checkExpr("true == true", true);
        checkExpr("false == false", true);
        checkExpr("true == false", false);
        checkExpr("1 == 1.0", true);
        checkExpr("[1] == [1]", false);

        checkExpr("1 != 1", false);
        checkExpr("1 != 2", true);
        checkExpr("1.0 != 1.0", false);
        checkExpr("1.0 != 2.0", true);
        checkExpr("true != true", false);
        checkExpr("false != false", false);
        checkExpr("true != false", true);
        checkExpr("1 != 1.0", false);

        checkExpr("\"hi\" != \"hi2\"", true);
        checkExpr("[1] != [1]", true);

         // test short circuit
        checkExpr("true || print(\"x\") == \"y\"", true, "");
        checkExpr("false && print(\"x\") == \"y\"", false, "");
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    public void testVarDecl () {
        check("var x: Int = 1; return x", 1L);
        check("var x: Float = 2.0; return x", 2d);

        check("var x: Int = 0; return x = 3", 3L);
        check("var x: String = \"0\"; return x = \"S\"", "S");

        // implicit conversions
        check("var x: Float = 1; x = 2; return x", 2.0d);
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    public void testRootAndBlock () {
        rule = grammar.root;
        check("return", null);
        check("return 1", 1L);
        check("return 1; return 2", 1L);

        check("print(\"a\")", null, "a\n");
        check("print(\"a\" + 1)", null, "a1\n");
        check("print(\"a\"); print(\"b\")", null, "a\nb\n");

        check("{ print(\"a\"); print(\"b\") }", null, "a\nb\n");

        check(
            "var x: Int = 1;" +
            "{ print(\"\" + x); var x: Int = 2; print(\"\" + x) }" +
            "print(\"\" + x)",
            null, "1\n2\n1\n");
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    public void testCalls () {
        rule = grammar.root;
        check(
            "fun add (a: Int, b: Int): Int { return a + b } " +
                "return add(4, 7)",
            11L);

        HashMap<String, Object> point = new HashMap<>();
        point.put("x", 1L);
        point.put("y", 2L);

        check(
            "struct Point { var x: Int; var y: Int }" +
                "return $Point(1, 2)",
            point);

        check("var str: String = null; return print(str + 1)", "null1", "null1\n");
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    public void testArrayStructAccess () {
        checkExpr("[1][0]", 1L);
        checkExpr("[1.0][0]", 1d);
        checkExpr("[1, 2][1]", 2L);

        // TODO check that this fails (& maybe improve so that it generates a better message?)
        // or change to make it legal (introduce a top type, and make it a top type array if thre
        // is no inference context available)
        // checkExpr("[].length", 0L);
        checkExpr("[1].length", 1L);
        checkExpr("[1, 2].length", 2L);

        checkThrows("var array: Int[] = null; return array[0]", NullPointerException.class);
        checkThrows("var array: Int[] = null; return array.length", NullPointerException.class);

        check("var x: Int[] = [0, 1]; x[0] = 3; return x[0]", 3L);
        checkThrows("var x: Int[] = []; x[0] = 3; return x[0]",
            ArrayIndexOutOfBoundsException.class);
        checkThrows("var x: Int[] = null; x[0] = 3",
            NullPointerException.class);

        check(
            "struct P { var x: Int; var y: Int }" +
                "return $P(1, 2).y",
            2L);

        checkThrows(
            "struct P { var x: Int; var y: Int }" +
                "var p: P = null;" +
                "return p.y",
            NullPointerException.class);

        check(
            "struct P { var x: Int; var y: Int }" +
                "var p: P = $P(1, 2);" +
                "p.y = 42;" +
                "return p.y",
            42L);

        checkThrows(
            "struct P { var x: Int; var y: Int }" +
                "var p: P = null;" +
                "p.y = 42",
            NullPointerException.class);
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    public void testIfWhile () {
        check("if (true) return 1 else return 2", 1L);
        check("if (false) return 1 else return 2", 2L);
        check("if (false) return 1 else if (true) return 2 else return 3 ", 2L);
        check("if (false) return 1 else if (false) return 2 else return 3 ", 3L);

        check("var i: Int = 0; while (i < 3) { print(\"\" + i); i = i + 1 } ", null, "0\n1\n2\n");
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    public void testInference () {
        check("var array: Int[] = []", null);
        check("var array: String[] = []", null);
        check("fun use_array (array: Int[]) {} ; use_array([])", null);
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    public void testTypeAsValues () {
        check("struct S{} ; return \"\"+ S", "S");
        check("struct S{} ; var type: Type = S ; return \"\"+ type", "S");
    }

    // ---------------------------------------------------------------------------------------------

    @Test public void testUnconditionalReturn()
    {
        check("fun f(): Int { if (true) return 1 else return 2 } ; return f()", 1L);
    }

    // ---------------------------------------------------------------------------------------------

    // NOTE(norswap): Not incredibly complete, but should cover the basics.
}
