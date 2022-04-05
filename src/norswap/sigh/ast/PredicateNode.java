package norswap.sigh.ast;

import norswap.autumn.positions.Span;
import norswap.utils.Util;
import java.util.List;

public class PredicateNode extends DeclarationNode
{
    public final String name;
    public final List<AtomLiteralNode> parameters; //todo check if they're all atoms in the semantics

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
        return "pred " + name;
    }

    @Override public String declaredThing () {
        return "predicate";
    }
}
