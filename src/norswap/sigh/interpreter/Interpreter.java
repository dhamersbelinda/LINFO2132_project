package norswap.sigh.interpreter;

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

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        visitor.register(BoolQueryNode.class,             this::query);

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
        //funcall
        node.predicate.parameters.forEach(this::run);
        Scope scope = reactor.get(node, "scope");
        DeclarationNode decl = reactor.get(node, "decl");
        ScopeStorage x;
        if (decl instanceof PredicateDeclarationNode || decl instanceof PredicateRuleNode) //red
            scope = scope.lookup(node.predicate.name).scope;
        x = (scope == rootScope) ? rootStorage : storage;
        for (int i = 0; i < node.predicate.parameters.size(); i++) {
            Object[] list = (Object[]) x.get(scope, node.predicate.name);
            if (list==null) list = new Object[0];
            else {
                for (Object o : list) {
                    if (o.equals(node.predicate.parameters.get(i)))
                        throw new IllegalArgumentException();
                }
            }
            Object[] newList = new Object[list.length+1];
            System.arraycopy(list, 0, newList, 0, list.length);
            newList[newList.length-1] = node.predicate.parameters.get(i);
            x.set(scope, node.predicate.name, newList);
        }
        return null;
    }

    private Void predRule (PredicateRuleNode node) {
        //funcall
        Scope scope = reactor.get(node, "scope");
        DeclarationNode decl = reactor.get(node, "decl");
        ScopeStorage x;
        if (decl instanceof PredicateDeclarationNode || decl instanceof PredicateRuleNode) //red //why
            scope = scope.lookup(node.name).scope;
        x = (scope == rootScope) ? rootStorage : storage;
        //todo removed x.set(scope, node.toString(), node);

        /*
        Object[] list = (Object[]) x.get(scope, node.name);
        if (list==null) list = new Object[0];
        else {
            for (Object o : list) {
                if (o.equals(node))
                    throw new IllegalArgumentException();
            }
        }
        Object[] newList = new Object[list.length+1];
        System.arraycopy(list, 0, newList, 0, list.length);
        newList[newList.length-1] = node;
        x.set(scope, node.name, newList);
        */
        x.set(scope, node.name, node);
        return null;
    }

    private boolean query (BoolQueryNode node) {

        Scope scope = reactor.get(node.left, "scope");
        String name = ((ReferenceNode) node.left).name;

        if (node.right instanceof AtomLiteralNode) {
            //need to check if present in declarations of scope -> look for existence of context
            DeclarationContext ctx = scope.lookup(((AtomLiteralNode) node.right).name);
            boolean check = ctx != null;
            assign(scope, name, check, reactor.get(node, "type"));
            return check;
        } else if (node.right instanceof PredicateNode) {
            //check in predicate declarations
            ScopeStorage sto = (scope == rootScope) ? rootStorage : storage;
            Object toLook = sto.get(scope, ((PredicateNode) node.right).name()); //Object[] or PredicateRuleNode
            if (toLook == null) { //the predicate (functor) had never been declared
                assign(scope, name, false, reactor.get(node, "type"));
                return false;
            }
            //we need to make sure that each of the atoms in node.right are represented
            if (!(toLook instanceof PredicateRuleNode)) {
                Object[] toLookPrim = (Object[]) toLook;
                for (ExpressionNode atomNode : ((PredicateNode) node.right).parameters) {
                    boolean contained = false;
                    for (Object o : toLookPrim) {
                        if (o.equals(atomNode))
                            contained = true;
                    }
                    if (!contained) {
                        assign(scope, name, false, reactor.get(node, "type"));
                        return false;
                    }
                }
                assign(scope, name, true, reactor.get(node, "type"));
                return true;
            }
            //we have a rule
            //we check the existence of the pred -> would give a string
            Object[] toLook2 = (Object[]) sto.get(scope, ((PredicateRuleNode) toLook).predicate.name());
            if (toLook2 == null) { //the predicate (functor) had never been declared
                assign(scope, name, false, reactor.get(node, "type"));
                return false;
            }
            List<ExpressionNode> given_args = ((PredicateNode) node.right).parameters;
            List<ParameterNode> params = ((PredicateRuleNode) toLook).parameters;
            List<ExpressionNode> args = (((PredicateRuleNode) toLook).predicate).parameters;
            //sizes are not the same
            if (given_args.size() != params.size()) {
                assign(scope, name, false, reactor.get(node, "type"));
                //maybe give an error message here instead?
                return false;
            }

            for (ExpressionNode arg : args) {
                if (arg instanceof ReferenceNode) { //we need to find value
                    int pos = -1;
                    for (ParameterNode param : params) {
                        if (((ReferenceNode) arg).name.equals(param.name())) {
                            pos = params.indexOf(param);
                            break;
                        }
                    }
                    //deduce value from outer scope
                    if (pos == -1) {
                        //find value
                        Object r = get(arg); //exception here if not found
                        boolean contained = false;
                        for (Object o : toLook2) {
                            Object r1 = get((SighNode) o);
                            if (r1.equals(r)) {
                                contained = true;
                                break;
                            }
                        }
                        if (!contained) {
                            assign(scope, name, false, reactor.get(node, "type"));
                            return false;
                        }
                        //if not found -> shouldn't be reached
                        assign(scope, name, false, reactor.get(node, "type"));
                        return false;
                    }
                    //deduce value from given_args
                    Object given_arg = given_args.get(pos);
                    Object r2 = get((SighNode) given_arg);
                    boolean contained = false;
                    for (Object o : toLook2) {
                        Object r1 = get((SighNode) o);
                        if (r1.equals(r2)) {
                            contained = true;
                            break;
                        }
                    }
                    if (!contained) {
                        assign(scope, name, false, reactor.get(node, "type"));
                        return false;
                    }

                } else { //we have its value
                    Object r1 = get(arg);
                    boolean contained = false;
                    for (Object o : toLook2) {
                        Object r2 = get((SighNode) o);
                        if (r1.equals(r2)) //comparing values
                            contained = true;
                    }
                    if (!contained) {
                        assign(scope, name, false, reactor.get(node, "type"));
                        return false;
                    }
                }
            }
            assign(scope, name, true, reactor.get(node, "type"));
            return true;
        } else { //we have to evaluate a boolean expression (we have an expressionNode)
            boolean endVal = (Boolean) this.run(node.right);
            assign(scope, name, endVal, reactor.get(node, "type"));
            return endVal;
        }

        //return true;
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
}
