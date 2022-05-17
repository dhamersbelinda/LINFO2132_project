package norswap.sigh.interpreter;

import norswap.sigh.SemanticAnalysis;
import norswap.sigh.ast.*;
import norswap.sigh.scopes.DeclarationContext;
import norswap.sigh.scopes.DeclarationKind;
import norswap.sigh.scopes.RootScope;
import norswap.sigh.scopes.Scope;
import norswap.sigh.scopes.SyntheticDeclarationNode;
import norswap.sigh.types.*;
import norswap.uranium.Reactor;
import norswap.utils.Util;
import norswap.utils.exceptions.Exceptions;
import norswap.utils.exceptions.NoStackException;
import norswap.utils.visitors.ValuedVisitor;

import java.util.*;

import static java.lang.String.format;
import static norswap.utils.Util.cast;
import static norswap.utils.Vanilla.coIterate;
import static norswap.utils.Vanilla.map;

/**
 * Implements a simple but inefficient interpreter for Sigh.
 *
 * <h2>Limitations</h2>
 * <ul>
 *     <li>The compiled code currently doesn't support closures (using variables in functions that
 *     are declared in some surroudning scopes outside the function). The top scope is supported.
 *     </li>
 * </ul>
 *
 * <p>Runtime value representation:
 * <ul>
 *     <li>{@code Int}, {@code Float}, {@code Bool}: {@link Long}, {@link Double}, {@link Boolean}</li>
 *     <li>{@code String}: {@link String}</li>
 *     <li>{@code null}: {@link Null#INSTANCE}</li>
 *     <li>Arrays: {@code Object[]}</li>
 *     <li>Structs: {@code HashMap<String, Object>}</li>
 *     <li>Functions: the corresponding {@link DeclarationNode} ({@link FunDeclarationNode} or
 *     {@link SyntheticDeclarationNode}), excepted structure constructors, which are
 *     represented by {@link Constructor}</li>
 *     <li>Types: the corresponding {@link StructDeclarationNode}</li>
 * </ul>
 */
public final class Interpreter
{
    // ---------------------------------------------------------------------------------------------

    private final ValuedVisitor<SighNode, Object> visitor = new ValuedVisitor<>();
    private final Reactor reactor;
    private ScopeStorage storage = null;
    private RootScope rootScope;
    private ScopeStorage rootStorage;

    // ---------------------------------------------------------------------------------------------

    public Interpreter (Reactor reactor) {
        this.reactor = reactor;

        // expressions
        visitor.register(IntLiteralNode.class,           this::intLiteral);
        visitor.register(FloatLiteralNode.class,         this::floatLiteral);
        visitor.register(StringLiteralNode.class,        this::stringLiteral);
        visitor.register(AtomLiteralNode.class,          this::atomLiteral);
        visitor.register(ReferenceNode.class,            this::reference);
        visitor.register(ConstructorNode.class,          this::constructor);
        visitor.register(ArrayLiteralNode.class,         this::arrayLiteral);
        visitor.register(ParenthesizedNode.class,        this::parenthesized);
        visitor.register(FieldAccessNode.class,          this::fieldAccess);
        visitor.register(ArrayAccessNode.class,          this::arrayAccess);
        visitor.register(FunCallNode.class,              this::funCall);
        visitor.register(UnaryExpressionNode.class,      this::unaryExpression);
        visitor.register(BinaryExpressionNode.class,     this::binaryExpression);
        visitor.register(AssignmentNode.class,           this::assignment);

        // logic stuff
        visitor.register(AtomDeclarationNode.class,       this::atomDecl);
        visitor.register(PredicateDeclarationNode.class,  this::predDecl);
        visitor.register(PredicateRuleNode.class,         this::predRule);
        visitor.register(BoolQueryNode.class,             this::boolQuery);
        visitor.register(UnificationNode.class,           this::unification);

        // statement groups & declarations
        visitor.register(RootNode.class,                 this::root);
        visitor.register(BlockNode.class,                this::block);
        visitor.register(VarDeclarationNode.class,       this::varDecl);
        // no need to visitor other declarations! (use fallback)

        // statements
        visitor.register(ExpressionStatementNode.class,  this::expressionStmt);
        visitor.register(IfNode.class,                   this::ifStmt);
        visitor.register(WhileNode.class,                this::whileStmt);
        visitor.register(ReturnNode.class,               this::returnStmt);

        visitor.registerFallback(node -> null);
    }

    // ---------------------------------------------------------------------------------------------

    public Object interpret (SighNode root) {
        try {
            return run(root);
        } catch (PassthroughException e) {
            throw Exceptions.runtime(e.getCause());
        }
    }

    // ---------------------------------------------------------------------------------------------

