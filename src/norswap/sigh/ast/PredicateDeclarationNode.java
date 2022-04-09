package norswap.sigh.ast;

import norswap.autumn.positions.Span;
import norswap.utils.Util;

public class PredicateDeclarationNode extends DeclarationNode {
    public final PredicateNode predicate;


    public PredicateDeclarationNode (Span span, Object name) {
        super(span);
        this.predicate = Util.cast(name, PredicateNode.class);
    }

    @Override
    public String name () {
        return predicate.name;
    }

    @Override
    public String declaredThing () {
        return "predicate";
    }

    @Override
    public String contents () {
        return predicate.contents();
    }
}
