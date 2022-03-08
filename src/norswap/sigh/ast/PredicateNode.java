package norswap.sigh.ast;

import norswap.autumn.positions.Span;
import norswap.utils.Util;
import java.util.List;

public class PredicateNode extends ExpressionNode{
    public final FunctorNode functor;
    public final List<ExpressionNode> arguments;
    public final Integer paramNum;

    public PredicateNode (Span span, Object aNode, Object arguments) {
        super(span);
        this.functor = Util.cast(aNode, FunctorNode.class);
        this.arguments = Util.cast(arguments, List.class);
        this.paramNum = this.arguments.size();
    }

    @Override public String contents ()
    {
        return functor.contents() + " as predicate with " + this.paramNum + " arguments";
    } //can change so it shows atoms too
}
