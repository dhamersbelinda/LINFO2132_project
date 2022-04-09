package norswap.sigh.ast;

import norswap.autumn.positions.Span;
import norswap.utils.Util;

public class AtomDeclarationNode extends DeclarationNode {
    public final AtomLiteralNode atom;


    public AtomDeclarationNode (Span span, Object name) {
        super(span);
        this.atom = Util.cast(name, AtomLiteralNode.class);
    }

    //@Override
    public String name () {
        return atom.name;
    }

    //@Override
    public String declaredThing () {
        return "atom";
    }

    @Override
    public String contents () {
        return atom.contents();
    }
}
