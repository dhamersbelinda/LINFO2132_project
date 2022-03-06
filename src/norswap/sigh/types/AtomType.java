package norswap.sigh.types;

public final class AtomType extends Type
{
    public static final AtomType INSTANCE = new AtomType();
    private AtomType() {}

    //TODO: primitive or not?
    @Override public String name() {
        return "Atom";
    }
}
