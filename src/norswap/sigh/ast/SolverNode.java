package norswap.sigh.ast;

import norswap.autumn.positions.Span;
import norswap.utils.Util;
import java.util.List;

public class SolverNode extends ExpressionNode
{
    public final List<PredicateUNode> list;

    @SuppressWarnings("unchecked")
    public SolverNode
        (Span span, Object list) {
        super(span);
        this.list = Util.cast(list, List.class);
    }

    @Override public String contents () {
        return "Solver : " + list.toString();
    }
}