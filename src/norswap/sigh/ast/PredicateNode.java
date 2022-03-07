package norswap.sigh.ast;

import norswap.autumn.positions.Span;
import norswap.utils.Util;
import java.util.List;

public class PredicateNode extends ExpressionNode{
    public final FunctorNode functor;
    public final List<ExpressionNode> arguments;

    public PredicateNode (Span span, Object aNode, Object arguments) {
        super(span);
        this.functor = Util.cast(aNode, FunctorNode.class);
        this.arguments = Util.cast(arguments, List.class);
    }

    @Override public String contents ()
    {
        return functor.contents();
    }
}
