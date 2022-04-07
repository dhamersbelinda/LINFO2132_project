package norswap.sigh.ast;

import norswap.autumn.positions.Span;
import norswap.utils.Util;
import java.util.List;

public class PredicateNode extends DeclarationNode
{
    public final String name;
    public final List<AtomLiteralNode> parameters;

    @SuppressWarnings("unchecked")
    public PredicateNode
        (Span span, Object name, Object parameters) {
        super(span);
        this.name = Util.cast(name, String.class);
        this.parameters = Util.cast(parameters, List.class);
    }

    @Override public String name () {
        return name;
    }

    @Override public String contents () {
        return "pred " + name + " : " + parameters.toString();
    }

    @Override public String declaredThing () {
        return "predicate";
    }
}