    private Object run (SighNode node) {
        try {
            return visitor.apply(node);
        } catch (InterpreterException | Return | PassthroughException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new InterpreterException("exception while executing " + node, e);
        }
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * Used to implement the control flow of the return statement.
     */
    private static class Return extends NoStackException {
        final Object value;
        private Return (Object value) {
            this.value = value;
        }
    }

    // ---------------------------------------------------------------------------------------------

    private <T> T get(SighNode node) {
        return cast(run(node));
    }

    // ---------------------------------------------------------------------------------------------

    private Long intLiteral (IntLiteralNode node) {
        return node.value;
    }

    private Double floatLiteral (FloatLiteralNode node) {
        return node.value;
    }

    private String stringLiteral (StringLiteralNode node) {
        return node.value;
    }

    private String atomLiteral (AtomLiteralNode node) {
        return node.name;
    }

    // ---------------------------------------------------------------------------------------------

    private Void predDecl (PredicateDeclarationNode node) {
        node.predicate.parameters.forEach(this::run);
        Scope scope = reactor.get(node, "scope");
        DeclarationNode decl = reactor.get(node, "decl");
        ScopeStorage x;
        if (decl instanceof PredicateDeclarationNode || decl instanceof PredicateRuleNode) //red
            scope = scope.lookup(node.predicate.name).scope;
        x = (scope == rootScope) ? rootStorage : storage;

        //we retrieve the list of rules and predicate declaration arguments that are already declared with the name
        //this list includes a list of previously declared predicate declaration arguments (arglist) and predicate rule nodes
        Object[] list = (Object[]) x.get(scope, node.predicate.name); //list with rules and arglist
        int pos = -1;
        //find position of arglist
        if (list == null) { //we create a new list if this is the first declaration
            list = new Object[0];
        }

        //find position of arglist
        for (int i = 0; i < list.length; i++) {
            if (!(list[i] instanceof PredicateRuleNode)) {
                pos = i;
                break;
            }
        }

        Object[] factList;
        if (pos == -1) //we create a new list for predicate fact arguments if there were none previously
            factList = new Object[0];
        else
            factList = (Object[]) list[pos];

        for (int i = 0; i < node.predicate.parameters.size(); i++) {
            //check if already present
            for (Object o : factList) {
                if (o.equals(get(node.predicate.parameters.get(i))))
                    throw new IllegalArgumentException("This argument was already provided for the same predicate.");
            }
            //add new declaration argument
            Object[] newList = new Object[factList.length+1];
            System.arraycopy(factList, 0, newList, 0, factList.length);
            newList[newList.length-1] = get(node.predicate.parameters.get(i));
            factList = newList;
        }

        //add to list
        if (pos == -1) {
            Object[] newList = new Object[list.length+1];
            System.arraycopy(list, 0, newList, 0, list.length);
            newList[newList.length-1] = factList;
            list = newList;
        } else {
            list[pos] = factList;
        }

        x.set(scope, node.predicate.name, list);

        return null;
    }

    private Void predRule (PredicateRuleNode node) {
        Scope scope = reactor.get(node, "scope");
        DeclarationNode decl = reactor.get(node, "decl");
        ScopeStorage x;
        if (decl instanceof PredicateDeclarationNode || decl instanceof PredicateRuleNode) //red //why
            scope = scope.lookup(node.name).scope;
        x = (scope == rootScope) ? rootStorage : storage;

        //we retrieve the list of rules and predicate declaration arguments that are already declared with the name
        Object[] list = (Object[]) x.get(scope, node.name);
        if (list==null) list = new Object[0];
        else {
            for (Object o : list) {
                if (!(o instanceof PredicateRuleNode) && (o.equals(node)))
                    throw new IllegalArgumentException();
            }
        }
        //we add the new predicate rule node to the scope with the name
        Object[] newList = new Object[list.length+1];
        System.arraycopy(list, 0, newList, 0, list.length);
        newList[newList.length-1] = node;
        x.set(scope, node.name, newList);
        return null;
    }

    private Void unification (UnificationNode node) {
        if (!node.left.name.equals(node.right.name))
            throw new InputMismatchException("Left expression and right expression don't use the same decleration");

        if (node.left.parameters.size() != node.right.parameters.size())
            throw new InputMismatchException("Left expression and right expression don't have matching signatures");

        for (int i=0; i<node.left.parameters.size(); i++) {
            SighNode left = node.left.parameters.get(i).arg;
            SighNode right = node.right.parameters.get(i).arg;
            Type leftT = reactor.get(left, "type");
            Type rightT = reactor.get(right, "type");

            if (left instanceof ParameterNode && right instanceof ParameterNode)
                throw new IllegalArgumentException("Arguments can't both be uninitialised variables");

            if (!(left instanceof ParameterNode || right instanceof ParameterNode) && get(right)!=get(left))
                throw new IllegalArgumentException("Arguments can't have different values");

            if (leftT != rightT)
                throw new InputMismatchException("Arguments don't have the same types");

            if (left instanceof ParameterNode) {
                assign(rootScope, ((ParameterNode) left).name, get(right), leftT);
            } else if (right instanceof ParameterNode) {
                assign(rootScope, ((ParameterNode) right).name, get(left), rightT);
            }
        }
        return null;
    }

    private boolean boolQuery (BoolQueryNode node) {

        Scope scope = reactor.get(node.left, "scope");
        String name = ((ReferenceNode) node.left).name;

        ExpressionNode toFind = node.right; //the expression that needs to be matched with prior declarations

        if (toFind instanceof AtomLiteralNode) {
            boolean result = retrieve(node, (AtomLiteralNode) toFind);
            assign(scope, name, result, reactor.get(node, "type"));
            return result;
        } else if (toFind instanceof LogicParenthesizedNode) {
            boolean result = retrieve(node, (LogicParenthesizedNode) toFind);
            assign(scope, name, result, reactor.get(node, "type"));
            return result;
        } else if (toFind instanceof LogicUnaryExpressionNode) {
            boolean result = retrieve(node, (LogicUnaryExpressionNode) toFind);
            assign(scope, name, result, reactor.get(node, "type"));
            return result;
        } else if (toFind instanceof LogicBinaryExpressionNode) {
            boolean result = retrieve(node, (LogicBinaryExpressionNode) toFind);
            assign(scope, name, result, reactor.get(node, "type"));
            return result;
        } else if (toFind instanceof PredicateNode) {
            boolean result = retrieve(node, (PredicateNode) toFind);
            assign(scope, name, result, reactor.get(node, "type"));
            return result;
        }
        throw new IllegalArgumentException("Illegal non-logic content in BoolQuery");
    }

    public boolean retrieve(BoolQueryNode node, PredicateNode toFind) {
        Scope scope = reactor.get(node.left, "scope");

        ScopeStorage sto = (scope == rootScope) ? rootStorage : storage;
        Object bigList = sto.get(scope, toFind.name()); //list containing rules and fact arguments
        if (bigList == null) { //the predicate (functor) had never been declared
            return false;
        }
        Object[] bigList2 = (Object[]) bigList;
        boolean posRes = false;
        for (Object toLook : bigList2) { //for every rule or list of declared fact arguments
            if (!(toLook instanceof PredicateRuleNode)) { //if it is the list of declared fact arguments
                boolean isClear = true;
                Object[] toMatchList = (Object[]) toLook; //list of args that have been declared
                for (ExpressionNode givenParam : toFind.parameters) {
                    boolean contained = false;
                    Object r1 = get(givenParam);
                    for (Object o : toMatchList) {
                        if (o.equals(r1))
                            contained = true;
                    }
                    if (!contained) { //a given argument (on the right side of the query) is not declared
                        isClear = false;
                        break;
                    }
                }
                if (!isClear)
                    posRes = false;
                else posRes = true; //we have found a complete match
            } else { //if it is a rule
                posRes =  predicateRuleVal(node, toFind, (PredicateRuleNode) toLook);
            }
            if (posRes) { //since a match is found, we can return true
                return true;
            }
        }
        return false;
    }
    public boolean predicateRuleVal(BoolQueryNode node, PredicateNode queriedPredicate, PredicateRuleNode ruleNode) {
        Scope scope = reactor.get(node.left, "scope");
        ScopeStorage sto = (scope == rootScope) ? rootStorage : storage;
        ExpressionNode ruleRight = ruleNode.right;

        //check consistency of queried predicate and rule
        List<ExpressionNode> given_args = queriedPredicate.parameters;
        List<ParameterNode> params = ruleNode.parameters;
        //size check
        if (given_args.size() != params.size()) {
            //throw new IllegalArgumentException("size not consistent");
            return false;
        }
        //type check
        for (int pos = 0; pos < given_args.size(); pos = pos + 1) {
            Type given_arg_type = reactor.get(given_args.get(pos), "type");
            Type param_type = reactor.get(params.get(pos), "type");
            if (!isAssignableTo(param_type, given_arg_type)) {
                //throw new IllegalArgumentException(format("Argument at position %d is should be of type %s", pos, param_type.name()));
                return false;
            }
        }

        //we need to retrieve whatever is on the right side of the rule
        if (ruleRight instanceof AtomLiteralNode)
            return retrieve(node, (AtomLiteralNode) ruleRight);
        else if (ruleRight instanceof PredicateNode)
            return replaceRetrieve(node, queriedPredicate, ruleNode, (PredicateNode) ruleRight);
        else if (ruleRight instanceof LogicParenthesizedNode)
            return replaceRetrieve(node, queriedPredicate, ruleNode, (LogicParenthesizedNode) ruleRight);
        else if (ruleRight instanceof LogicUnaryExpressionNode)
            return replaceRetrieve(node, queriedPredicate, ruleNode, (LogicUnaryExpressionNode) ruleRight);
        else if (ruleRight instanceof LogicBinaryExpressionNode)
            return replaceRetrieve(node, queriedPredicate, ruleNode, (LogicBinaryExpressionNode) ruleRight);
        else
            throw new IllegalArgumentException("Wrong type in PredicateRuleVal");
    }

    public boolean replaceRetrieve(BoolQueryNode node, PredicateNode queriedPredicate, PredicateRuleNode ruleNode, PredicateNode component) {
        Scope scope = reactor.get(node.left, "scope");
        ScopeStorage sto = (scope == rootScope) ? rootStorage : storage;

        //we check if the component has been declared
        Object componentDecl = sto.get(scope, component.name());
        if (componentDecl == null) { //the predicate (functor) had never been declared
            return false;
        }

        List<ExpressionNode> given_args = queriedPredicate.parameters;
        List<ParameterNode> params = ruleNode.parameters;
        List<ExpressionNode> args = component.parameters; //args of the component of a rule
        //make copy
        List<ExpressionNode> args_to_replace = new ArrayList<ExpressionNode>(args);

        for (int i = 0; i < args_to_replace.size(); i++) {
            ExpressionNode arg = args_to_replace.get(i);
            if (arg instanceof ReferenceNode) { //we need to find value
                int pos = -1;
                for (ParameterNode param : params) {
                    if (((ReferenceNode) arg).name.equals(param.name())) {
                        pos = params.indexOf(param);
                        //we have found the position of the argument in the queried predicate and can deduce its value
                        break;
                    }
                }

                //deduce value from given_args or from outer scope
                Object given_arg;
                if (pos != -1) //if found, retrieve value from queried predicate
                    given_arg = given_args.get(pos);
                else
                    given_arg = arg; //if not found, get from outer scope
                //check type consistency
                Type given_arg_type = reactor.get(given_arg, "type");
                Type arg_type = reactor.get(arg, "type");
                if (!isAssignableTo(arg_type, given_arg_type)) {
                    throw new IllegalArgumentException(format("Argument at position %d is should be of type %s", pos, arg_type.name()));
                    //return false;
                }
                args_to_replace.set(i, (ExpressionNode) given_arg); //replace with found value
            }
        }
        return retrieve(node, new PredicateNode(component.span, component.name, args_to_replace));

    }

    public boolean retrieve(BoolQueryNode node, AtomLiteralNode toFind) {
        Scope scope = reactor.get(node.left, "scope");
        DeclarationContext ctx = scope.lookup(toFind.name);
        boolean check = ctx != null;
        return check;
    }

    public boolean retrieve(BoolQueryNode node, LogicParenthesizedNode toFind) {
        ExpressionNode expression = toFind.expression;
        if (expression instanceof AtomLiteralNode)
            return retrieve(node, (AtomLiteralNode) expression);
        else if (expression instanceof LogicParenthesizedNode)
            return retrieve(node, (LogicParenthesizedNode)expression);
        else if (expression instanceof LogicUnaryExpressionNode)
            return retrieve(node, (LogicUnaryExpressionNode) expression);
        else if (expression instanceof LogicBinaryExpressionNode)
            return retrieve(node, (LogicBinaryExpressionNode)expression);
        else if (expression instanceof PredicateNode)
            return retrieve(node, (PredicateNode)expression);
        throw new IllegalArgumentException("Illegal type in LogicParenthesizedNode.");
    }

    public boolean replaceRetrieve(BoolQueryNode node, PredicateNode queriedPredicate, PredicateRuleNode ruleNode, LogicParenthesizedNode component) {
        ExpressionNode expression = component.expression;
        if (expression instanceof AtomLiteralNode)
            return retrieve(node, (AtomLiteralNode) expression);
        else if (expression instanceof LogicParenthesizedNode)
            return replaceRetrieve(node, queriedPredicate, ruleNode, (LogicParenthesizedNode) expression);
        else if (expression instanceof LogicUnaryExpressionNode)
            return replaceRetrieve(node, queriedPredicate, ruleNode, (LogicUnaryExpressionNode) expression);
        else if (expression instanceof LogicBinaryExpressionNode)
            return replaceRetrieve(node, queriedPredicate, ruleNode, (LogicBinaryExpressionNode) expression);
        else if (expression instanceof PredicateNode)
            return replaceRetrieve(node, queriedPredicate, ruleNode, (PredicateNode) expression);
        throw new IllegalArgumentException("Illegal type in LogicParenthesizedNode.");
    }

    public boolean retrieve(BoolQueryNode node, LogicUnaryExpressionNode toFind) {
        ExpressionNode expression = toFind.operand;
        if (expression instanceof AtomLiteralNode)
            return ! retrieve(node, (AtomLiteralNode) expression);
        else if (expression instanceof LogicParenthesizedNode)
            return ! retrieve(node, (LogicParenthesizedNode)expression);
        else if (expression instanceof LogicUnaryExpressionNode)
            return ! retrieve(node, (LogicUnaryExpressionNode) expression);
        else if (expression instanceof LogicBinaryExpressionNode)
            return ! retrieve(node, (LogicBinaryExpressionNode)expression);
        else if (expression instanceof PredicateNode)
            return ! retrieve(node, (PredicateNode)expression);
        throw new IllegalArgumentException("Illegal type in LogicUnaryExpressionNode.");
    }

    public boolean replaceRetrieve(BoolQueryNode node, PredicateNode queriedPredicate, PredicateRuleNode ruleNode, LogicUnaryExpressionNode component) {
        ExpressionNode expression = component.operand;
        if (expression instanceof AtomLiteralNode)
            return ! retrieve(node, (AtomLiteralNode) expression);
        else if (expression instanceof LogicParenthesizedNode)
            return ! replaceRetrieve(node, queriedPredicate, ruleNode, (LogicParenthesizedNode) expression);
        else if (expression instanceof LogicUnaryExpressionNode)
            return ! replaceRetrieve(node, queriedPredicate, ruleNode, (LogicUnaryExpressionNode) expression);
        else if (expression instanceof LogicBinaryExpressionNode)
            return ! replaceRetrieve(node, queriedPredicate, ruleNode, (LogicBinaryExpressionNode) expression);
        else if (expression instanceof PredicateNode)
            return ! replaceRetrieve(node, queriedPredicate, ruleNode, (PredicateNode) expression);
        throw new IllegalArgumentException("Illegal type in LogicUnaryExpressionNode");
    }

    public boolean retrieve(BoolQueryNode node, LogicBinaryExpressionNode toFind) {
        ExpressionNode expression1 = toFind.left;
        ExpressionNode expression2 = toFind.right;

        boolean check1 = false;
        boolean check2 = false;
        //TODO make checks if types are correct -> maybe in semantics it's sufficient
        if (expression1 instanceof AtomLiteralNode)
            check1 =  retrieve(node, (AtomLiteralNode) expression1);
        else if (expression1 instanceof LogicParenthesizedNode)
            check1 = retrieve(node, (LogicParenthesizedNode)expression1);
        else if (expression1 instanceof LogicUnaryExpressionNode)
            check1 = retrieve(node, (LogicUnaryExpressionNode) expression1);
        else if (expression1 instanceof LogicBinaryExpressionNode)
            check1 = retrieve(node, (LogicBinaryExpressionNode)expression1);
        else if (expression1 instanceof PredicateNode)
            check1 = retrieve(node, (PredicateNode)expression1);
        else
            throw new IllegalArgumentException("Illegal type in LogicBinaryExpressionNode.");

        if (expression2 instanceof AtomLiteralNode)
            check2 =  retrieve(node, (AtomLiteralNode) expression2);
        else if (expression2 instanceof LogicParenthesizedNode)
            check2 = retrieve(node, (LogicParenthesizedNode)expression2);
        else if (expression2 instanceof LogicUnaryExpressionNode)
            check2 = retrieve(node, (LogicUnaryExpressionNode) expression2);
        else if (expression2 instanceof LogicBinaryExpressionNode)
            check2 = retrieve(node, (LogicBinaryExpressionNode)expression2);
        else if (expression2 instanceof PredicateNode)
            check2 = retrieve(node, (PredicateNode)expression2);
        else
            throw new IllegalArgumentException("Illegal type in LogicBinaryExpressionNode.");

        if (toFind.operator == BinaryOperator.AND)
            return check1 && check2;
        else
            return check1 || check2;
    }

    public boolean replaceRetrieve(BoolQueryNode node, PredicateNode queriedPredicate, PredicateRuleNode ruleNode, LogicBinaryExpressionNode component) {
        ExpressionNode expression1 = component.left;
        ExpressionNode expression2 = component.right;

        boolean check1 = false;
        boolean check2 = false;
        //TODO make checks if types are correct -> maybe in semantics it's sufficient
        if (expression1 instanceof AtomLiteralNode)
            check1 =  retrieve(node, (AtomLiteralNode) expression1);
        else if (expression1 instanceof LogicParenthesizedNode)
            check1 = replaceRetrieve(node, queriedPredicate, ruleNode, (LogicParenthesizedNode) expression1);
        else if (expression1 instanceof LogicUnaryExpressionNode)
            check1 = replaceRetrieve(node, queriedPredicate, ruleNode, (LogicUnaryExpressionNode) expression1);
        else if (expression1 instanceof LogicBinaryExpressionNode)
            check1 = replaceRetrieve(node, queriedPredicate, ruleNode, (LogicBinaryExpressionNode) expression1);
        else if (expression1 instanceof PredicateNode)
            check1 = replaceRetrieve(node, queriedPredicate, ruleNode, (PredicateNode) expression1);
        else
            throw new IllegalArgumentException("Illegal type in LogicBinaryExpressionNode");

        if (expression2 instanceof AtomLiteralNode)
            check2 =  retrieve(node, (AtomLiteralNode) expression2);
        else if (expression2 instanceof LogicParenthesizedNode)
            check2 = replaceRetrieve(node, queriedPredicate, ruleNode, (LogicParenthesizedNode) expression2);
        else if (expression2 instanceof LogicUnaryExpressionNode)
            check2 = replaceRetrieve(node, queriedPredicate, ruleNode, (LogicUnaryExpressionNode) expression2);
        else if (expression2 instanceof LogicBinaryExpressionNode)
            check2 = replaceRetrieve(node, queriedPredicate, ruleNode, (LogicBinaryExpressionNode) expression2);
        else if (expression2 instanceof PredicateNode)
            check2 = replaceRetrieve(node, queriedPredicate, ruleNode, (PredicateNode) expression2);
        else
            throw new IllegalArgumentException("Illegal type in LogicBinaryExpressionNode.");

        if (component.operator == BinaryOperator.AND)
            return check1 && check2;
        else
            return check1 || check2;
    }



    // ---------------------------------------------------------------------------------------------

    private Object parenthesized (ParenthesizedNode node) {
        return get(node.expression);
    }

    // ---------------------------------------------------------------------------------------------

    private Object[] arrayLiteral (ArrayLiteralNode node) {
        return map(node.components, new Object[0], visitor);
    }

    // ---------------------------------------------------------------------------------------------

    private Object binaryExpression (BinaryExpressionNode node)
    {
        Type leftType  = reactor.get(node.left, "type");
        Type rightType = reactor.get(node.right, "type");

        // Cases where both operands should not be evaluated.
        switch (node.operator) {
            case OR:  return booleanOp(node, false);
            case AND: return booleanOp(node, true);
        }

        Object left  = get(node.left);
        Object right = get(node.right);

        if (node.operator == BinaryOperator.ADD
                && (leftType instanceof StringType || rightType instanceof StringType))
            return convertToString(left) + convertToString(right);

        boolean floating = leftType instanceof FloatType || rightType instanceof FloatType;
        boolean numeric  = floating || leftType instanceof IntType;

        if (numeric)
            return numericOp(node, floating, (Number) left, (Number) right);

        switch (node.operator) {
            case EQUALITY:
                return  leftType.isPrimitive() ? left.equals(right) : left == right;
            case NOT_EQUALS:
                return  leftType.isPrimitive() ? !left.equals(right) : left != right;
        }

        throw new Error("should not reach here");
    }

    // ---------------------------------------------------------------------------------------------

    private boolean booleanOp (BinaryExpressionNode node, boolean isAnd)
    {
        boolean left = get(node.left);
        return isAnd
                ? left && (boolean) get(node.right)
                : left || (boolean) get(node.right);
    }

    // ---------------------------------------------------------------------------------------------

    private Object numericOp
            (BinaryExpressionNode node, boolean floating, Number left, Number right)
    {
        long ileft, iright;
        double fleft, fright;

        if (floating) {
            fleft  = left.doubleValue();
            fright = right.doubleValue();
            ileft = iright = 0;
        } else {
            ileft  = left.longValue();
            iright = right.longValue();
            fleft = fright = 0;
        }

        Object result;
        if (floating)
            switch (node.operator) {
                case MULTIPLY:      return fleft *  fright;
                case DIVIDE:        return fleft /  fright;
                case REMAINDER:     return fleft %  fright;
                case ADD:           return fleft +  fright;
                case SUBTRACT:      return fleft -  fright;
                case GREATER:       return fleft >  fright;
                case LOWER:         return fleft <  fright;
                case GREATER_EQUAL: return fleft >= fright;
                case LOWER_EQUAL:   return fleft <= fright;
                case EQUALITY:      return fleft == fright;
                case NOT_EQUALS:    return fleft != fright;
                default:
                    throw new Error("should not reach here");
            }
        else
            switch (node.operator) {
                case MULTIPLY:      return ileft *  iright;
                case DIVIDE:        return ileft /  iright;
                case REMAINDER:     return ileft %  iright;
                case ADD:           return ileft +  iright;
                case SUBTRACT:      return ileft -  iright;
                case GREATER:       return ileft >  iright;
                case LOWER:         return ileft <  iright;
                case GREATER_EQUAL: return ileft >= iright;
                case LOWER_EQUAL:   return ileft <= iright;
                case EQUALITY:      return ileft == iright;
                case NOT_EQUALS:    return ileft != iright;
                default:
                    throw new Error("should not reach here");
            }
    }

    // ---------------------------------------------------------------------------------------------

    public Object assignment (AssignmentNode node)
    {
        if (node.left instanceof ReferenceNode) {
            Scope scope = reactor.get(node.left, "scope");
            String name = ((ReferenceNode) node.left).name;
            Object rvalue = get(node.right);
            assign(scope, name, rvalue, reactor.get(node, "type"));
            return rvalue;
        }

        if (node.left instanceof ArrayAccessNode) {
            ArrayAccessNode arrayAccess = (ArrayAccessNode) node.left;
            Object[] array = getNonNullArray(arrayAccess.array);
            int index = getIndex(arrayAccess.index);
            try {
                return array[index] = get(node.right);
            } catch (ArrayIndexOutOfBoundsException e) {
                throw new PassthroughException(e);
            }
        }

        if (node.left instanceof FieldAccessNode) {
            FieldAccessNode fieldAccess = (FieldAccessNode) node.left;
            Object object = get(fieldAccess.stem);
            if (object == Null.INSTANCE)
                throw new PassthroughException(
                    new NullPointerException("accessing field of null object"));
            Map<String, Object> struct = cast(object);
            Object right = get(node.right);
            struct.put(fieldAccess.fieldName, right);
            return right;
        }

        throw new Error("should not reach here");
    }

    // ---------------------------------------------------------------------------------------------

    private int getIndex (ExpressionNode node)
    {
        long index = get(node);
        if (index < 0)
            throw new ArrayIndexOutOfBoundsException("Negative index: " + index);
        if (index >= Integer.MAX_VALUE - 1)
            throw new ArrayIndexOutOfBoundsException("Index exceeds max array index (2ˆ31 - 2): " + index);
        return (int) index;
    }

    // ---------------------------------------------------------------------------------------------

    private Object[] getNonNullArray (ExpressionNode node)
    {
        Object object = get(node);
        if (object == Null.INSTANCE)
            throw new PassthroughException(new NullPointerException("indexing null array"));
        return (Object[]) object;
    }

    // ---------------------------------------------------------------------------------------------

    private Object unaryExpression (UnaryExpressionNode node)
    {
        // there is only NOT
        assert node.operator == UnaryOperator.NOT;
        return ! (boolean) get(node.operand);
    }

    // ---------------------------------------------------------------------------------------------

    private Object arrayAccess (ArrayAccessNode node)
    {
        Object[] array = getNonNullArray(node.array);
        try {
            return array[getIndex(node.index)];
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new PassthroughException(e);
        }
    }

    // ---------------------------------------------------------------------------------------------

    private Object root (RootNode node)
    {
        assert storage == null;
        rootScope = reactor.get(node, "scope");
        storage = rootStorage = new ScopeStorage(rootScope, null);
        storage.initRoot(rootScope);

        try {
            node.statements.forEach(this::run);
        } catch (Return r) {
            return r.value;
            // allow returning from the main script
        } finally {
            storage = null;
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------

    private Void block (BlockNode node) {
        Scope scope = reactor.get(node, "scope");
        storage = new ScopeStorage(scope, storage);
        node.statements.forEach(this::run);
        storage = storage.parent;
        return null;
    }

    // ---------------------------------------------------------------------------------------------

    private Constructor constructor (ConstructorNode node) {
        // guaranteed safe by semantic analysis
        return new Constructor(get(node.ref));
    }

    // ---------------------------------------------------------------------------------------------

    private Object expressionStmt (ExpressionStatementNode node) {
        get(node.expression);
        return null;  // discard value
    }

    // ---------------------------------------------------------------------------------------------

    private Object fieldAccess (FieldAccessNode node)
    {
        Object stem = get(node.stem);
        if (stem == Null.INSTANCE)
            throw new PassthroughException(
                new NullPointerException("accessing field of null object"));
        return stem instanceof Map
                ? Util.<Map<String, Object>>cast(stem).get(node.fieldName)
                : (long) ((Object[]) stem).length; // only field on arrays
    }

    // ---------------------------------------------------------------------------------------------

    private Object funCall (FunCallNode node)
    {
        Object decl = get(node.function);
        node.arguments.forEach(this::run);
        Object[] args = map(node.arguments, new Object[0], visitor);

        if (decl == Null.INSTANCE)
            throw new PassthroughException(new NullPointerException("calling a null function"));

        if (decl instanceof SyntheticDeclarationNode)
            return builtin(((SyntheticDeclarationNode) decl).name(), args);

        if (decl instanceof Constructor)
            return buildStruct(((Constructor) decl).declaration, args);

        ScopeStorage oldStorage = storage;
        Scope scope = reactor.get(decl, "scope");
        storage = new ScopeStorage(scope, storage);

        FunDeclarationNode funDecl = (FunDeclarationNode) decl;
        coIterate(args, funDecl.parameters,
                (arg, param) -> storage.set(scope, param.name, arg));

        try {
            get(funDecl.block);
        } catch (Return r) {
            return r.value;
        } finally {
            storage = oldStorage;
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------

    private Object builtin (String name, Object[] args)
    {
        assert name.equals("print"); // only one at the moment
        String out = convertToString(args[0]);
        System.out.println(out);
        return out;
    }

    // ---------------------------------------------------------------------------------------------

    private boolean comparePredicateParameter(ParameterNode parameter, ExpressionNode aNode, Scope scope)
    {
        String type = ((SimpleTypeNode) parameter.type).name;
        Object object = reactor.get(aNode, "type");
        return (type.equals("Bool") && object instanceof BoolType)
            || (type.equals("Int") && object instanceof IntType)
            || (type.equals("String") && object instanceof StringType)
            || (type.equals("Float") && object instanceof FloatType);
    }

    // ---------------------------------------------------------------------------------------------

    private String convertToString (Object arg)
    {
        if (arg == Null.INSTANCE)
            return "null";
        else if (arg instanceof Object[])
            return Arrays.deepToString((Object[]) arg);
        else if (arg instanceof FunDeclarationNode)
            return ((FunDeclarationNode) arg).name;
        else if (arg instanceof StructDeclarationNode)
            return ((StructDeclarationNode) arg).name;
        else if (arg instanceof Constructor)
            return "$" + ((Constructor) arg).declaration.name;
        else
            return arg.toString();
    }

    // ---------------------------------------------------------------------------------------------

    private HashMap<String, Object> buildStruct (StructDeclarationNode node, Object[] args)
    {
        HashMap<String, Object> struct = new HashMap<>();
        for (int i = 0; i < node.fields.size(); ++i)
            struct.put(node.fields.get(i).name, args[i]);
        return struct;
    }

    // ---------------------------------------------------------------------------------------------

    private Void ifStmt (IfNode node)
    {
        if (get(node.condition))
            get(node.trueStatement);
        else if (node.falseStatement != null)
            get(node.falseStatement);
        return null;
    }

    // ---------------------------------------------------------------------------------------------

    private Void whileStmt (WhileNode node)
    {
        while (get(node.condition))
            get(node.body);
        return null;
    }

    // ---------------------------------------------------------------------------------------------

    private Object reference (ReferenceNode node)
    {
        Scope scope = reactor.get(node, "scope");
        DeclarationNode decl = reactor.get(node, "decl");

        if (decl instanceof VarDeclarationNode
        || decl instanceof ParameterNode
        || decl instanceof SyntheticDeclarationNode
                && ((SyntheticDeclarationNode) decl).kind() == DeclarationKind.VARIABLE)
            return scope == rootScope
                ? rootStorage.get(scope, node.name)
                : storage.get(scope, node.name);

        return decl; // structure or function
    }

    // ---------------------------------------------------------------------------------------------

    private Void returnStmt (ReturnNode node) {
        throw new Return(node.expression == null ? null : get(node.expression));
    }

    // ---------------------------------------------------------------------------------------------

    private Void varDecl (VarDeclarationNode node)
    {
        Scope scope = reactor.get(node, "scope");
        assign(scope, node.name, get(node.initializer), reactor.get(node, "type"));
        return null;
    }

    private Void atomDecl (AtomDeclarationNode node)
    {
        Scope scope = reactor.get(node, "scope");
        assign(scope, node.atom.name, true, reactor.get(node, "type"));
        return null;
    }

    // ---------------------------------------------------------------------------------------------

    private void assign (Scope scope, String name, Object value, Type targetType)
    {
        if (value instanceof Long && targetType instanceof FloatType)
            value = ((Long) value).doubleValue();

        storage.set(scope, name, value);
    }

    // ---------------------------------------------------------------------------------------------
    private static boolean isAssignableTo (Type a, Type b)
    {
        if (a instanceof VoidType || b instanceof VoidType)
            return false;

        if (a instanceof IntType && b instanceof FloatType)
            return true;

        if (a instanceof ArrayType)
            return b instanceof ArrayType
                    && isAssignableTo(((ArrayType)a).componentType, ((ArrayType)b).componentType);

        return a instanceof NullType && b.isReference() || a.equals(b);
    }
}
