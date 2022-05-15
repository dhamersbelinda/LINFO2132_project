package norswap.sigh.ast;

import norswap.autumn.positions.Span;
import norswap.utils.Util;
import java.util.List;

public class PredicateUNode extends SighNode
{
    public final String name;
    public final List<SighNode> parameters;

    @SuppressWarnings("unchecked")
    public PredicateUNode
        (Span span, Object name, Object parameters) {
        super(span);
        this.name = Util.cast(name, String.class);
        this.parameters = Util.cast(parameters, List.class);
    }

    @Override public String contents () {
        return "pred " + name + " : " + parameters.toString();
    }
}
